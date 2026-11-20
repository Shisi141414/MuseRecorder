package com.neuroskill.muserecorder.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.neuroskill.muserecorder.MainActivity
import com.neuroskill.muserecorder.R
import com.neuroskill.muserecorder.ble.ConnStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台服务：保活 BLE 采集与录制；状态栏常驻通知实时显示
 * 连接状态 / 电量 / 录制时长，并提供断开、停止录制操作。
 */
class RecordingService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "muse_status"
        const val NOTIFICATION_ID = 1001
        const val ACTION_DISCONNECT = "com.neuroskill.muserecorder.DISCONNECT"
        const val ACTION_STOP_RECORD = "com.neuroskill.muserecorder.STOP_RECORD"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        MuseHub.init(this)
        startAsForeground()
        observe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_DISCONNECT -> MuseHub.disconnect()
            ACTION_STOP_RECORD -> lifecycleScope.launch {
                MuseHub.stopRecording()
                MuseHub.maybeStopService()
            }
        }
        updateNotification()
        return START_STICKY
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        val type = if (Build.VERSION.SDK_INT >= 29)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            var lastUpdate = 0L
            while (isActive) {
                // 通知节流 + 状态变化即时更新
                delay(500)
                if (System.currentTimeMillis() - lastUpdate > 900) {
                    updateNotification()
                    lastUpdate = System.currentTimeMillis()
                }
            }
        }
        lifecycleScope.launch {
            MuseHub.conn.collect {
                updateNotification()
                syncWakeLock()
                // 完全空闲时停止前台服务
                if (it.status == ConnStatus.DISCONNECTED || it.status == ConnStatus.IDLE) {
                    if (!MuseHub.recorder.isRecording) stopSelf()
                }
            }
        }
        lifecycleScope.launch {
            MuseHub.recorder.state.collect {
                updateNotification()
                syncWakeLock()
            }
        }
    }

    /** 流式传输或录制期间持有部分唤醒锁，防止锁屏后 CPU 休眠丢数据 */
    private fun syncWakeLock() {
        val streaming = MuseHub.conn.value.status == ConnStatus.STREAMING
        val recording = MuseHub.recorder.isRecording
        if (streaming || recording) acquireWakeLock() else releaseWakeLock()
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectIntent = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopRecordIntent = PendingIntent.getService(
            this, 2, Intent(this, RecordingService::class.java).setAction(ACTION_STOP_RECORD),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val s = MuseHub.conn.value
        val rec = MuseHub.recorder.state.value
        val battery = MuseHub.battery.value

        val title = when {
            rec != null -> getString(R.string.notif_recording, formatDuration(System.currentTimeMillis() - rec.startedAtMs))
            s.status == ConnStatus.STREAMING -> getString(R.string.notif_streaming)
            s.status == ConnStatus.RECONNECTING -> getString(R.string.notif_reconnecting, s.attempt)
            s.status == ConnStatus.CONNECTING || s.status == ConnStatus.CONNECTED -> getString(R.string.notif_connecting)
            else -> getString(R.string.notif_idle)
        }
        val text = buildString {
            append(s.name ?: s.address ?: getString(R.string.notif_no_device))
            if (battery >= 0) append(" · ").append(getString(R.string.notif_battery, battery))
            if (rec != null) append(" · ").append(rec.rows).append(" ").append(getString(R.string.notif_rows))
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (rec != null) builder.addAction(0, getString(R.string.notif_stop_record), stopRecordIntent)
        if (s.status != ConnStatus.DISCONNECTED && s.status != ConnStatus.IDLE) {
            builder.addAction(0, getString(R.string.notif_disconnect), disconnectIntent)
        }
        return builder.build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        return String.format(
            java.util.Locale.US, "%02d:%02d:%02d",
            totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60,
        )
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MuseRecorder:stream").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}

package com.neuroskill.muserecorder.core

import android.content.Context
import android.content.Intent
import android.util.Log
import com.neuroskill.muserecorder.R
import com.neuroskill.muserecorder.ble.Capabilities
import com.neuroskill.muserecorder.ble.ConnState
import com.neuroskill.muserecorder.ble.ConnStatus
import com.neuroskill.muserecorder.ble.DeviceInfo
import com.neuroskill.muserecorder.ble.LogKind
import com.neuroskill.muserecorder.ble.MuseBle
import com.neuroskill.muserecorder.ble.MuseProtocol
import com.neuroskill.muserecorder.ble.ScanItem
import com.neuroskill.muserecorder.util.Locales
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 进程级中心枢纽：持有 BLE 客户端、记录器与全部 UI 状态流。
 * 前台服务与 Activity 共享该单例，Activity 被回收不影响采集。
 */
object MuseHub {

    private const val TAG = "MuseHub"

    lateinit var appContext: Context
        private set
    lateinit var prefs: Prefs
        private set

    /** 协程异常不致崩溃，且全部落盘（CrashReporter） */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> CrashReporter.logCoroutineError(e) },
    )

    val conn = MutableStateFlow(ConnState())
    val scanItems = MutableStateFlow<List<ScanItem>>(emptyList())
    val battery = MutableStateFlow(-1)
    val telemetry = MutableStateFlow<MuseProtocol.Telemetry?>(null)
    val deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val caps = MutableStateFlow<Capabilities?>(null)
    val errors = MutableStateFlow<String?>(null)

    /** 最近一次读取的 RSSI（dBm），未知为 Int.MIN_VALUE */
    val rssi = MutableStateFlow(Int.MIN_VALUE)

    /** 连接日志（最近 60 条，排障用；UI 展示最新在前） */
    data class LogLine(val time: String, val text: String, val kind: LogKind)

    val logLines = MutableStateFlow<List<LogLine>>(emptyList())
    private val logBuf = ArrayDeque<LogLine>()

    fun pushLog(line: String, kind: LogKind = LogKind.INFO) {
        synchronized(logBuf) {
            logBuf.addLast(
                LogLine(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()), line, kind),
            )
            while (logBuf.size > 60) logBuf.removeFirst()
            logLines.value = logBuf.toList()
        }
    }

    class QualityState(val levels: IntArray = IntArray(4) { -1 }, val headbandOn: Boolean = false)
    val quality = MutableStateFlow(QualityState())

    class StreamStats(
        val eegRate: Float = 0f,
        val accRate: Float = 0f,
        val gyroRate: Float = 0f,
        val ppgRate: Float = 0f,
        val opticsRate: Float = 0f,
    )

    val stats = MutableStateFlow(StreamStats())

    val recorder: CsvRecorder by lazy { CsvRecorder(appContext) }

    // ---------------- 当前事件（v1.6.0） ----------------

    /** 事件旁路文件（随记录会话开合）；惰性初始化，避免对象加载时访问未就绪的 appContext */
    val eventFileStore: EventFileStore by lazy { EventFileStore(appContext) }

    /** 当前事件跟踪（换选自动结束上一个；结束写入 _events.csv） */
    val tracker = EventTracker(
        clock = { System.currentTimeMillis() },
        onClosed = { rec -> scope.launch(Dispatchers.IO) { eventFileStore.write(rec) } },
    )

    /** 事件预设（首次读取按语言注入默认值） */
    fun defaultEventPresets(): List<String> =
        if (Locale.getDefault().language == "zh") {
            listOf("闭眼静息", "睁眼静息", "心算任务", "观看视频", "阅读")
        } else {
            listOf("Eyes closed rest", "Eyes open rest", "Mental arithmetic", "Watching video", "Reading")
        }

    fun eventPresets(): List<String> = prefs.eventPresets(defaultEventPresets())

    fun setEventPresets(list: List<String>) = prefs.setEventPresets(list)

    fun selectEvent(name: String?) {
        tracker.select(name)
        prefs.lastEventName = name ?: ""
    }

    /** 波形环形缓冲（4 通道 × 8 秒） */
    class WaveBuffer(val channels: Int, val capacity: Int) {
        val data = Array(channels) { FloatArray(capacity) }
        var writePos = 0
            private set
        var total = 0L
            private set

        @Synchronized
        fun write(batch: Array<FloatArray>) {
            val ch = minOf(batch.size, channels)
            for (i in batch[0].indices) {
                for (c in 0 until ch) data[c][writePos] = batch[c][i]
                writePos = (writePos + 1) % capacity
                total++
            }
        }

        /** 按时间顺序拷出最近 [n] 个样本到 out[ch][i]；返回实际数量 */
        @Synchronized
        fun copyOut(n: Int, out: Array<FloatArray>): Int {
            val count = minOf(n, total.toInt(), capacity)
            var start = (writePos - count + capacity) % capacity
            for (i in 0 until count) {
                for (c in 0 until channels) out[c][i] = data[c][start]
                start = (start + 1) % capacity
            }
            return count
        }
    }

    val wave = WaveBuffer(4, 2048)
    val displayAcc = MutableStateFlow<FloatArray?>(null)

    private val eegCount = AtomicLong()
    private val accCount = AtomicLong()
    private val gyroCount = AtomicLong()
    private val ppgCount = AtomicLong()
    private val opticsCount = AtomicLong()

    val ble: MuseBle by lazy {
        MuseBle(appContext, listener).also { b ->
            b.wantAccel = prefs.recordAccel
            b.wantGyro = prefs.recordGyro
            b.wantPpg = prefs.recordPpg
            b.preset = prefs.preset.takeIf { it.isNotBlank() }
            b.lowLatency = prefs.lowLatency
            b.lightOff = prefs.lightOff
        }
    }

    private var qualityEst: QualityEstimator? = null
    private var appContextSet = false

    fun init(context: Context) {
        if (appContextSet) return
        appContext = context.applicationContext
        prefs = Prefs(appContext)
        appContextSet = true
        CrashReporter.install(appContext)
        // 上次崩溃提示（一次性）
        CrashReporter.lastCrashName(appContext)?.let { name ->
            pushLog("上次异常记录: $name (Download/MuseRecorder)", LogKind.ERROR)
        }
        // 恢复上次选择的事件
        tracker.selectedName.value = prefs.lastEventName.takeIf { it.isNotBlank() }
        qualityEst = QualityEstimator { levels, headOn ->
            quality.value = QualityState(levels.copyOf(), headOn)
            recorder.setHsi(levels)
        }
        recorder.onFatal = { msg ->
            errors.value = msg
            pushLog(msg, LogKind.ERROR)
            tracker.onRecordingStopped()
            scope.launch(Dispatchers.IO) { eventFileStore.close() }
        }
        // 状态联动：断开/恢复时写入标记
        scope.launch {
            var prev: ConnStatus? = null
            conn.collect { s ->
                if (s.status != prev) {
                    if (recorder.isRecording) {
                        when (s.status) {
                            ConnStatus.RECONNECTING, ConnStatus.CONNECTING ->
                                if (prev == ConnStatus.STREAMING) recorder.addMarker("BLE_Disconnected")
                            ConnStatus.STREAMING ->
                                if (prev == ConnStatus.RECONNECTING || prev == ConnStatus.CONNECTING)
                                    recorder.addMarker("BLE_Reconnected")
                            else -> {}
                        }
                    }
                    prev = s.status
                }
            }
        }
        // 速率统计
        scope.launch {
            while (isActive) {
                val e0 = eegCount.get(); val a0 = accCount.get()
                val g0 = gyroCount.get(); val p0 = ppgCount.get(); val o0 = opticsCount.get()
                delay(1000)
                stats.value = StreamStats(
                    eegRate = (eegCount.get() - e0).toFloat(),
                    accRate = (accCount.get() - a0).toFloat(),
                    gyroRate = (gyroCount.get() - g0).toFloat(),
                    ppgRate = (ppgCount.get() - p0).toFloat(),
                    opticsRate = (opticsCount.get() - o0).toFloat(),
                )
            }
        }
    }

    // ---------------- 对外操作 ----------------

    fun startScan(deep: Boolean): Boolean {
        val ok = ble.startScan(scope, deep)
        if (ok) {
            scope.launch {
                while (conn.value.status == ConnStatus.SCANNING) {
                    scanItems.value = synchronized(ble.scanResults) { ble.scanResults.values.toList() }
                        .sortedByDescending { it.rssi }
                    delay(300)
                }
                scanItems.value = synchronized(ble.scanResults) { ble.scanResults.values.toList() }
                    .sortedByDescending { it.rssi }
            }
        }
        return ok
    }

    fun stopScan() = ble.stopScan()

    fun connect(item: ScanItem) {
        prefs.lastDeviceName = item.name ?: ""
        prefs.lastDeviceAddress = item.address
        startService()
        ble.connect(scope, item.address, item.name)
    }

    fun connectAddress(address: String, name: String?) {
        prefs.lastDeviceName = name ?: ""
        prefs.lastDeviceAddress = address
        startService()
        ble.connect(scope, address, name)
    }

    fun disconnect() {
        if (recorder.isRecording) {
            scope.launch {
                recorder.addMarker("RecordingStopped_Disconnect")
                stopRecordingInternal()
                ble.disconnect()
            }
        } else {
            ble.disconnect()
        }
    }

    fun onBluetoothOff() {
        ble.forceReset("蓝牙已关闭")
    }

    fun applyPrefsToBle() {
        ble.wantAccel = prefs.recordAccel
        ble.wantGyro = prefs.recordGyro
        ble.wantPpg = prefs.recordPpg
        ble.preset = prefs.preset.takeIf { it.isNotBlank() }
        ble.lowLatency = prefs.lowLatency
        ble.lightOff = prefs.lightOff
    }

    fun startRecording(): Boolean {
        if (!recorder.isRecording) {
            val c = caps.value
            val columns = CsvRecorder.Columns(
                aux = c == null || conn.value.protocol == com.neuroskill.muserecorder.ble.Protocol.LEGACY,
                accel = prefs.recordAccel && (c?.accel ?: false),
                gyro = prefs.recordGyro && (c?.gyro ?: false),
                optics = prefs.recordPpg && (c?.optics ?: false),
                ppg = prefs.recordPpg && (c?.ppg ?: false),
            )
            val name = recorder.start(scope, columns, prefs.filePrefix)
            if (name == null) {
                errors.value = Locales.ctx(appContext).getString(R.string.err_record_file)
                return false
            }
            recorder.setBattery(battery.value)
            startService()
            // 事件旁路文件 + 自动开启已选事件（主 CSV 不写事件标记）
            if (eventFileStore.open(name)) {
                tracker.onRecordingStarted()
            }
        }
        return true
    }

    suspend fun stopRecording() {
        stopRecordingInternal()
    }

    private suspend fun stopRecordingInternal() {
        tracker.onRecordingStopped()
        scope.launch(Dispatchers.IO) { eventFileStore.close() }
        recorder.stop()
    }

    fun addMarker(label: String) {
        if (recorder.isRecording) recorder.addMarker(label)
    }

    private fun startService() {
        try {
            val intent = Intent(appContext, RecordingService::class.java)
            appContext.startForegroundService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startService", e)
        }
    }

    fun maybeStopService() {
        if (!recorder.isRecording && conn.value.status.let {
                it == ConnStatus.DISCONNECTED || it == ConnStatus.IDLE
            }
        ) {
            try {
                appContext.stopService(Intent(appContext, RecordingService::class.java))
            } catch (_: Exception) {}
        }
    }

    // ---------------- BLE 回调 ----------------

    private val listener = object : MuseBle.Listener {
        private var lastLoggedStatus: ConnStatus? = null

        /** 本地化字符串 */
        private fun s(id: Int, vararg args: Any): String =
            Locales.ctx(appContext).getString(id, *args)

        override fun onState(state: ConnState) {
            conn.value = state
            if (state.status != lastLoggedStatus) {
                lastLoggedStatus = state.status
                if (state.status == ConnStatus.STREAMING) {
                    pushLog(s(R.string.log_streaming_ok), LogKind.OK)
                } else if (state.status != ConnStatus.IDLE) {
                    val kind = when (state.status) {
                        ConnStatus.DISCONNECTED -> LogKind.ERROR
                        ConnStatus.SCANNING, ConnStatus.CONNECTING,
                        ConnStatus.CONNECTED, ConnStatus.RECONNECTING -> LogKind.WARN
                        else -> LogKind.INFO
                    }
                    val nameRes = when (state.status) {
                        ConnStatus.SCANNING -> R.string.status_scanning
                        ConnStatus.CONNECTING -> R.string.status_connecting
                        ConnStatus.CONNECTED -> R.string.status_connected
                        ConnStatus.STREAMING -> R.string.status_streaming
                        ConnStatus.RECONNECTING -> R.string.status_reconnecting
                        ConnStatus.DISCONNECTED -> R.string.status_disconnected
                        else -> R.string.status_idle
                    }
                    val text = s(R.string.log_state_fmt, s(nameRes)) +
                        (state.message?.let { " · $it" } ?: "")
                    pushLog(text, kind)
                }
            }
        }

        override fun onEeg(batch: MuseProtocol.SampleBatch, auxIncluded: Boolean) {
            eegCount.addAndGet(batch.ts.size.toLong())
            wave.write(batch.data)
            qualityEst?.feed(batch.data)
            if (recorder.isRecording) {
                recorder.offer(
                    CsvRecorder.Event.Eeg(
                        batch.ts,
                        if (auxIncluded) batch.data
                        else com.neuroskill.muserecorder.ble.MuseProtocol.takeChannels(batch.data, 4),
                    )
                )
            }
        }

        override fun onAcc(batch: MuseProtocol.SampleBatch) {
            accCount.addAndGet(batch.ts.size.toLong())
            displayAcc.value = FloatArray(3) { batch.data[it].lastOrNull() ?: Float.NaN }
            if (recorder.isRecording) {
                recorder.offer(CsvRecorder.Event.Acc(FloatArray(3) { batch.data[it].lastOrNull() ?: Float.NaN }))
            }
        }

        override fun onGyro(batch: MuseProtocol.SampleBatch) {
            gyroCount.addAndGet(batch.ts.size.toLong())
            if (recorder.isRecording) {
                recorder.offer(CsvRecorder.Event.Gyro(FloatArray(3) { batch.data[it].lastOrNull() ?: Float.NaN }))
            }
        }

        override fun onPpg(batch: MuseProtocol.SampleBatch) {
            ppgCount.addAndGet(batch.ts.size.toLong())
            if (recorder.isRecording) {
                recorder.offer(CsvRecorder.Event.Ppg(FloatArray(3) { batch.data[it].lastOrNull() ?: Float.NaN }))
            }
        }

        override fun onOptics(batch: MuseProtocol.SampleBatch) {
            opticsCount.addAndGet(batch.ts.size.toLong())
            if (recorder.isRecording) {
                recorder.offer(
                    CsvRecorder.Event.Optics(
                        FloatArray(16) { i -> batch.data.getOrNull(i)?.lastOrNull() ?: Float.NaN },
                    )
                )
            }
        }

        override fun onBattery(percent: Int) {
            battery.value = percent
            recorder.setBattery(percent)
        }

        override fun onTelemetry(t: MuseProtocol.Telemetry) {
            telemetry.value = t
            if (t.batteryPercent in 0f..100f) {
                battery.value = t.batteryPercent.toInt()
                recorder.setBattery(t.batteryPercent.toInt())
            }
        }

        override fun onControlMessage(message: String) {
            deviceInfo.value = ble.deviceInfo
        }

        override fun onCapabilities(caps: Capabilities) {
            this@MuseHub.caps.value = caps
        }

        override fun onError(what: String) {
            Log.w(TAG, "ble: $what")
            errors.value = what
            pushLog(what, LogKind.ERROR)
        }

        override fun onRssi(value: Int) {
            rssi.value = value
        }

        override fun onLog(line: String, kind: LogKind) {
            pushLog(line, kind)
        }
    }
}

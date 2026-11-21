package com.neuroskill.muserecorder.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.neuroskill.muserecorder.R
import com.neuroskill.muserecorder.ble.MuseProtocol.RlsCorrector
import com.neuroskill.muserecorder.ble.MuseProtocol.SampleBatch
import com.neuroskill.muserecorder.util.Locales
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

enum class Protocol { ATHENA, LEGACY }

enum class ConnStatus { IDLE, SCANNING, CONNECTING, CONNECTED, STREAMING, RECONNECTING, DISCONNECTED }

data class ConnState(
    val status: ConnStatus = ConnStatus.IDLE,
    val name: String? = null,
    val address: String? = null,
    val protocol: Protocol? = null,
    val attempt: Int = 0,
    val message: String? = null,
)

data class ScanItem(val name: String?, val address: String, val rssi: Int, val lastSeenMs: Long)

data class DeviceInfo(
    val name: String? = null,
    val serial: String? = null,
    val firmware: String? = null,
    val hardware: String? = null,
    val preset: String? = null,
)

/** 设备能力（连接后探测） */
data class Capabilities(
    val eeg: Boolean = false,
    val accel: Boolean = false,
    val gyro: Boolean = false,
    val ppg: Boolean = false,     // legacy 3 通道 PPG
    val optics: Boolean = false,  // Athena 16 通道光学
    val telemetry: Boolean = false,
)

/** 日志类别（连接日志按色区分） */
enum class LogKind { INFO, OK, WARN, ERROR }

/**
 * Muse BLE 客户端：扫描、连接、GATT 串行配置、双协议解码、
 * 数据看门狗与自动重连。
 *
 * 所有 onXxx 回调在 Binder 线程触发，实现方需自行保证线程安全。
 */
@SuppressLint("MissingPermission")
class MuseBle(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onState(state: ConnState)
        fun onEeg(batch: SampleBatch, auxIncluded: Boolean)
        fun onAcc(batch: SampleBatch)
        fun onGyro(batch: SampleBatch)
        fun onPpg(batch: SampleBatch)
        fun onOptics(batch: SampleBatch)
        fun onBattery(percent: Int)
        fun onTelemetry(t: MuseProtocol.Telemetry)
        fun onControlMessage(message: String)
        fun onCapabilities(caps: Capabilities)
        fun onError(what: String)
        fun onRssi(rssi: Int)
        fun onLog(line: String, kind: LogKind = LogKind.INFO)
    }

    companion object {
        private const val TAG = "MuseBle"
        private val BACKOFF_S = longArrayOf(2, 3, 5, 8, 13, 21, 30)
        private const val WATCHDOG_STALL_MS = 5000L
        private const val WATCHDOG_DROP_MS = 12000L
        private const val EEG_COMPLETE_IDX_LEGACY = 1   // AF7 包到达即一组完成
        private const val PPG_COMPLETE_IDX_LEGACY = 2   // red 包到达即一组完成
        val ATHENA_PRESETS = setOf(
            "p20", "p21", "p50", "p51", "p60", "p61",
            "p1034", "p1035", "p1041", "p1042", "p1043", "p1044", "p1045", "p1046", "p4129",
        )
    }

    // ---------------- 配置（连接前由外部设置） ----------------

    @Volatile var wantAccel = true
    @Volatile var wantGyro = true
    @Volatile var wantPpg = true
    @Volatile var preset: String? = null        // null = 自动（Athena p1041 / legacy p21）
    @Volatile var lowLatency = true
    @Volatile var lightOff = false
    @Volatile var autoReconnect = true

    @Volatile var currentState = ConnState()
        private set

    val scanResults = LinkedHashMap<String, ScanItem>()
    var deviceInfo = DeviceInfo()
        private set

    // ---------------- 内部状态 ----------------

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var scanJob: Job? = null
    private var scanCallback: ScanCallback? = null
    @Volatile private var deepScan = false

    private var scope: CoroutineScope? = null
    private var gatt: BluetoothGatt? = null
    private var targetName: String? = null
    private var targetAddress: String? = null
    @Volatile private var shouldStayConnected = false
    @Volatile private var protocol: Protocol? = null
    @Volatile private var lastDataMs = 0L
    private var caps = Capabilities()

    private var servicesDeferred: CompletableDeferred<Int>? = null
    private var mtuDeferred: CompletableDeferred<Int>? = null
    private var descDeferred: CompletableDeferred<Int>? = null
    private var connectionLost: CompletableDeferred<Unit>? = null

    private var controlChar: BluetoothGattCharacteristic? = null

    private val controlAssembler = MuseProtocol.ControlAssembler()

    private var eegCorrector: RlsCorrector? = null
    private var accCorrector: RlsCorrector? = null
    private var gyroCorrector: RlsCorrector? = null
    private var ppgCorrector: RlsCorrector? = null
    private var opticsCorrector: RlsCorrector? = null

    private val legacyEeg = Array(5) { FloatArray(MuseProtocol.LEGACY_EEG_SAMPLES) }
    private val legacyEegArrival = DoubleArray(5) { Double.MAX_VALUE }
    private var legacyEegLastTm = -1
    private var legacyEegSampleIndex = 0L

    private val legacyPpg = Array(3) { FloatArray(MuseProtocol.LEGACY_PPG_SAMPLES) }
    private var legacyPpgLastTm = -1
    private var legacyPpgSampleIndex = 0L

    @Volatile private var lastCmdMs = 0L

    // ====================== 扫描 ======================

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    /** 按当前应用语言取字符串 */
    private fun str(id: Int, vararg args: Any): String =
        Locales.ctx(context).getString(id, *args)

    fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) has(Manifest.permission.BLUETOOTH_SCAN)
        else has(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 || has(Manifest.permission.BLUETOOTH_CONNECT)

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    /** 开始扫描；返回 false 表示无权限或蓝牙未开启 */
    fun startScan(scope: CoroutineScope, deep: Boolean): Boolean {
        val a = adapter ?: return false
        if (!a.isEnabled || !hasScanPermission()) return false
        stopScanInternal()
        deepScan = deep
        synchronized(scanResults) { scanResults.clear() }
        setState(currentState.copy(status = ConnStatus.SCANNING, message = null))
        val scanner = a.bluetoothLeScanner ?: return false
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        val filters = if (deep) null
        else listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(MuseUuids.ADVERTISED_SERVICE)).build())
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name
                if (deepScan && (name == null || !name.startsWith("Muse"))) return
                val item = ScanItem(name, result.device.address, result.rssi, System.currentTimeMillis())
                synchronized(scanResults) { scanResults[item.address] = item }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "scan failed: $errorCode")
                setState(currentState.copy(status = ConnStatus.IDLE, message = str(R.string.err_scan_fmt, errorCode)))
            }
        }
        scanCallback = cb
        try {
            scanner.startScan(filters, settings, cb)
        } catch (e: SecurityException) {
            Log.e(TAG, "startScan", e)
            return false
        }
        scanJob = scope.launch {
            delay(if (deep) 12000L else 10000L)
            stopScanInternal()
            setState(currentState.copy(status = ConnStatus.IDLE))
        }
        return true
    }

    fun stopScan() {
        stopScanInternal()
        if (currentState.status == ConnStatus.SCANNING)
            setState(currentState.copy(status = ConnStatus.IDLE))
    }

    private fun stopScanInternal() {
        scanJob?.cancel(); scanJob = null
        val cb = scanCallback
        scanCallback = null
        if (cb != null) {
            try { adapter?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {}
        }
    }

    // ====================== 连接 ======================

    fun connect(scope: CoroutineScope, address: String, name: String?) {
        val a = adapter
        if (a == null || !hasConnectPermission()) {
            setState(ConnState(ConnStatus.DISCONNECTED, message = str(R.string.err_no_permission)))
            return
        }
        stopScanInternal()
        shouldStayConnected = true
        this.scope = scope
        targetAddress = address
        targetName = name
        val device = try {
            a.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            setState(ConnState(ConnStatus.DISCONNECTED, message = str(R.string.err_invalid_addr)))
            return
        }
        scope.launch { runConnectionLoop(device) }
    }

    /** 用户主动断开 */
    fun disconnect() {
        shouldStayConnected = false
        stopScanInternal()
        try { gatt?.disconnect() } catch (_: Exception) {}
        closeGatt()
        streaming = false
        setState(ConnState(ConnStatus.DISCONNECTED, name = targetName, address = targetAddress))
    }

    /** 系统事件（蓝牙关闭）强制复位 */
    fun forceReset(reason: String) {
        shouldStayConnected = false
        stopScanInternal()
        try { gatt?.disconnect() } catch (_: Exception) {}
        closeGatt()
        streaming = false
        setState(ConnState(ConnStatus.DISCONNECTED, name = targetName, address = targetAddress, message = reason))
    }

    @Volatile private var streaming = false

    private fun closeGatt() {
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    /** GATT 状态码的人话描述（排障用，随应用语言） */
    private fun describeGattStatus(s: Int): String = when (s) {
        0 -> str(R.string.gatt_ok)
        5 -> str(R.string.gatt_insufficient_enc)
        8 -> str(R.string.gatt_timeout)
        13 -> str(R.string.gatt_conn_fail)
        16 -> str(R.string.gatt_busy)
        19 -> str(R.string.gatt_remote_closed)
        22 -> str(R.string.gatt_local_closed)
        34 -> str(R.string.gatt_cancelled)
        59 -> str(R.string.gatt_auto_na)
        62 -> str(R.string.gatt_full)
        133 -> str(R.string.gatt_133)
        257 -> str(R.string.gatt_257)
        else -> "status=$s"
    }

    /** 清除系统 GATT 缓存（反射调用隐藏 API，失败忽略）；可解决 133 循环 */
    private fun refreshGattCache(g: BluetoothGatt?) {
        if (g == null) return
        try {
            val m = BluetoothGatt::class.java.getMethod("refresh")
            m.isAccessible = true
            m.invoke(g)
        } catch (_: Exception) {
        }
    }

    private fun setState(s: ConnState) {
        currentState = s
        listener.onState(s)
    }

    private suspend fun runConnectionLoop(initialDevice: BluetoothDevice) {
        var device = initialDevice
        var attempt = 0
        while (shouldStayConnected) {
            var lastError: String? = null
            try {
                setState(
                    if (attempt == 0) ConnState(ConnStatus.CONNECTING, targetName, device.address)
                    else ConnState(ConnStatus.RECONNECTING, targetName, device.address, attempt = attempt)
                )
                connectOnce(device, attempt)
                // 正常返回 = 连接已断开
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "connection error", e)
                listener.onLog(str(R.string.log_failed_fmt, lastError!!), LogKind.ERROR)
            } finally {
                if (shouldStayConnected && autoReconnect) refreshGattCache(gatt)
                closeGatt()
                streaming = false
            }
            if (!shouldStayConnected) break
            if (!autoReconnect) break
            attempt++
            val backoff = BACKOFF_S[minOf(attempt - 1, BACKOFF_S.size - 1)]
            setState(
                ConnState(
                    ConnStatus.RECONNECTING, targetName, device.address,
                    attempt = attempt,
                    message = (lastError?.let { "$it · " } ?: "") + str(R.string.log_backoff_fmt, backoff),
                )
            )
            // 连续失败且知道设备名：重新扫描刷新地址
            if (attempt >= 3 && targetName != null) {
                val refreshed = refreshAddress(targetName!!)
                if (refreshed != null && refreshed != device.address) {
                    val a = adapter
                    if (a != null) {
                        val remote = try { a.getRemoteDevice(refreshed) } catch (_: IllegalArgumentException) { null }
                        if (remote != null) {
                            device = remote
                            targetAddress = refreshed
                            continue
                        }
                    }
                }
            }
            delay(backoff * 1000L)
        }
        setState(ConnState(ConnStatus.DISCONNECTED, name = targetName, address = targetAddress))
    }

    /** 单次完整连接周期；挂起直到连接断开或失败抛异常 */
    private suspend fun connectOnce(device: BluetoothDevice, attempt: Int) {
        val lost = CompletableDeferred<Unit>()
        connectionLost = lost
        val svc = CompletableDeferred<Int>()
        servicesDeferred = svc
        lastDataMs = System.currentTimeMillis()
        controlChar = null
        controlAssembler.reset()
        caps = Capabilities()

        val auto = attempt >= 3   // 多次失败后改用后台自动连接（部分系统更稳定）
        val g = device.connectGatt(context, auto, gattCallback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IllegalStateException(str(R.string.err_connect_gatt))
        gatt = g
        listener.onLog(
            if (auto) str(R.string.log_connect_bg_fmt, device.address, attempt + 1)
            else str(R.string.log_connect_fmt, device.address, attempt + 1),
            LogKind.INFO,
        )
        // 关键：不在此处调用 discoverServices —— 等 onConnectionStateChange(STATE_CONNECTED)
        // 回调后延迟触发，否则多数手机直接 GATT 133 失败循环

        val status = withTimeoutOrNull(if (auto) 30000L else 15000L) { svc.await() }
        servicesDeferred = null
        if (status == null) throw IllegalStateException(str(R.string.err_conn_timeout))
        if (status != BluetoothGatt.GATT_SUCCESS) {
            throw IllegalStateException(str(R.string.err_services_fmt, describeGattStatus(status)))
        }
        listener.onLog(str(R.string.log_services_done), LogKind.OK)

        // Athena 数据帧较长，协商更大 MTU（尽力而为）
        requestMtuSafe(g)

        // 探测协议
        val charMap = HashMap<UUID, BluetoothGattCharacteristic>()
        for (s in g.services) for (c in s.characteristics) charMap[c.uuid] = c
        val proto = when {
            charMap.containsKey(MuseUuids.ATHENA_DATA1) -> Protocol.ATHENA
            charMap.containsKey(MuseUuids.EEG_TP9) -> Protocol.LEGACY
            else -> {
                shouldStayConnected = false
                setState(
                    ConnState(
                        ConnStatus.DISCONNECTED, targetName, device.address,
                        message = str(R.string.err_not_muse),
                    )
                )
                return
            }
        }
        protocol = proto
        controlChar = charMap[MuseUuids.CONTROL] ?: throw IllegalStateException(str(R.string.err_no_control))

        setState(ConnState(ConnStatus.CONNECTED, targetName, device.address, protocol = proto))

        var subscribed = 0
        when (proto) {
            Protocol.ATHENA -> {
                caps = Capabilities(eeg = true, optics = true, accel = true, gyro = true)
                if (subscribe(charMap[MuseUuids.CONTROL]!!)) subscribed++
                if (subscribe(charMap[MuseUuids.ATHENA_DATA1]!!)) subscribed++
                charMap[MuseUuids.ATHENA_DATA2]?.let { if (subscribe(it)) subscribed++ }
            }
            Protocol.LEGACY -> {
                val eegChars = MuseUuids.LEGACY_EEG_CHANNELS.mapNotNull { charMap[it] }
                if (eegChars.size < 5) throw IllegalStateException(str(R.string.err_eeg_chars_fmt, eegChars.size))
                caps = Capabilities(
                    eeg = true,
                    accel = charMap.containsKey(MuseUuids.ACCELEROMETER),
                    gyro = charMap.containsKey(MuseUuids.GYRO),
                    ppg = charMap.containsKey(MuseUuids.PPG1),
                    telemetry = charMap.containsKey(MuseUuids.TELEMETRY),
                )
                charMap[MuseUuids.TELEMETRY]?.let { if (subscribe(it)) subscribed++ }
                if (caps.accel && wantAccel) charMap[MuseUuids.ACCELEROMETER]?.let { if (subscribe(it)) subscribed++ }
                if (caps.gyro && wantGyro) charMap[MuseUuids.GYRO]?.let { if (subscribe(it)) subscribed++ }
                if (caps.ppg && wantPpg) {
                    if (subscribe(charMap[MuseUuids.PPG1]!!)) subscribed++
                    if (subscribe(charMap[MuseUuids.PPG2]!!)) subscribed++
                    if (subscribe(charMap[MuseUuids.PPG3]!!)) subscribed++
                }
                for (c in eegChars) if (subscribe(c)) subscribed++
                if (subscribe(charMap[MuseUuids.CONTROL]!!)) subscribed++
            }
        }
        if (subscribed == 0) throw IllegalStateException(str(R.string.err_no_subscribe))
        listener.onCapabilities(caps)

        // ---- 初始化与开流 ----
        resetStreamState()
        when (proto) {
            Protocol.ATHENA -> {
                if (lightOff) { sendCommand("L0"); delay(150) }
                sendCommand("v6"); delay(200)
                sendCommand("s"); delay(200)
                sendCommand("h"); delay(200)
                sendCommand(normalizedPreset(proto)); delay(200)
                sendCommand("dc001"); delay(60)
                sendCommand("dc001"); delay(100)
                if (lowLatency) { sendCommand("L1"); delay(100) }
                sendCommand("s"); delay(250)
            }
            Protocol.LEGACY -> {
                sendCommand(normalizedPreset(proto)); delay(250)
                sendCommand("v1"); delay(200)
                sendCommand("s"); delay(200)   // 状态查询（回复含电量 bp）
                sendCommand("d")               // 开始数据流
                delay(200)
            }
        }

        streaming = true
        lastDataMs = System.currentTimeMillis()
        setState(ConnState(ConnStatus.STREAMING, targetName, device.address, protocol = proto))

        // ---- 看门狗，直到断开 ----
        val watchJob = scope?.launch {
            var rssiTick = 0
            while (true) {
                delay(2000L)
                if (!streaming) break   // 连接已切换/断开，停止本看门狗
                if (++rssiTick >= 2) {   // 每 4 秒读一次信号强度
                    rssiTick = 0
                    try { g.readRemoteRssi() } catch (_: Exception) {}
                }
                val idle = System.currentTimeMillis() - lastDataMs
                if (idle > WATCHDOG_DROP_MS) {
                    listener.onError(str(R.string.log_no_data_fmt, (idle / 1000).toInt()))
                    try { g.disconnect() } catch (_: Exception) {}
                    break
                } else if (idle > WATCHDOG_STALL_MS) {
                    when (protocol) {
                        Protocol.ATHENA -> { sendCommand("dc001"); delay(60); sendCommand("s") }
                        Protocol.LEGACY -> sendCommand("d")
                        null -> {}
                    }
                }
            }
        }
        try {
            lost.await()
        } finally {
            watchJob?.cancel()
            streaming = false
        }
    }

    private suspend fun requestMtuSafe(g: BluetoothGatt) {
        val d = CompletableDeferred<Int>()
        mtuDeferred = d
        val ok = try { g.requestMtu(185) } catch (e: Exception) { false }
        if (!ok) { mtuDeferred = null; return }
        withTimeoutOrNull(3000L) { d.await() }
        mtuDeferred = null
    }

    /** 开启特征 notify（写 CCCD 并等待完成）；返回是否发起成功 */
    private suspend fun subscribe(c: BluetoothGattCharacteristic): Boolean {
        val g = gatt ?: return false
        try { g.setCharacteristicNotification(c, true) } catch (_: Exception) {}
        val cccd = c.getDescriptor(MuseUuids.CCCD) ?: return false
        val d = CompletableDeferred<Int>()
        descDeferred = d
        val initiated = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
        if (!initiated) { descDeferred = null; return false }
        val st: Int? = withTimeoutOrNull(5000L) { d.await() }
        descDeferred = null
        if (st == null) listener.onLog(str(R.string.log_sub_timeout_fmt, c.uuid.toString()), LogKind.WARN)
        else if (st != BluetoothGatt.GATT_SUCCESS) {
            listener.onLog(
                str(R.string.log_sub_fail_fmt, c.uuid.toString(), describeGattStatus(st)),
                LogKind.ERROR,
            )
        }
        return true
    }

    // ====================== 命令 ======================

    private fun normalizedPreset(proto: Protocol): String {
        val configured = preset?.trim()?.takeIf { it.isNotEmpty() }
        if (configured == null) return if (proto == Protocol.ATHENA) "p1041" else "p21"
        val p = if (configured.startsWith("p")) configured else "p$configured"
        if (proto == Protocol.ATHENA && p !in ATHENA_PRESETS) {
            listener.onError(str(R.string.warn_preset_fmt, p))
            return "p1041"
        }
        return p
    }

    /** 写控制命令；调用方需自行保证 ≥50ms 间隔（限速不阻塞超过 50ms） */
    fun sendCommand(cmd: String) {
        val g = gatt ?: return
        val c = controlChar ?: return
        val bytes = MuseProtocol.encodeCommand(cmd)
        val now = System.currentTimeMillis()
        val since = now - lastCmdMs
        if (since in 0 until 50) {
            try {
                Thread.sleep(50 - since)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        lastCmdMs = System.currentTimeMillis()
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                val rc = g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                if (rc != BluetoothStatusCodes.SUCCESS) {
                    listener.onLog(str(R.string.log_cmd_fail_fmt, cmd, rc), LogKind.ERROR)
                }
            } else {
                @Suppress("DEPRECATION")
                c.setValue(bytes)
                @Suppress("DEPRECATION")
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        } catch (_: Exception) {
        }
        Log.d(TAG, "cmd -> $cmd")
    }

    // ====================== GATT 回调 ======================

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onServicesDiscovered(g: BluetoothGatt?, status: Int) {
            servicesDeferred?.complete(status)
            servicesDeferred = null
        }

        override fun onMtuChanged(g: BluetoothGatt?, mtu: Int, status: Int) {
            mtuDeferred?.complete(mtu)
        }

        override fun onDescriptorWrite(g: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            descDeferred?.complete(status)
        }

        override fun onConnectionStateChange(g: BluetoothGatt?, status: Int, newState: Int) {
            Log.i(TAG, "conn state: status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener.onLog(str(R.string.log_gatt_connected), LogKind.OK)
                // 延迟 300ms 再发现服务：多数手机立即调用会导致 133 失败；失败自动重试
                scope?.launch {
                    delay(300)
                    repeat(3) {
                        val ok = try { g?.discoverServices() == true } catch (_: Exception) { false }
                        if (ok) return@launch
                        delay(300)
                    }
                    servicesDeferred?.completeExceptionally(
                        IllegalStateException(str(R.string.err_discover_start)),
                    )
                    servicesDeferred = null
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val desc = describeGattStatus(status)
                listener.onLog(str(R.string.log_disconnected_fmt, desc), LogKind.ERROR)
                servicesDeferred?.completeExceptionally(
                    IllegalStateException(str(R.string.log_disconnected_fmt, desc)),
                )
                servicesDeferred = null
                mtuDeferred?.complete(-1)
                descDeferred?.complete(-1)
                connectionLost?.complete(Unit)
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt?, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) listener.onRssi(rssi)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt?, c: BluetoothGattCharacteristic?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onLog(str(R.string.log_write_fail_fmt, describeGattStatus(status)), LogKind.ERROR)
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotify(characteristic.uuid, value)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            val v = characteristic?.value ?: return
            handleNotify(characteristic.uuid, v)
        }
    }

    // ====================== 数据分发 ======================

    private fun handleNotify(uuid: UUID, data: ByteArray) {
        if (data.isEmpty()) return
        lastDataMs = System.currentTimeMillis()
        try {
            when {
                MuseUuids.isAthenaData(uuid) -> onAthenaData(data)
                uuid == MuseUuids.CONTROL -> onControl(data)
                uuid == MuseUuids.TELEMETRY -> listener.onTelemetry(MuseProtocol.decodeLegacyTelemetry(data))
                uuid == MuseUuids.ACCELEROMETER -> onLegacyImu(data, isAcc = true)
                uuid == MuseUuids.GYRO -> onLegacyImu(data, isAcc = false)
                uuid == MuseUuids.PPG1 -> onLegacyPpg(0, data)
                uuid == MuseUuids.PPG2 -> onLegacyPpg(1, data)
                uuid == MuseUuids.PPG3 -> onLegacyPpg(2, data)
                MuseUuids.LEGACY_EEG_CHANNELS.contains(uuid) ->
                    onLegacyEeg(MuseUuids.LEGACY_EEG_CHANNELS.indexOf(uuid), data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "notify parse error", e)
        }
    }

    private fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0

    // ---- Athena ----

    private fun onAthenaData(data: ByteArray) {
        val packets = MuseProtocol.splitPackets(data)
        if (packets.isEmpty()) return
        val hostTime = nowSeconds()
        for (packet in packets) {
            for (block in MuseProtocol.blocks(packet)) {
                val cfg = MuseProtocol.SENSOR_CONFIG[block.tag] ?: continue
                when (cfg.kind) {
                    MuseProtocol.SensorCfg.KIND_EEG -> {
                        val ch = MuseProtocol.decodeAthenaEeg(block.payload, cfg.nChannels, cfg.nSamples)
                        val n = cfg.nSamples
                        val ts = DoubleArray(n)
                        val corr = eegCorrector ?: RlsCorrector(MuseProtocol.EEG_RATE, hostTime).also { eegCorrector = it }
                        corr.timestamps(n, hostTime, ts)
                        listener.onEeg(SampleBatch(ts, MuseProtocol.takeChannels(ch, 4)), auxIncluded = false)
                    }
                    MuseProtocol.SensorCfg.KIND_ACC_GYRO -> {
                        val (acc, gyro) = MuseProtocol.decodeAthenaAccGyro(block.payload, cfg.nSamples)
                        val n = cfg.nSamples
                        val ts = DoubleArray(n)
                        val corr = accCorrector ?: RlsCorrector(MuseProtocol.IMU_RATE, hostTime).also { accCorrector = it }
                        corr.timestamps(n, hostTime, ts)
                        listener.onAcc(SampleBatch(ts.copyOf(n), acc))
                        listener.onGyro(SampleBatch(ts.copyOf(n), gyro))
                    }
                    MuseProtocol.SensorCfg.KIND_OPTICS -> {
                        val ch = MuseProtocol.decodeAthenaOptics(block.payload, block.tag, cfg.nChannels, cfg.nSamples)
                        val n = cfg.nSamples
                        val ts = DoubleArray(n)
                        val corr = opticsCorrector ?: RlsCorrector(MuseProtocol.OPTICS_RATE, hostTime).also { opticsCorrector = it }
                        corr.timestamps(n, hostTime, ts)
                        listener.onOptics(SampleBatch(ts, ch))
                    }
                    MuseProtocol.SensorCfg.KIND_BATTERY -> {
                        val pct = MuseProtocol.decodeAthenaBattery(block.payload)
                        if (pct >= 0) listener.onBattery(pct)
                    }
                    else -> {}
                }
            }
        }
    }

    // ---- Legacy ----

    private fun onLegacyEeg(chIndex: Int, packet: ByteArray) {
        if (packet.size < 20) return
        val (tm, values) = MuseProtocol.decodeLegacyEeg(packet)
        System.arraycopy(values, 0, legacyEeg[chIndex], 0, values.size)
        legacyEegArrival[chIndex] = nowSeconds()
        if (chIndex != EEG_COMPLETE_IDX_LEGACY) return

        val hostTime = legacyEegArrival.min()
        val n = MuseProtocol.LEGACY_EEG_SAMPLES
        val ts = DoubleArray(n)
        if (legacyEegLastTm != -1) {
            val delta = tm - legacyEegLastTm
            if (delta != 1 && delta != -65535) {
                legacyEegSampleIndex += n.toLong() * (delta + 1)
            }
        }
        legacyEegLastTm = tm
        val corr = eegCorrector ?: RlsCorrector(MuseProtocol.EEG_RATE, hostTime).also { eegCorrector = it }
        corr.advanceTo(legacyEegSampleIndex)
        corr.timestamps(n, hostTime, ts)
        legacyEegSampleIndex += n
        listener.onEeg(SampleBatch(ts, legacyEeg.map { it.copyOf() }.toTypedArray()), auxIncluded = true)
        for (i in legacyEegArrival.indices) legacyEegArrival[i] = Double.MAX_VALUE
    }

    private fun onLegacyImu(packet: ByteArray, isAcc: Boolean) {
        if (packet.size < 20) return
        val scale = if (isAcc) MuseProtocol.LEGACY_ACC_SCALE else MuseProtocol.LEGACY_GYRO_SCALE
        val (_, samples) = MuseProtocol.decodeLegacyImu(packet, scale)
        val n = MuseProtocol.LEGACY_IMU_SAMPLES
        val hostTime = nowSeconds()
        val ts = DoubleArray(n)
        if (isAcc) {
            val corr = accCorrector ?: RlsCorrector(MuseProtocol.IMU_RATE, hostTime).also { accCorrector = it }
            corr.timestamps(n, hostTime, ts)
            listener.onAcc(SampleBatch(ts, samples))
        } else {
            val corr = gyroCorrector ?: RlsCorrector(MuseProtocol.IMU_RATE, hostTime).also { gyroCorrector = it }
            corr.timestamps(n, hostTime, ts)
            listener.onGyro(SampleBatch(ts, samples))
        }
    }

    private fun onLegacyPpg(chIndex: Int, packet: ByteArray) {
        if (packet.size < 20) return
        val (tm, counts) = MuseProtocol.decodeLegacyPpg(packet)
        for (i in counts.indices) legacyPpg[chIndex][i] = counts[i].toFloat()
        if (chIndex != PPG_COMPLETE_IDX_LEGACY) return
        val hostTime = nowSeconds()
        val n = MuseProtocol.LEGACY_PPG_SAMPLES
        val ts = DoubleArray(n)
        if (legacyPpgLastTm != -1) {
            val delta = tm - legacyPpgLastTm
            if (delta != 1 && delta != -65535) {
                legacyPpgSampleIndex += n.toLong() * (delta + 1)
            }
        }
        legacyPpgLastTm = tm
        val corr = ppgCorrector ?: RlsCorrector(64.0, hostTime).also { ppgCorrector = it }
        corr.advanceTo(legacyPpgSampleIndex)
        corr.timestamps(n, hostTime, ts)
        legacyPpgSampleIndex += n
        listener.onPpg(SampleBatch(ts, legacyPpg.map { it.copyOf() }.toTypedArray()))
    }

    // ---- 控制通道 ----

    private fun onControl(data: ByteArray) {
        val msg = controlAssembler.feed(data) ?: return
        Log.d(TAG, "control: $msg")
        listener.onControlMessage(msg)
        // 尝试提取设备信息（JSON 或键值对）
        parseDeviceInfo(msg)
    }

    private fun parseDeviceInfo(msg: String) {
        try {
            val json = org.json.JSONObject(msg)
            fun opt(k: String) = if (json.has(k) && !json.isNull(k)) json.get(k).toString() else null
            deviceInfo = DeviceInfo(
                name = opt("hn") ?: deviceInfo.name,
                serial = opt("sn") ?: deviceInfo.serial,
                firmware = opt("fw") ?: deviceInfo.firmware,
                hardware = opt("hw") ?: deviceInfo.hardware,
                preset = opt("ps") ?: deviceInfo.preset,
            )
            json.optInt("bp", -1).takeIf { it >= 0 }?.let { listener.onBattery(it) }
        } catch (_: Exception) {
            // 非 JSON 回复，忽略
        }
    }

    private fun resetStreamState() {
        eegCorrector = null; accCorrector = null; gyroCorrector = null
        ppgCorrector = null; opticsCorrector = null
        legacyEegLastTm = -1; legacyEegSampleIndex = 0
        legacyPpgLastTm = -1; legacyPpgSampleIndex = 0
        for (i in legacyEegArrival.indices) legacyEegArrival[i] = Double.MAX_VALUE
    }

    /** 按设备名重新扫描以刷新地址（应对随机地址变化） */
    private suspend fun refreshAddress(name: String): String? {
        val a = adapter ?: return null
        if (!a.isEnabled) return null
        val scanner = a.bluetoothLeScanner ?: return null
        val found = CompletableDeferred<String?>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val n = result.scanRecord?.deviceName ?: result.device.name
                if (n != null && n == name) found.complete(result.device.address)
            }
        }
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), cb)
        } catch (_: Exception) {
            return null
        }
        val addr = withTimeoutOrNull(8000L) { found.await() }
        try { scanner.stopScan(cb) } catch (_: Exception) {}
        return addr
    }
}

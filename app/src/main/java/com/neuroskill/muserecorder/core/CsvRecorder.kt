package com.neuroskill.muserecorder.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * CSV 记录器：所有数据事件进入单一无界通道，由专职写线程按序落盘。
 * 输出格式与 Mind Monitor 兼容：
 *   TimeStamp,RAW_TP9,RAW_AF7,RAW_AF8,RAW_TP10,[AUX,][Accelerometer_X/Y/Z,][Gyro_X/Y/Z,]
 *   [PPG1..3 | Optics_0..15,]Battery,HSI_TP9,HSI_AF7,HSI_AF8,HSI_TP10,Elements
 * 每行一个 EEG 样本（256 Hz），IMU/光学列为最近值；标注行为独立行。
 */
class CsvRecorder(private val context: Context) {

    companion object {
        private const val TAG = "CsvRecorder"
        private const val DIR_NAME = "MuseRecorder"
    }

    sealed class Event {
        data class Eeg(val ts: DoubleArray, val data: Array<FloatArray>) : Event()
        data class Acc(val data: FloatArray) : Event()      // [axis] 最近一个样本
        data class Gyro(val data: FloatArray) : Event()
        data class Ppg(val data: FloatArray) : Event()      // [3] 最近一个样本
        data class Optics(val data: FloatArray) : Event()   // [16] 最近一个样本
        data class Marker(val text: String, val tsMs: Double) : Event()
    }

    /** 列配置（录制开始时确定） */
    data class Columns(
        val aux: Boolean,
        val accel: Boolean,
        val gyro: Boolean,
        val optics: Boolean,   // Athena 16 通道
        val ppg: Boolean,      // legacy 3 通道
    )

    data class RecordingInfo(
        val fileName: String,
        val startedAtMs: Long,
        val rows: Long,
        val bytes: Long,
    )

    val state = MutableStateFlow<RecordingInfo?>(null)
    @Volatile var isRecording = false
        private set

    // 供行内锁存的最新值（写线程读取）
    @Volatile private var batteryPct = -1
    @Volatile private var hsiLevels: IntArray? = null

    private var channel: Channel<Event>? = null
    private var writerJob: Job? = null
    private val writerDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "csv-writer").apply { priority = Thread.NORM_PRIORITY + 1 }
    }.asCoroutineDispatcher()

    private var sink: BufferedWriter? = null
    private var outputStream: OutputStream? = null
    private var fileUri: android.net.Uri? = null
    private var filePath: File? = null
    private var rows = 0L
    private var bytes = 0L

    fun setBattery(pct: Int) { batteryPct = pct }
    fun setHsi(levels: IntArray?) { hsiLevels = levels }

    /** 开始录制；返回文件名，失败返回 null */
    fun start(scope: CoroutineScope, columns: Columns, prefix: String): String? {
        if (isRecording) return state.value?.fileName
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH.mm.ss", Locale.US).format(Date())
        val fileName = "${prefix.ifBlank { "MuseRecord" }}_$stamp.csv"

        val opened: Boolean
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            fileUri = uri
            outputStream = uri?.let { resolver.openOutputStream(it, "wt") }
            opened = outputStream != null
        } else {
            val dir = File(context.getExternalFilesDir(null), DIR_NAME).apply { mkdirs() }
            val f = File(dir, fileName)
            filePath = f
            outputStream = FileOutputStream(f)
            opened = true
        }
        if (!opened) {
            Log.e(TAG, "无法创建录制文件")
            cleanupSink()
            return null
        }

        rows = 0; bytes = 0
        sink = BufferedWriter(OutputStreamWriter(outputStream!!, Charsets.UTF_8), 1 shl 16)
        sink!!.write(headerLine(columns))
        sink!!.write("\n")

        val ch = Channel<Event>(Channel.UNLIMITED)
        channel = ch
        isRecording = true
        state.value = RecordingInfo(fileName, System.currentTimeMillis(), 0, 0)

        writerJob = scope.launch(writerDispatcher) {
            writeLoop(ch, columns)
        }
        offer(Event.Marker("RecordingStart", nowMs()))
        return fileName
    }

    /** 结束录制（挂起直至写线程收尾） */
    suspend fun stop() {
        if (!isRecording) return
        offer(Event.Marker("RecordingStop", nowMs()))
        channel?.close()
        val job = writerJob
        if (job != null) {
            try { withTimeout(8000) { job.join() } } catch (_: Exception) { job.cancel() }
        }
        isRecording = false
        state.value = null
    }

    fun offer(event: Event) {
        channel?.trySend(event)
    }

    fun addMarker(text: String) {
        offer(Event.Marker(text, nowMs()))
    }

    private fun nowMs(): Double = System.currentTimeMillis().toDouble()

    // ---------------- 写线程 ----------------

    private suspend fun writeLoop(ch: Channel<Event>, columns: Columns) {
        val sb = StringBuilder(256)
        val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        var accLatest: FloatArray? = null
        var gyroLatest: FloatArray? = null
        var ppgLatest: FloatArray? = null
        var opticsLatest: FloatArray? = null
        var lastFlush = System.nanoTime()

        try {
            for (event in ch) {
                when (event) {
                    is Event.Eeg -> {
                        val n = event.ts.size
                        val nCh = minOf(event.data.size, if (columns.aux) 5 else 4)
                        val hsi = hsiLevels
                        for (i in 0 until n) {
                            sb.setLength(0)
                            sb.append(tsFormat.format(Date((event.ts[i] * 1000.0).toLong())))
                            for (c in 0 until nCh) {
                                sb.append(',')
                                appendFloat(sb, event.data[c][i], 2)
                            }
                            if (columns.accel) {
                                val a = accLatest
                                for (k in 0..2) { sb.append(','); appendFloat(sb, a?.get(k) ?: Float.NaN, 5) }
                            }
                            if (columns.gyro) {
                                val g = gyroLatest
                                for (k in 0..2) { sb.append(','); appendFloat(sb, g?.get(k) ?: Float.NaN, 3) }
                            }
                            if (columns.ppg) {
                                val p = ppgLatest
                                for (k in 0..2) { sb.append(','); appendInt(sb, p?.get(k)) }
                            }
                            if (columns.optics) {
                                val o = opticsLatest
                                for (k in 0 until 16) { sb.append(','); appendInt(sb, o?.get(k)) }
                            }
                            sb.append(',')
                            if (batteryPct >= 0) sb.append(batteryPct)
                            for (k in 0 until 4) {
                                sb.append(',')
                                val lv = hsi?.get(k) ?: -1
                                if (lv > 0) sb.append(lv)
                            }
                            sb.append(',')
                            sb.append('\n')
                            sink!!.write(sb.toString())
                            rows++
                        }
                    }
                    is Event.Acc -> accLatest = latestPerAxis(event.data)
                    is Event.Gyro -> gyroLatest = latestPerAxis(event.data)
                    is Event.Ppg -> ppgLatest = latestPerAxis(event.data)
                    is Event.Optics -> opticsLatest = latestPerAxis(event.data)
                    is Event.Marker -> {
                        sb.setLength(0)
                        sb.append(tsFormat.format(Date(event.tsMs.toLong())))
                        val colCount = columnCount(columns)
                        repeat(colCount) { sb.append(',') }
                        sb.append(',')
                        appendEscaped(sb, event.text)
                        sb.append('\n')
                        sink!!.write(sb.toString())
                        rows++
                    }
                }
                if (System.nanoTime() - lastFlush > 2_000_000_000L) {
                    sink?.flush()
                    lastFlush = System.nanoTime()
                    updateInfo()
                }
            }
            sink?.flush()
            updateInfo()
        } catch (e: Exception) {
            Log.e(TAG, "write error", e)
        } finally {
            try { sink?.flush() } catch (_: Exception) {}
            try { sink?.close() } catch (_: Exception) {}
            sink = null
            cleanupSink()
        }
    }

    private fun latestPerAxis(data: FloatArray): FloatArray {
        // data 为 [ch][1] 的最近样本：取每通道最后一个值
        return data
    }

    private fun updateInfo() {
        val cur = state.value ?: return
        bytes = try {
            if (Build.VERSION.SDK_INT >= 29 && fileUri != null) {
                context.contentResolver.query(
                    fileUri!!, arrayOf(MediaStore.Downloads.SIZE), null, null, null,
                )?.use { c -> if (c.moveToFirst()) c.getLong(0) else cur.bytes } ?: cur.bytes
            } else filePath?.length() ?: cur.bytes
        } catch (_: Exception) { cur.bytes }
        state.value = cur.copy(rows = rows, bytes = bytes)
    }

    private fun cleanupSink() {
        try { outputStream?.close() } catch (_: Exception) {}
        outputStream = null
    }

    // ---------------- 行构造 ----------------

    private fun columnCount(c: Columns): Int {
        var n = if (c.aux) 5 else 4
        if (c.accel) n += 3
        if (c.gyro) n += 3
        if (c.ppg) n += 3
        if (c.optics) n += 16
        n += 1 // Battery
        n += 4 // HSI
        return n
    }

    private fun headerLine(c: Columns): String {
        val sb = StringBuilder("TimeStamp")
        for (ch in listOf("TP9", "AF7", "AF8", "TP10")) sb.append(",RAW_").append(ch)
        if (c.aux) sb.append(",RAW_AUX")
        if (c.accel) sb.append(",Accelerometer_X,Accelerometer_Y,Accelerometer_Z")
        if (c.gyro) sb.append(",Gyro_X,Gyro_Y,Gyro_Z")
        if (c.ppg) sb.append(",PPG1,PPG2,PPG3")
        if (c.optics) for (i in 0 until 16) sb.append(",Optics_").append(i)
        sb.append(",Battery,HSI_TP9,HSI_AF7,HSI_AF8,HSI_TP10,Elements")
        return sb.toString()
    }

    private fun appendFloat(sb: StringBuilder, v: Float, decimals: Int) {
        if (v.isNaN()) return
        sb.append(String.format(Locale.US, "%.${decimals}f", v))
    }

    private fun appendInt(sb: StringBuilder, v: Float?) {
        if (v == null || v.isNaN()) return
        sb.append(v.toInt())
    }

    private fun appendEscaped(sb: StringBuilder, text: String) {
        if (text.contains(',') || text.contains('"') || text.contains('\n')) {
            sb.append('"').append(text.replace("\"", "\"\"")).append('"')
        } else {
            sb.append(text)
        }
    }
}

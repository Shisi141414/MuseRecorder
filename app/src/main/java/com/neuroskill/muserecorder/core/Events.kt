package com.neuroskill.muserecorder.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一个已完成的事件区间（时间与脑电 CSV 同源毫秒时钟） */
data class EventRecord(
    val name: String,
    val startMs: Long,
    val endMs: Long,
    val note: String,
)

/**
 * 当前事件跟踪：选择预设事件 → 记录中开启区间（换选自动结束上一个）。
 * 纯状态机（时间可注入，可单元测试）；落盘由 [EventFileStore] 负责。
 * 主 CSV 不写任何事件标记，事件仅存 <记录名>_events.csv。
 */
class EventTracker(
    private val clock: () -> Long,
    private val onClosed: (EventRecord) -> Unit,
) {
    /** 进行中的事件：name to startMs */
    val active = MutableStateFlow<Pair<String, Long>?>(null)
    val activeNote = MutableStateFlow("")
    val selectedName = MutableStateFlow<String?>(null)

    /** 当前记录会话内已完成的事件（不含进行中） */
    val sessionEvents = MutableStateFlow<List<EventRecord>>(emptyList())

    private var openStart = 0L
    private var sessionActive = false

    /** 选择事件（null = 取消选择）；更换即自动结束上一个；未在记录中仅记住选择 */
    fun select(name: String?) {
        selectedName.value = name
        closeCurrent()
        if (name != null && sessionActive) open(name)
    }

    /** 记录开始：清空会话事件；若有已选事件则自动开启区间 */
    fun onRecordingStarted() {
        sessionActive = true
        sessionEvents.value = emptyList()
        selectedName.value?.let { name ->
            if (active.value == null) open(name)
        }
    }

    /** 记录结束：关闭未完结事件 */
    fun onRecordingStopped() {
        sessionActive = false
        closeCurrent()
    }

    /** 手动结束当前事件（卡片「结束」按钮） */
    fun closeCurrent() {
        val cur = active.value ?: return
        val rec = EventRecord(cur.first, cur.second, clock(), activeNote.value)
        sessionEvents.value = sessionEvents.value + rec
        active.value = null
        activeNote.value = ""
        onClosed(rec)
    }

    /** 修改当前事件备注（仅进行中有效） */
    fun setNote(text: String) {
        if (active.value != null) activeNote.value = text
    }

    private fun open(name: String) {
        openStart = clock()
        active.value = name to openStart
        activeNote.value = ""
    }
}

/**
 * 事件旁路文件 <记录名去.csv>_events.csv：
 *   Event,Start,End,Duration,Note   （Start/End 与主 CSV TimeStamp 同格式毫秒）
 */
class EventFileStore(private val context: Context) {

    companion object {
        private const val TAG = "EventFileStore"
        const val SUFFIX = "_events.csv"
        val TIME_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS"
    }

    private var sink: BufferedWriter? = null
    private var outputStream: OutputStream? = null
    private var fileUri: android.net.Uri? = null
    private var filePath: File? = null
    var fileName: String? = null
        private set

    /** 打开（写表头）；失败返回 false */
    fun open(baseCsvName: String): Boolean {
        close()
        val name = baseCsvName.removeSuffix(".csv") + SUFFIX
        fileName = name
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MuseRecorder")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                fileUri = uri
                outputStream = context.contentResolver.openOutputStream(uri, "wt")
            } else {
                val dir = File(context.getExternalFilesDir(null), "MuseRecorder").apply { mkdirs() }
                val f = File(dir, name)
                filePath = f
                outputStream = FileOutputStream(f)
            }
            val os = outputStream ?: return false
            sink = BufferedWriter(OutputStreamWriter(os, Charsets.UTF_8))
            sink!!.write("Event,Start,End,Duration,Note\n")
            sink!!.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "open failed", e)
            close()
            false
        }
    }

    /** 追加一行事件（在 IO 线程调用） */
    fun write(rec: EventRecord) {
        try {
            val fmt = SimpleDateFormat(TIME_FORMAT, Locale.US)
            val dur = String.format(Locale.US, "%.1f", (rec.endMs - rec.startMs) / 1000.0)
            sink?.let {
                it.write(csv(rec.name) + "," + csv(fmt.format(Date(rec.startMs))) + "," +
                    csv(fmt.format(Date(rec.endMs))) + "," + csv(dur) + "," + csv(rec.note) + "\n")
                it.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "write failed", e)
        }
    }

    fun close() {
        try { sink?.flush() } catch (_: Exception) {}
        try { sink?.close() } catch (_: Exception) {}
        sink = null
        try { outputStream?.close() } catch (_: Exception) {}
        outputStream = null
        fileUri = null
        filePath = null
    }

    private fun csv(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
}

/** 简单的带引号 CSV 行解析（事件表读取用） */
fun parseCsvLine(line: String): List<String> {
    val out = ArrayList<String>()
    val sb = StringBuilder()
    var inQuote = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            inQuote && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                sb.append('"'); i++
            }
            c == '"' -> inQuote = !inQuote
            c == ',' && !inQuote -> {
                out.add(sb.toString()); sb.setLength(0)
            }
            else -> sb.append(c)
        }
        i++
    }
    out.add(sb.toString())
    return out
}

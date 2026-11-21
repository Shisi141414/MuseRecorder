package com.neuroskill.muserecorder.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志自动落盘：未捕获异常 / 协程异常写入
 * Download/MuseRecorder/crash-<时间>.txt（旧系统写入应用外部目录），
 * 便于现场排障（用户可直接用文件管理器取走发给开发者）。
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val DIR_NAME = "MuseRecorder"
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeReport("crash", thread.name, throwable)
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "crash reporter installed")
    }

    /** 非致命协程异常记录（不中断流程） */
    fun logCoroutineError(throwable: Throwable) {
        Log.e(TAG, "coroutine error", throwable)
        try {
            writeReport("coroutine-error", "coroutine", throwable)
        } catch (_: Exception) {
        }
    }

    /** 最近一次崩溃文件名（无则 null），用于启动时提示 */
    fun lastCrashName(context: Context): String? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.RELATIVE_PATH),
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                arrayOf("%/MuseRecorder/%", "crash-%"),
                "${MediaStore.Downloads.DATE_MODIFIED} DESC",
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } else {
            val dir = File(context.getExternalFilesDir(null), DIR_NAME)
            dir.listFiles()?.filter { it.name.startsWith("crash-") }?.maxByOrNull { it.lastModified() }?.name
        }
    } catch (_: Exception) {
        null
    }

    private fun writeReport(prefix: String, threadName: String, throwable: Throwable) {
        val ctx = appContext ?: return
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "$prefix-$stamp.txt"
        val body = buildString {
            appendLine("app: MuseRecorder")
            appendLine("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
            appendLine("thread: $threadName")
            appendLine()
            appendLine(Log.getStackTraceString(throwable))
        }
        // 1) 先写应用外部目录（必定可写、留底）
        try {
            val dir = File(ctx.getExternalFilesDir(null), DIR_NAME).apply { mkdirs() }
            File(dir, name).writeText(body)
        } catch (_: Exception) {
        }
        // 2) 再复制到 Download/MuseRecorder（用户可直接取走），API 29+
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME)
                }
                ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(body.toByteArray()) }
                }
            } catch (_: Exception) {
            }
        }
    }
}

package com.neuroskill.muserecorder.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** 录制文件的列举 / 分享 / 删除 */
object FileStore {

    data class RecFile(
        val uri: Uri,
        val name: String,
        val size: Long,
        val modifiedMs: Long,
    )

    fun list(context: Context): List<RecFile> = query(context, includeEvents = false)

    /** 事件表文件（*_events.csv） */
    fun listEvents(context: Context): List<RecFile> = query(context, includeEvents = true)

    fun findByName(context: Context, name: String): RecFile? =
        (list(context) + listEvents(context)).firstOrNull { it.name == name }

    private fun query(context: Context, includeEvents: Boolean): List<RecFile> {
        val out = ArrayList<RecFile>()
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.SIZE, MediaStore.Downloads.DATE_MODIFIED,
                    MediaStore.Downloads.RELATIVE_PATH),
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf("%/MuseRecorder/%"),
                "${MediaStore.Downloads.DATE_MODIFIED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    if (!name.endsWith(".csv")) continue
                    val isEvent = name.endsWith(EventFileStore.SUFFIX)
                    if (isEvent != includeEvents) continue
                    out.add(
                        RecFile(
                            uri = Uri.withAppendedPath(collection, id.toString()),
                            name = name,
                            size = c.getLong(2),
                            modifiedMs = c.getLong(3) * 1000L,
                        )
                    )
                }
            }
        } else {
            val dir = File(context.getExternalFilesDir(null), "MuseRecorder")
            val files = dir.listFiles() ?: return emptyList()
            for (f in files.sortedByDescending { it.lastModified() }) {
                if (!f.name.endsWith(".csv")) continue
                val isEvent = f.name.endsWith(EventFileStore.SUFFIX)
                if (isEvent != includeEvents) continue
                out.add(RecFile(Uri.fromFile(f), f.name, f.length(), f.lastModified()))
            }
        }
        return out
    }

    /** 读取事件表内容（IO 线程调用） */
    fun readText(context: Context, file: RecFile): String? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.openInputStream(file.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } else {
            File(file.uri.path!!).readText()
        }
    } catch (_: Exception) {
        null
    }

    /** 重写文件内容（单事件删除后回写；IO 线程调用） */
    fun writeText(context: Context, file: RecFile, text: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(text.toByteArray()) }
            true
        } else {
            File(file.uri.path!!).writeText(text)
            true
        }
    } catch (_: Exception) {
        false
    }

    fun shareIntent(context: Context, file: RecFile): Intent {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            if (Build.VERSION.SDK_INT >= 29) {
                putExtra(Intent.EXTRA_STREAM, file.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                val uri = FileProvider.getUriForFile(
                    context, context.packageName + ".fileprovider", File(file.uri.path!!),
                )
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        return Intent.createChooser(sendIntent, file.name)
    }

    fun delete(context: Context, file: RecFile): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.delete(file.uri, null, null) > 0
        } else {
            File(file.uri.path!!).delete()
        }
    } catch (_: Exception) {
        false
    }
}

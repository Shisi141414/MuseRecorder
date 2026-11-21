package com.neuroskill.muserecorder.ui

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.neuroskill.muserecorder.R
import com.neuroskill.muserecorder.core.EventFileStore
import com.neuroskill.muserecorder.core.EventRecord
import com.neuroskill.muserecorder.core.FileStore
import com.neuroskill.muserecorder.core.MuseHub
import com.neuroskill.muserecorder.core.parseCsvLine
import com.neuroskill.muserecorder.databinding.FragmentEventListBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 事件记录页（v1.6.0）：按记录会话分组展示事件起止时间（与脑电 CSV 毫秒级同源）。
 * 主 CSV 不写事件标记，数据全部来自旁路 <记录名>_events.csv。
 */
class EventListDialog : DialogFragment() {

    private var _binding: FragmentEventListBinding? = null
    private val binding get() = _binding!!

    private data class Row(val name: String, val start: String, val end: String, val note: String, val ongoing: Boolean = false)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val d = super.onCreateDialog(savedInstanceState)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return d
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentEventListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnClose.setOnClickListener { dismiss() }
        binding.btnShareEvents.setOnClickListener { shareCurrent() }
        binding.btnDeleteEvents.setOnClickListener { deleteCurrent() }
        rebuild()

        lifecycleScope.launch {
            MuseHub.tracker.sessionEvents.collect { rebuild() }
        }
        lifecycleScope.launch {
            MuseHub.tracker.active.collect { rebuild() }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    private fun rebuild() {
        lifecycleScope.launch {
            val sessions = withContext(Dispatchers.IO) { loadSessions() }
            val ctx = context ?: return@launch
            val list = binding.eventList
            list.removeAllViews()

            if (sessions.isEmpty()) {
                val empty = TextView(ctx).apply {
                    text = getString(R.string.events_empty)
                    setPadding(24, 40, 24, 40)
                    gravity = Gravity.CENTER
                    setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                    setTextColor(0xFF8A90A4.toInt())
                }
                list.addView(empty)
                currentFileForButtons = null
                updateButtons()
                return@launch
            }

            currentFileForButtons = sessions.firstOrNull { it.currentFileName != null }?.let { s ->
                FileStore.findByName(ctx, s.currentFileName!!)
            } ?: sessions.firstOrNull()?.let { s -> FileStore.findByName(ctx, s.fileName) }
            updateButtons()

            for (s in sessions) {
                val header = TextView(ctx).apply {
                    text = if (s.recording) getString(R.string.events_session_recording_fmt, s.fileName)
                    else s.fileName.removeSuffix(EventFileStore.SUFFIX).removeSuffix(".csv").substringBefore("_events")
                        .let { s.fileName } // 显示完整文件名
                    setPadding(8, 20, 8, 6)
                    textSize = 11f
                    setTextColor(0xFF8A90A4.toInt())
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
                list.addView(header)
                for (row in s.rows) list.addView(buildItem(row))
            }
        }
    }

    private var currentFileForButtons: FileStore.RecFile? = null

    private fun updateButtons() {
        val has = currentFileForButtons != null
        binding.btnShareEvents.isEnabled = has
        binding.btnDeleteEvents.isEnabled = has && !MuseHub.recorder.isRecording
        binding.btnDeleteEvents.alpha = if (binding.btnDeleteEvents.isEnabled) 1f else 0.4f
    }

    private fun buildItem(row: Row): View {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val item = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            background = entryItemBg()
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.bottomMargin = (7 * dp).toInt()
            layoutParams = params
        }
        val barColor = if (row.ongoing) 0xFF2FA84F.toInt() else 0xFF4355B9.toInt()
        item.addView(View(ctx).apply {
            setBackgroundColor(barColor)
            layoutParams = LinearLayout.LayoutParams((4 * dp).toInt(), (36 * dp).toInt()).apply {
                marginEnd = (10 * dp).toInt()
            }
        })
        val main = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        main.addView(TextView(ctx).apply {
            text = row.name
            textSize = 14f
            setTextColor(0xFF2C3350.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        main.addView(TextView(ctx).apply {
            text = formatTimes(row.start, row.end)
            textSize = 10.5f
            setTextColor(0xFF9AA0B5.toInt())
        })
        if (row.note.isNotBlank()) {
            main.addView(TextView(ctx).apply {
                text = getString(R.string.event_note_fmt, row.note)
                textSize = 10.5f
                setTextColor(0xFF4355B9.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (2 * dp).toInt() }
            })
        }
        item.addView(main)
        item.addView(TextView(ctx).apply {
            text = if (row.ongoing) getString(R.string.event_ongoing) else durationOf(row.start, row.end)
            textSize = 12f
            setTextColor(if (row.ongoing) 0xFF2FA84F.toInt() else 0xFF4355B9.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.END
        })
        return item
    }

    private fun entryItemBg(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            cornerRadius = 13 * resources.displayMetrics.density
            setStroke(
                (1.4 * resources.displayMetrics.density).toInt(),
                0xFFEEF0F7.toInt(),
            )
        }

    private fun formatTimes(start: String, end: String): String {
        // 完整时间戳 yyyy-MM-dd HH:mm:ss.SSS → 只展示时间部分 + 日期组头已有
        val s = start.substringAfter(' ')
        val e = if (end.isEmpty()) getString(R.string.event_ongoing) else end.substringAfter(' ')
        return "$s → $e"
    }

    private fun durationOf(start: String, end: String): String {
        return try {
            val fmt = java.text.SimpleDateFormat(EventFileStore.TIME_FORMAT, java.util.Locale.US)
            val dur = (fmt.parse(end).time - fmt.parse(start).time) / 1000.0
            String.format(java.util.Locale.US, "%.1fs", dur)
        } catch (_: Exception) {
            ""
        }
    }

    private data class Session(
        val fileName: String,
        val rows: List<Row>,
        val recording: Boolean,
        val currentFileName: String? = null,
    )

    private fun loadSessions(): List<Session> {
        val out = ArrayList<Session>()
        // 当前会话（优先）
        val tracker = MuseHub.tracker
        val curFileName = MuseHub.eventFileStore.fileName
        val curRows = ArrayList<Row>()
        for (rec in tracker.sessionEvents.value) {
            curRows.add(rowOf(rec))
        }
        tracker.active.value?.let { (name, startMs) ->
            val fmt = java.text.SimpleDateFormat(EventFileStore.TIME_FORMAT, java.util.Locale.US)
            curRows.add(Row(name, fmt.format(java.util.Date(startMs)), "", tracker.activeNote.value, ongoing = true))
        }
        if (curRows.isNotEmpty() || (curFileName != null && MuseHub.recorder.isRecording)) {
            val known = curFileName ?: "current"
            out.add(Session(known, curRows, recording = MuseHub.recorder.isRecording, currentFileName = curFileName))
        }
        // 历史事件表
        for (f in FileStore.listEvents(requireContext())) {
            if (curFileName != null && f.name == curFileName) continue
            val text = FileStore.readText(requireContext(), f) ?: continue
            val rows = text.lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("Event,") }
                .map { line ->
                    val cols = parseCsvLine(line)
                    Row(
                        name = cols.getOrElse(0) { "" },
                        start = cols.getOrElse(1) { "" },
                        end = cols.getOrElse(2) { "" },
                        note = cols.getOrElse(4) { "" },
                    )
                }.toList()
            if (rows.isNotEmpty()) out.add(Session(f.name, rows, recording = false))
        }
        return out
    }

    private fun rowOf(rec: EventRecord): Row {
        val fmt = java.text.SimpleDateFormat(EventFileStore.TIME_FORMAT, java.util.Locale.US)
        return Row(rec.name, fmt.format(java.util.Date(rec.startMs)), fmt.format(java.util.Date(rec.endMs)), rec.note)
    }

    private fun shareCurrent() {
        val f = currentFileForButtons ?: return
        startActivity(FileStore.shareIntent(requireContext(), f))
    }

    private fun deleteCurrent() {
        val f = currentFileForButtons ?: return
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.events_delete_confirm, f.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { FileStore.delete(requireContext(), f) }
                    rebuild()
                    (parentFragment as? RecordFragment)?.refreshFiles()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

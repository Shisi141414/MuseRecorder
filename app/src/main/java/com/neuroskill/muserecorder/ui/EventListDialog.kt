package com.neuroskill.muserecorder.ui

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 事件记录页：按事件表文件（<脑电记录名>_events.csv 或独立 Events_*_events.csv）分组，
 * 条目含事件名、起止时间（与脑电 CSV 毫秒级同源）、备注与时长。
 * 点击行展开「分享/删除」单事件操作；底部按会话分享/删除整表。
 */
class EventListDialog : DialogFragment() {

    private var _binding: FragmentEventListBinding? = null
    private val binding get() = _binding!!

    private data class Row(
        val name: String,
        val start: String,
        val end: String,
        val note: String,
        val ongoing: Boolean = false,
        val rec: EventRecord? = null,     // 当前会话内存事件（用于删除）
        val line: String? = null,         // 文件原始行（用于删除/去重）
    )

    private data class Session(
        val fileName: String,
        val rows: List<Row>,
        val recording: Boolean,
    )

    private var currentFileForButtons: FileStore.RecFile? = null
    private var expandedKey: String? = null

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
        lifecycleScope.launch { MuseHub.tracker.sessionEvents.collect { rebuild() } }
        lifecycleScope.launch { MuseHub.tracker.active.collect { rebuild() } }
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
                    gravity = android.view.Gravity.CENTER
                    setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                    setTextColor(0xFF8A90A4.toInt())
                }
                list.addView(empty)
                currentFileForButtons = null
                updateButtons()
                return@launch
            }

            currentFileForButtons = FileStore.findByName(ctx, sessions.first().fileName)
            updateButtons()

            val fmtIn = SimpleDateFormat(EventFileStore.TIME_FORMAT, Locale.US)
            val fmtOut = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

            for ((sIndex, s) in sessions.withIndex()) {
                val group = layoutInflater.inflate(R.layout.item_event_group, list, false)
                group.findViewById<TextView>(R.id.groupName).text = displayName(s.fileName)
                group.findViewById<TextView>(R.id.groupStatus).apply {
                    text = if (s.recording) getString(R.string.event_group_recording) else ""
                }
                list.addView(group)
                for (row in s.rows) {
                    val item = layoutInflater.inflate(R.layout.item_event_record, list, false)
                    val barColors = intArrayOf(
                        0xFF4355B9.toInt(), 0xFFE8A23A.toInt(),
                        0xFF2FA84F.toInt(), 0xFF9B59D6.toInt(),
                    )
                    item.findViewById<View>(R.id.evBar).setBackgroundColor(
                        if (row.ongoing) 0xFF2FA84F.toInt() else barColors[sIndex % barColors.size],
                    )
                    item.findViewById<TextView>(R.id.evName).text = row.name
                    val startT = try { fmtOut.format(fmtIn.parse(row.start) ?: Date()) } catch (_: Exception) { row.start }
                    val endT = if (row.ongoing) getString(R.string.event_ongoing)
                    else try { fmtOut.format(fmtIn.parse(row.end) ?: Date()) } catch (_: Exception) { row.end }
                    item.findViewById<TextView>(R.id.evTimes).text = "$startT → $endT"
                    item.findViewById<TextView>(R.id.evNote).apply {
                        if (row.note.isNotBlank()) {
                            text = getString(R.string.event_note_fmt, row.note)
                            visibility = View.VISIBLE
                        }
                    }
                    item.findViewById<TextView>(R.id.evDuration).text =
                        if (row.ongoing) getString(R.string.event_ongoing) else durationOf(row.start, row.end)
                    item.findViewById<TextView>(R.id.evDurSub).text = ""

                    // 点击行：展开/收起单事件操作（进行中不可操作）
                    val key = "${s.fileName}|${row.name}|${row.start}|${row.end}|${row.note}"
                    val actions = item.findViewById<View>(R.id.evActions)
                    if (expandedKey == key && !row.ongoing) actions.visibility = View.VISIBLE
                    item.setOnClickListener {
                        expandedKey = if (expandedKey == key) null else key
                        rebuild()
                    }
                    if (!row.ongoing) {
                        item.findViewById<View>(R.id.btnEvShare).setOnClickListener { shareRow(row) }
                        item.findViewById<View>(R.id.btnEvDelete).setOnClickListener { confirmDeleteRow(s, row) }
                    }
                    list.addView(item)
                }
            }
        }
    }

    private fun displayName(fileName: String): String =
        fileName.removeSuffix(".csv").removeSuffix(EventFileStore.SUFFIX)

    private fun updateButtons() {
        val has = currentFileForButtons != null
        binding.btnShareEvents.isEnabled = has
        binding.btnDeleteEvents.isEnabled = has && !MuseHub.recorder.isRecording
        binding.btnDeleteEvents.alpha = if (binding.btnDeleteEvents.isEnabled) 1f else 0.4f
    }

    private fun durationOf(start: String, end: String): String = try {
        val fmt = SimpleDateFormat(EventFileStore.TIME_FORMAT, Locale.US)
        val dur = (fmt.parse(end).time - fmt.parse(start).time) / 1000.0
        String.format(Locale.US, "%.1fs", dur)
    } catch (_: Exception) {
        ""
    }

    /** 分享单次事件（文本） */
    private fun shareRow(row: Row) {
        val dur = durationOf(row.start, row.end).removeSuffix("s")
        val text = buildString {
            appendLine(row.name)
            appendLine("${row.start} → ${row.end}")
            appendLine("duration: $dur s")
            if (row.note.isNotBlank()) appendLine("note: ${row.note}")
        }
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text.trim())
                },
                row.name,
            ),
        )
    }

    /** 删除单次事件：从所属事件表移除该行；当前会话同步移除内存记录 */
    private fun confirmDeleteRow(s: Session, row: Row) {
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.event_delete_confirm, row.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val f = FileStore.findByName(requireContext(), s.fileName)
                        if (f != null) {
                            val text = FileStore.readText(requireContext(), f)
                            if (text != null) {
                                val kept = text.lineSequence()
                                    .filter { it.isBlank() || it == row.line || !sameLine(it, row) }
                                    .toList()
                                FileStore.writeText(requireContext(), f, kept.joinToString("\n") + "\n")
                            }
                        }
                        row.rec?.let { MuseHub.tracker.removeEvent(it) }
                    }
                    rebuild()
                    (parentFragment as? RecordFragment)?.refreshFiles()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 行匹配：四元组（名称/起止/备注）一致即同一事件 */
    private fun sameLine(line: String, row: Row): Boolean {
        if (line.startsWith("Event,")) return false
        val c = parseCsvLine(line)
        return c.getOrElse(0) { "" } == row.name &&
            c.getOrElse(1) { "" } == row.start &&
            c.getOrElse(2) { "" } == row.end &&
            c.getOrElse(4) { "" } == row.note
    }

    private fun loadSessions(): List<Session> {
        val out = LinkedHashMap<String, Session>()
        val openName = MuseHub.eventFileStore.fileName
        // 历史事件表（新→旧）；当前打开的表跳过（用内存数据，避免重复）
        for (f in FileStore.listEvents(requireContext())) {
            if (openName != null && f.name == openName) continue
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
                        line = line,
                    )
                }.toList()
            if (rows.isNotEmpty()) out[f.name] = Session(f.name, rows, recording = false)
        }
        // 当前组：以内存事件为准（去重）+ 进行中
        if (openName != null) {
            val curRows = ArrayList<Row>()
            for (rec in MuseHub.tracker.sessionEvents.value) {
                curRows.add(rowOf(rec))
            }
            MuseHub.tracker.active.value?.let { (name, startMs) ->
                val fmt = SimpleDateFormat(EventFileStore.TIME_FORMAT, Locale.US)
                curRows.add(
                    Row(name, fmt.format(Date(startMs)), "", MuseHub.tracker.activeNote.value, ongoing = true),
                )
            }
            if (curRows.isNotEmpty()) {
                out[openName] = Session(
                    openName, curRows,
                    recording = MuseHub.recorder.isRecording &&
                        openName == MuseHub.recorder.state.value?.fileName,
                )
            }
        }
        // 当前组置顶
        return if (openName != null && out.containsKey(openName)) {
            listOf(out.remove(openName)!!) + out.values.toList()
        } else {
            out.values.toList()
        }
    }

    private fun rowOf(rec: EventRecord): Row {
        val fmt = SimpleDateFormat(EventFileStore.TIME_FORMAT, Locale.US)
        return Row(
            rec.name,
            fmt.format(Date(rec.startMs)),
            fmt.format(Date(rec.endMs)),
            rec.note,
            rec = rec,
        )
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

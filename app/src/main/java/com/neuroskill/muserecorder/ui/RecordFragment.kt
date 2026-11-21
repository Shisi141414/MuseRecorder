package com.neuroskill.muserecorder.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.neuroskill.muserecorder.R
import com.neuroskill.muserecorder.ble.ConnStatus
import com.neuroskill.muserecorder.core.FileStore
import com.neuroskill.muserecorder.core.MuseHub
import com.neuroskill.muserecorder.databinding.FragmentRecordBinding
import com.neuroskill.muserecorder.databinding.ItemFileBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale

class RecordFragment : Fragment() {

    private var _binding: FragmentRecordBinding? = null
    private val binding get() = _binding!!

    private var allFiles: List<FileStore.RecFile> = emptyList()

    private val fileAdapter = FileAdapter(
        onShare = { startActivity(FileStore.shareIntent(requireContext(), it)) },
        onDelete = { confirmDelete(it) },
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentRecordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.fileList.layoutManager = LinearLayoutManager(requireContext())
        binding.fileList.adapter = fileAdapter

        binding.btnRecord.setOnClickListener { toggleRecording() }
        binding.btnMore.setOnClickListener {
            FileBrowserDialog().show(childFragmentManager, "fileBrowser")
        }
        binding.fileEntry.setOnClickListener {
            FileBrowserDialog().show(childFragmentManager, "fileBrowser")
        }
        binding.eventEntry.setOnClickListener {
            EventListDialog().show(childFragmentManager, "eventList")
        }
        binding.btnCustomMarker.setOnClickListener { showCustomMarkerDialog() }
        buildMarkerButtons()

        viewLifecycleOwner.lifecycleScope.launch {
            MuseHub.tracker.sessionEvents.collect { renderEntrySubs() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            MuseHub.tracker.active.collect { renderEntrySubs() }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                render()
                delay(500)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            MuseHub.conn.collect { render() }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshFiles()
    }

    internal fun refreshFiles() {
        viewLifecycleOwner.lifecycleScope.launch {
            allFiles = withContext(Dispatchers.IO) { FileStore.list(requireContext()) }
            val recent = allFiles.take(RECENT_LIMIT)
            fileAdapter.submitList(recent) {
                binding.filesEmptyText.visibility =
                    if (allFiles.isEmpty()) View.VISIBLE else View.GONE
            }
            binding.btnMore.visibility =
                if (allFiles.size > RECENT_LIMIT) View.VISIBLE else View.GONE
            binding.btnMore.text = getString(R.string.more_files_fmt, allFiles.size)
            renderEntrySubs()
        }
    }

    /** 双入口副标题计数 */
    private fun renderEntrySubs() {
        val n = allFiles.size
        binding.fileEntrySub.text =
            getString(R.string.file_entry_sub_fmt, minOf(n, RECENT_LIMIT), n)
        val cur = MuseHub.tracker.sessionEvents.value.size +
            (if (MuseHub.tracker.active.value != null) 1 else 0)
        viewLifecycleOwner.lifecycleScope.launch {
            val total = withContext(Dispatchers.IO) { countTotalEvents() }
            binding.eventEntrySub.text =
                getString(R.string.event_entry_sub_fmt, cur, total + cur)
        }
    }

    private fun countTotalEvents(): Int {
        var count = 0
        for (f in FileStore.listEvents(requireContext())) {
            val text = FileStore.readText(requireContext(), f) ?: continue
            count += text.lineSequence().count { it.isNotBlank() && !it.startsWith("Event,") }
        }
        return count
    }

    private fun toggleRecording() {
        if (MuseHub.recorder.isRecording) {
            viewLifecycleOwner.lifecycleScope.launch {
                MuseHub.stopRecording()
                MuseHub.maybeStopService()
                refreshFiles()
                render()
            }
        } else {
            val st = MuseHub.conn.value.status
            if (st != ConnStatus.STREAMING && st != ConnStatus.CONNECTED) {
                Toast.makeText(requireContext(), R.string.record_need_connection, Toast.LENGTH_LONG).show()
                return
            }
            if (MuseHub.startRecording()) Toast.makeText(
                requireContext(),
                MuseHub.recorder.state.value?.fileName,
                Toast.LENGTH_SHORT,
            ).show()
            render()
        }
    }

    private fun render() {
        val rec = MuseHub.recorder.state.value
        if (rec != null) {
            val dur = (System.currentTimeMillis() - rec.startedAtMs) / 1000
            val durStr = String.format(Locale.US, "%02d:%02d:%02d", dur / 3600, (dur % 3600) / 60, dur % 60)
            binding.recStatusText.text = getString(R.string.record_running_fmt, rec.fileName)
            binding.recStatsText.visibility = View.VISIBLE
            binding.recStatsText.text = getString(
                R.string.record_stats_fmt, rec.rows, formatBytes(rec.bytes),
            ) + " · " + durStr
            binding.btnRecord.setText(R.string.record_stop)
        } else {
            binding.recStatusText.setText(R.string.record_idle)
            binding.recStatsText.visibility = View.GONE
            binding.btnRecord.setText(R.string.record_start)
        }
    }

    private fun formatBytes(b: Long): String = when {
        b >= 1 shl 20 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
        b >= 1 shl 10 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
        else -> "$b B"
    }

    private fun confirmDelete(file: FileStore.RecFile) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.file_delete_confirm, file.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { FileStore.delete(requireContext(), file) }
                    refreshFiles()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---------------- 标注（9 键 + 自定义） ----------------

    private fun buildMarkerButtons() {
        val grid = binding.markerGrid
        grid.removeAllViews()
        for (i in 1..9) {
            val btn = com.google.android.material.button.MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = MuseHub.prefs.markerLabel(i)
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1, 1f)
                    rowSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1)
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    setMargins(4, 4, 4, 4)
                }
                setOnClickListener { insertMarker(MuseHub.prefs.markerLabel(i)) }
                setOnLongClickListener {
                    showLabelEditor(i)
                    true
                }
            }
            grid.addView(btn)
        }
    }

    private fun insertMarker(label: String) {
        if (!MuseHub.recorder.isRecording) {
            Toast.makeText(requireContext(), R.string.marker_need_recording, Toast.LENGTH_SHORT).show()
            return
        }
        MuseHub.addMarker(label)
        Toast.makeText(requireContext(), getString(R.string.marker_done, label), Toast.LENGTH_SHORT).show()
        vibrate()
    }

    private fun showLabelEditor(index: Int) {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply {
            text.insert(0, MuseHub.prefs.markerLabel(index))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.marker_label_title, index))
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val t = input.text.toString().trim().ifEmpty { index.toString() }
                MuseHub.prefs.setMarkerLabel(index, t)
                buildMarkerButtons()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCustomMarkerDialog() {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.custom_marker_title)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) insertMarker(t)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun vibrate() {
        try {
            val v = requireContext().getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createOneShot(40, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(40)
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val RECENT_LIMIT = 15
    }
}

/** 文件列表适配器（记录页与文件浏览器共用） */
class FileAdapter(
    private val onShare: (FileStore.RecFile) -> Unit,
    private val onDelete: (FileStore.RecFile) -> Unit,
) : ListAdapter<FileStore.RecFile, FileAdapter.VH>(object : DiffUtil.ItemCallback<FileStore.RecFile>() {
    override fun areItemsTheSame(a: FileStore.RecFile, b: FileStore.RecFile) = a.uri == b.uri
    override fun areContentsTheSame(a: FileStore.RecFile, b: FileStore.RecFile) =
        a.name == b.name && a.size == b.size
}) {

    class VH(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val f = getItem(position)
        holder.binding.fileName.text = f.name
        holder.binding.fileMeta.text = String.format(
            Locale.US, "%,d B · %tF %tR",
            f.size, Date(f.modifiedMs), Date(f.modifiedMs),
        )
        holder.binding.btnShareFile.setOnClickListener { onShare(f) }
        holder.binding.root.setOnLongClickListener {
            onDelete(f)
            true
        }
    }
}

package com.neuroskill.muserecorder.ui

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.neuroskill.muserecorder.R
import com.neuroskill.muserecorder.core.FileStore
import com.neuroskill.muserecorder.databinding.FragmentFileBrowserBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 全量数据文件浏览：搜索文件名 + 时间范围筛选（全屏对话框） */
class FileBrowserDialog : DialogFragment() {

    private var _binding: FragmentFileBrowserBinding? = null
    private val binding get() = _binding!!

    private var allFiles: List<FileStore.RecFile> = emptyList()
    private var query = ""
    private var filterDays = 0   // 0=全部

    private val adapter = FileAdapter(
        onShare = { startActivity(FileStore.shareIntent(requireContext(), it)) },
        onDelete = { f ->
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.file_delete_confirm, f.name))
                .setPositiveButton(R.string.delete) { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { FileStore.delete(requireContext(), f) }
                        reload()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        },
    )

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
        _binding = FragmentFileBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.fileList.layoutManager = LinearLayoutManager(requireContext())
        binding.fileList.adapter = adapter

        binding.btnClose.setOnClickListener { dismiss() }

        binding.searchEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim() ?: ""
                apply()
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        val chips = listOf(
            binding.chipAll to 0,
            binding.chipToday to 1,
            binding.chipWeek to 7,
            binding.chipMonth to 30,
        )
        for ((chip, days) in chips) {
            chip.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    filterDays = days
                    for ((c, _) in chips) {
                        if (c !== chip) c.isChecked = false
                    }
                    apply()
                }
            }
        }
        reload()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    private fun reload() {
        lifecycleScope.launch {
            allFiles = withContext(Dispatchers.IO) { FileStore.list(requireContext()) }
            apply()
        }
    }

    private fun apply() {
        val ctx = context ?: return
        val now = System.currentTimeMillis()
        val filtered = allFiles.filter { f ->
            val matchQuery = query.isEmpty() || f.name.contains(query, ignoreCase = true)
            val matchTime = filterDays == 0 || now - f.modifiedMs <= filterDays * 24L * 3600_000L
            matchQuery && matchTime
        }
        adapter.submitList(filtered)
        binding.countText.text = getString(R.string.file_count_fmt, filtered.size)

        // 筛选 chip 上的数量提示
        binding.chipAll.text = getString(R.string.filter_all) + " " + allFiles.size
        fun count(days: Int) = allFiles.count { now - it.modifiedMs <= days * 24L * 3600_000L }
        binding.chipToday.text = getString(R.string.filter_today) + " " + count(1)
        binding.chipWeek.text = getString(R.string.filter_week) + " " + count(7)
        binding.chipMonth.text = getString(R.string.filter_month) + " " + count(30)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        // 通知记录页刷新（可能有删除）
        (parentFragment as? RecordFragment)?.refreshFiles()
    }
}

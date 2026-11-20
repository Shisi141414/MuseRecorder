package com.neuroskill.muserecorder.ui

import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.neuroskill.muserecorder.MainActivity
import com.neuroskill.muserecorder.R
import com.neuroskill.muserecorder.ble.ConnState
import com.neuroskill.muserecorder.ble.ConnStatus
import com.neuroskill.muserecorder.core.MuseHub
import com.neuroskill.muserecorder.databinding.FragmentOverviewBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 总览页（设计稿 v2 + 电池 v3）：
 * 框1 状态·信号·电极质量·电池图标 → 连接/断开长按钮 + 录制方块键 →
 * 波形（右上角图例）→ 标注 1–4 → 底部说明。全部一屏无需滚动。
 */
class OverviewFragment : Fragment() {

    private var _binding: FragmentOverviewBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentOverviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                binding.waveView.refresh()
                delay(33)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch { MuseHub.conn.collect { renderStatus(it) } }
        viewLifecycleOwner.lifecycleScope.launch { MuseHub.battery.collect { renderBattery() } }
        viewLifecycleOwner.lifecycleScope.launch { MuseHub.rssi.collect { renderSignal() } }
        viewLifecycleOwner.lifecycleScope.launch { MuseHub.quality.collect { renderQuality() } }
        viewLifecycleOwner.lifecycleScope.launch {
            MuseHub.recorder.state.collect { renderRecordButton() }
        }

        binding.btnConnect.setOnClickListener { onConnectClicked() }
        binding.btnRec.setOnClickListener { onRecordClicked() }
        binding.btnViewMode.setOnClickListener {
            val m = binding.waveView.cycleMode()
            renderModeUi(m)
            Toast.makeText(requireContext(), modeName(m), Toast.LENGTH_SHORT).show()
        }
        buildMarkerButtons()
    }

    private fun modeName(m: EegWaveView.Mode): String = when (m) {
        EegWaveView.Mode.RAW -> getString(R.string.view_raw)
        EegWaveView.Mode.FFT -> getString(R.string.view_fft)
        EegWaveView.Mode.SPECTROGRAM -> getString(R.string.view_spec)
    }

    private fun renderModeUi(m: EegWaveView.Mode) {
        binding.modeBadge.text = modeName(m)
        when (m) {
            EegWaveView.Mode.RAW -> {
                binding.legendRow.visibility = View.VISIBLE
                binding.legendAlt.visibility = View.GONE
            }
            EegWaveView.Mode.FFT -> {
                binding.legendRow.visibility = View.GONE
                binding.legendAlt.visibility = View.VISIBLE
                binding.legendAlt.setText(R.string.view_fft_note)
            }
            EegWaveView.Mode.SPECTROGRAM -> {
                binding.legendRow.visibility = View.GONE
                binding.legendAlt.visibility = View.VISIBLE
                binding.legendAlt.setText(R.string.view_spec_note)
            }
        }
    }

    // ---------------- 状态 / 电量 / 信号 ----------------

    private fun renderStatus(s: ConnState) {
        val ctx = context ?: return
        val (textRes, colorRes) = when (s.status) {
            ConnStatus.IDLE -> R.string.status_idle to R.color.bad
            ConnStatus.SCANNING -> R.string.status_scanning to R.color.medium
            ConnStatus.CONNECTING -> R.string.status_connecting to R.color.medium
            ConnStatus.CONNECTED -> R.string.status_connected to R.color.medium
            ConnStatus.STREAMING -> R.string.status_streaming to R.color.good
            ConnStatus.RECONNECTING -> R.string.status_reconnecting to R.color.medium
            ConnStatus.DISCONNECTED -> R.string.status_disconnected to R.color.bad
        }
        binding.statusText.text = if (s.status == ConnStatus.RECONNECTING) {
            getString(textRes, s.attempt)
        } else {
            getString(textRes)
        }
        val dot = ContextCompat.getDrawable(ctx, R.drawable.dot)?.mutate()
        dot?.setTint(ContextCompat.getColor(ctx, colorRes))
        binding.statusDot.background = dot

        // 连接按钮：未连接→连接；连接中/重连中→取消；已连接→断开
        when (s.status) {
            ConnStatus.IDLE, ConnStatus.SCANNING, ConnStatus.DISCONNECTED ->
                binding.btnConnect.setText(R.string.btn_connect)
            ConnStatus.CONNECTING, ConnStatus.RECONNECTING ->
                binding.btnConnect.setText(R.string.btn_cancel_connect)
            else -> binding.btnConnect.setText(R.string.btn_disconnect)
        }
    }

    private fun onConnectClicked() {
        when (MuseHub.conn.value.status) {
            ConnStatus.IDLE, ConnStatus.SCANNING, ConnStatus.DISCONNECTED -> {
                val addr = MuseHub.prefs.lastDeviceAddress
                if (addr.isNotBlank()) {
                    MuseHub.connectAddress(addr, MuseHub.prefs.lastDeviceName)
                } else {
                    Toast.makeText(requireContext(), R.string.go_connect_hint, Toast.LENGTH_SHORT).show()
                    (activity as? MainActivity)?.goToTab(R.id.nav_connect)
                }
            }
            else -> MuseHub.disconnect()
        }
    }

    private fun renderBattery() {
        binding.batteryView.setPercent(MuseHub.battery.value)
    }

    private fun renderSignal() {
        val r = MuseHub.rssi.value
        if (r == Int.MIN_VALUE) {
            binding.signalText.setText(R.string.overview_signal_unknown)
            return
        }
        val label = when {
            r >= -60 -> getString(R.string.signal_good)
            r >= -75 -> getString(R.string.signal_medium)
            r >= -88 -> getString(R.string.signal_weak)
            else -> getString(R.string.signal_bad)
        }
        binding.signalText.text = getString(R.string.overview_signal_fmt, r, label)
    }

    // ---------------- 电极质量（仅色点 + 通道名） ----------------

    private fun renderQuality() {
        val q = MuseHub.quality.value
        val ctx = context ?: return
        val dots = listOf(binding.dotTp9, binding.dotAf7, binding.dotAf8, binding.dotTp10)
        for (i in 0 until 4) {
            val level = q.levels.getOrElse(i) { -1 }
            val colorRes = when (level) {
                1 -> R.color.good
                2 -> R.color.medium
                4 -> R.color.bad
                else -> android.R.color.darker_gray
            }
            val dot = ContextCompat.getDrawable(ctx, R.drawable.dot)?.mutate()
            dot?.setTint(ContextCompat.getColor(ctx, colorRes))
            dots[i].background = dot
        }
        binding.headbandText.setText(if (q.headbandOn) R.string.headband_on else R.string.headband_off)
        binding.headbandText.setTextColor(
            ContextCompat.getColor(ctx, if (q.headbandOn) R.color.good else R.color.bad),
        )
    }

    // ---------------- 录制文字按钮 ----------------

    private fun renderRecordButton() {
        val ctx = context ?: return
        if (MuseHub.recorder.isRecording) {
            binding.btnRec.setText(R.string.btn_rec_stop)
            binding.btnRec.setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
            binding.btnRec.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.bad)
            binding.btnRec.strokeColor = ContextCompat.getColorStateList(ctx, R.color.bad)
        } else {
            binding.btnRec.setText(R.string.btn_rec_start)
            binding.btnRec.setTextColor(ContextCompat.getColor(ctx, R.color.bad))
            binding.btnRec.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.chipBg)
            binding.btnRec.strokeColor = ContextCompat.getColorStateList(ctx, R.color.bad)
        }
    }

    private fun onRecordClicked() {
        if (MuseHub.recorder.isRecording) {
            viewLifecycleOwner.lifecycleScope.launch {
                MuseHub.stopRecording()
                MuseHub.maybeStopService()
                renderRecordButton()
            }
        } else {
            val st = MuseHub.conn.value.status
            if (st != ConnStatus.STREAMING && st != ConnStatus.CONNECTED) {
                Toast.makeText(requireContext(), R.string.record_need_connection, Toast.LENGTH_LONG).show()
                return
            }
            MuseHub.startRecording()
            renderRecordButton()
            Toast.makeText(
                requireContext(),
                MuseHub.recorder.state.value?.fileName,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // ---------------- 标注（4 键，波形下方） ----------------

    private fun buildMarkerButtons() {
        val row = binding.markerRow
        row.removeAllViews()
        for (i in 1..4) {
            val btn = MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = MuseHub.prefs.markerLabel(i)
                textSize = 20f
                minHeight = 54
                setInsetTop(0)
                setInsetBottom(0)
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                ).apply {
                    marginStart = if (i == 1) 0 else 5
                    marginEnd = if (i == 4) 0 else 5
                }
                setOnClickListener { insertMarker(MuseHub.prefs.markerLabel(i)) }
                setOnLongClickListener {
                    showLabelEditor(i)
                    true
                }
            }
            row.addView(btn)
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
        val input = EditText(ctx).apply {
            text.insert(0, MuseHub.prefs.markerLabel(index))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(ctx)
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

    private fun vibrate() {
        try {
            val v = requireContext().getSystemService(android.content.Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(40)
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

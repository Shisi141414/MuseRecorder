package com.neuroskill.muserecorder.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import com.neuroskill.muserecorder.R
import com.neuroskill.muserecorder.core.MuseHub
import com.neuroskill.muserecorder.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = MuseHub.prefs

        binding.swAccel.isChecked = prefs.recordAccel
        binding.swGyro.isChecked = prefs.recordGyro
        binding.swPpg.isChecked = prefs.recordPpg
        binding.swLowLatency.isChecked = prefs.lowLatency
        binding.swLightOff.isChecked = prefs.lightOff
        binding.swAutoReconnect.isChecked = prefs.autoReconnect
        binding.etPreset.setText(prefs.preset)
        binding.etPrefix.setText(prefs.filePrefix)

        fun apply() {
            prefs.recordAccel = binding.swAccel.isChecked
            prefs.recordGyro = binding.swGyro.isChecked
            prefs.recordPpg = binding.swPpg.isChecked
            prefs.lowLatency = binding.swLowLatency.isChecked
            prefs.lightOff = binding.swLightOff.isChecked
            prefs.autoReconnect = binding.swAutoReconnect.isChecked
            prefs.preset = binding.etPreset.text.toString().trim()
            prefs.filePrefix = binding.etPrefix.text.toString().trim().ifEmpty { "MuseRecord" }
            MuseHub.ble.autoReconnect = prefs.autoReconnect
            MuseHub.applyPrefsToBle()
        }

        for (sw in listOf(binding.swAccel, binding.swGyro, binding.swPpg,
                binding.swLowLatency, binding.swLightOff, binding.swAutoReconnect)) {
            sw.setOnCheckedChangeListener { _, _ -> apply() }
        }
        binding.etPreset.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) apply() }
        binding.etPrefix.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) apply() }

        binding.btnBatteryOpt.setOnClickListener { requestIgnoreBatteryOptimizations() }
        binding.btnLanguage.setOnClickListener { showLanguageDialog() }
        renderLanguageButton()

        binding.btnRange.setOnClickListener { showRangeDialog() }
        renderRangeButton()

        val version = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        binding.aboutText.text = getString(R.string.settings_about, version)
    }

    // ---------------- 显示量程 ----------------

    private val rangeValues = intArrayOf(0, 50, 100, 200, 500)

    private fun rangeLabel(v: Int): String =
        if (v == 0) getString(R.string.range_auto)
        else getString(R.string.range_uv_fmt, v)

    private fun renderRangeButton() {
        binding.btnRange.text = getString(R.string.settings_eeg_range) + " · " +
            getString(R.string.range_current_fmt, rangeLabel(MuseHub.prefs.eegRangeUv))
    }

    private fun showRangeDialog() {
        val current = MuseHub.prefs.eegRangeUv
        val labels = rangeValues.map { rangeLabel(it) }.toTypedArray()
        val checked = rangeValues.indexOfFirst { it == current }.let { if (it < 0) 0 else it }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_eeg_range)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                MuseHub.prefs.eegRangeUv = rangeValues[which]
                renderRangeButton()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- 语言 ----------------

    /** 当前选择索引：0=跟随系统 1=简体中文 2=English */
    private fun currentLanguageIndex(): Int {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return 0
        return when (locales[0]?.language) {
            "zh" -> 1
            "en" -> 2
            else -> 0
        }
    }

    private fun renderLanguageButton() {
        val name = when (currentLanguageIndex()) {
            1 -> getString(R.string.lang_zh)
            2 -> getString(R.string.lang_en)
            else -> getString(R.string.lang_follow_system)
        }
        binding.btnLanguage.text = getString(R.string.lang_current_fmt, name)
    }

    private fun showLanguageDialog() {
        val options = arrayOf(
            getString(R.string.lang_follow_system),
            getString(R.string.lang_zh),
            getString(R.string.lang_en),
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.lang_select_title)
            .setSingleChoiceItems(options, currentLanguageIndex()) { dialog, which ->
                val locales = when (which) {
                    1 -> LocaleListCompat.forLanguageTags("zh")
                    2 -> LocaleListCompat.forLanguageTags("en")
                    else -> LocaleListCompat.getEmptyLocaleList()
                }
                AppCompatDelegate.setApplicationLocales(locales)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${requireContext().packageName}"),
            )
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(requireContext(), "无法打开系统设置", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

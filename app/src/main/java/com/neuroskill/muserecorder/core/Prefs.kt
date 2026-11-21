package com.neuroskill.muserecorder.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** 应用设置（SharedPreferences 封装） */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("muse_recorder", Context.MODE_PRIVATE)

    var recordAccel: Boolean
        get() = sp.getBoolean("recordAccel", true)
        set(v) = sp.edit { putBoolean("recordAccel", v) }

    var recordGyro: Boolean
        get() = sp.getBoolean("recordGyro", true)
        set(v) = sp.edit { putBoolean("recordGyro", v) }

    /** Athena 光学 / legacy PPG */
    var recordPpg: Boolean
        get() = sp.getBoolean("recordPpg", true)
        set(v) = sp.edit { putBoolean("recordPpg", v) }

    /** null/空 = 自动 */
    var preset: String
        get() = sp.getString("preset", "") ?: ""
        set(v) = sp.edit { putString("preset", v) }

    var lowLatency: Boolean
        get() = sp.getBoolean("lowLatency", true)
        set(v) = sp.edit { putBoolean("lowLatency", v) }

    var lightOff: Boolean
        get() = sp.getBoolean("lightOff", false)
        set(v) = sp.edit { putBoolean("lightOff", v) }

    var autoReconnect: Boolean
        get() = sp.getBoolean("autoReconnect", true)
        set(v) = sp.edit { putBoolean("autoReconnect", v) }

    var filePrefix: String
        get() = sp.getString("filePrefix", "MuseRecord") ?: "MuseRecord"
        set(v) = sp.edit { putString("filePrefix", v) }

    /** 脑电显示量程 µV；0 = 自动 */
    var eegRangeUv: Int
        get() = sp.getInt("eegRangeUv", 0)
        set(v) = sp.edit { putInt("eegRangeUv", v) }

    /** 上次连接的设备 */
    var lastDeviceName: String
        get() = sp.getString("lastDeviceName", "") ?: ""
        set(v) = sp.edit { putString("lastDeviceName", v) }

    var lastDeviceAddress: String
        get() = sp.getString("lastDeviceAddress", "") ?: ""
        set(v) = sp.edit { putString("lastDeviceAddress", v) }

    /** 事件预设列表（逗号安全：用 \u0001 分隔存储） */
    fun eventPresets(defaults: List<String>): List<String> {
        val raw = sp.getString("eventPresets", null)
        if (raw != null) {
            return raw.split('\u0001').filter { it.isNotBlank() }
        }
        return defaults
    }

    fun setEventPresets(list: List<String>) {
        sp.edit { putString("eventPresets", list.joinToString("\u0001")) }
    }

    /** 上次选择的事件名（跨会话记住） */
    var lastEventName: String
        get() = sp.getString("lastEventName", "") ?: ""
        set(v) = sp.edit { putString("lastEventName", v) }

    /** 标注按钮文字（1..9） */
    fun markerLabel(i: Int): String = sp.getString("marker$i", i.toString()) ?: i.toString()

    fun setMarkerLabel(i: Int, label: String) = sp.edit { putString("marker$i", label) }

    /** 是否提示过电池优化 */
    var askedBatteryOptimization: Boolean
        get() = sp.getBoolean("askedBatteryOpt", false)
        set(v) = sp.edit { putBoolean("askedBatteryOpt", v) }
}

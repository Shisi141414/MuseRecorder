package com.neuroskill.muserecorder.ble

import java.util.UUID

/**
 * Muse GATT 属性表。
 *
 * 说明：
 * - Muse 设备通过服务 0xFE8D 广播，全部数据特征位于自定义 base UUID
 *   `xxxx-4c4d-454d-96be-f03bac821358`（"MEM-" ASCII）。
 * - 控制特征 273e0001 同时用于 legacy（Muse 2016 / Muse 2 / Muse S 一代）
 *   与 Athena（Muse S Gen 2）协议：写入带帧头的数据包，并通过 notify 接收回复。
 *
 * 参考：muse-lsl (alexandrebarachant/muse-lsl) constants.py / athena.py，
 * 其 Athena 解码移植自 BrainFlow PR #779。
 */
object MuseUuids {
    /** 标准 BLE：Client Characteristic Configuration Descriptor */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Muse 广播服务（Interaxon） */
    val ADVERTISED_SERVICE: UUID = UUID.fromString("0000fe8d-0000-1000-8000-00805f9b34fb")

    private fun u(id: String): UUID = UUID.fromString("$id-4c4d-454d-96be-f03bac821358")

    // ---- 通用 ----
    val CONTROL = u("273e0001")        // 控制通道：写命令 / notify 回复（两种协议共用）

    // ---- Legacy 每通道特征（Muse 2016 / Muse 2 / Muse S 一代）----
    val EEG_TP9 = u("273e0003")
    val EEG_AF7 = u("273e0004")
    val EEG_AF8 = u("273e0005")
    val EEG_TP10 = u("273e0006")
    val EEG_RIGHT_AUX = u("273e0007")
    val GYRO = u("273e0009")
    val ACCELEROMETER = u("273e000a")
    val TELEMETRY = u("273e000b")
    val PPG1 = u("273e000f")           // ambient
    val PPG2 = u("273e0010")           // infrared
    val PPG3 = u("273e0011")           // red

    // ---- Athena（Muse S Gen 2）多路复用数据通道 ----
    val ATHENA_DATA1 = u("273e0013")
    val ATHENA_DATA2 = u("273e0014")

    /** legacy 模式 EEG 特征（按通道顺序） */
    val LEGACY_EEG_CHANNELS = listOf(EEG_TP9, EEG_AF7, EEG_AF8, EEG_TP10, EEG_RIGHT_AUX)

    /** 由 UUID 快速判断协议归属 */
    fun isAthenaData(uuid: UUID): Boolean = uuid == ATHENA_DATA1 || uuid == ATHENA_DATA2
}

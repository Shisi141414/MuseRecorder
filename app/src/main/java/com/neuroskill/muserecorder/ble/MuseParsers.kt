package com.neuroskill.muserecorder.ble

/**
 * Muse BLE 数据包纯解析层（不依赖 Android，可单元测试）。
 *
 * 两种协议：
 *  1. Athena（Muse S Gen 2）：DATA_1/DATA_2 两个特征上的多路复用长度前缀帧，
 *     内含 EEG(14-bit LSB-first) / 光学(20-bit) / ACC+GYRO(int16 LE) / 电池 子包。
 *  2. Legacy（Muse 2016 / Muse 2 / Muse S 一代）：每个传感器一条特征，
 *     大端位打包（EEG 12×12-bit，IMU 9×int16，PPG 6×24-bit，遥测 5×uint16）。
 *
 * 全部解码逻辑与 muse-lsl / BrainFlow PR #779 逐位对齐。
 */
object MuseProtocol {

    // ========================= 通用数据结构 =========================

    /** 一批同步采样的数据：ts[i] 对应 data[ch][i] */
    class SampleBatch(val ts: DoubleArray, val data: Array<FloatArray>)

    /** 遥测（legacy） */
    class Telemetry(
        val batteryPercent: Float,   // %
        val voltageMv: Float,        // mV
        val adcVolt: Int,
        val temperatureRaw: Int,     // 0.01 °C
    )

    // ========================= Athena ==============================

    const val ATHENA_PACKET_HEADER_SIZE = 14
    const val ATHENA_SUBPACKET_HEADER_SIZE = 5
    const val ATHENA_EEG_SCALE = 1450.0f / 16383.0f   // µV / LSB
    const val ATHENA_EEG_MIDPOINT = 1 shl 13          // 8192
    const val ATHENA_ACC_SCALE = 0.0000610352f        // g / LSB
    const val ATHENA_GYRO_SCALE = -0.0074768f         // dps / LSB（注意负号）
    const val ATHENA_BATTERY_SCALE = 1.0f / 512.0f

    const val EEG_RATE = 256.0
    const val IMU_RATE = 52.0
    const val OPTICS_RATE = 64.0
    const val NB_OPTICS = 16

    const val TAG_EEG4 = 0x11      // EEG 4 通道 × 4 样本
    const val TAG_EEG8 = 0x12      // EEG 8 通道 × 2 样本（前 4 通道 = TP9/AF7/AF8/TP10）
    const val TAG_OPTICS4 = 0x34   // 光学 4ch × 3smp（映射到规范通道 4..7）
    const val TAG_OPTICS8 = 0x35   // 光学 8ch × 2smp
    const val TAG_OPTICS16 = 0x36  // 光学 16ch × 1smp
    const val TAG_ACC_GYRO = 0x47  // ACC+GYRO 6ch × 3smp
    const val TAG_BATTERY_VAR = 0x88
    const val TAG_BATTERY = 0x98

    class SensorCfg(
        val kind: Int,        // KIND_*
        val nChannels: Int,
        val nSamples: Int,
        val rateHz: Double,
        val dataLen: Int,     // 0 = 变长
        val variable: Boolean,
    ) {
        companion object {
            const val KIND_EEG = 1
            const val KIND_OPTICS = 2
            const val KIND_ACC_GYRO = 3
            const val KIND_BATTERY = 4
            const val KIND_UNKNOWN = 5
        }
    }

    val SENSOR_CONFIG: Map<Int, SensorCfg> = mapOf(
        0x11 to SensorCfg(SensorCfg.KIND_EEG, 4, 4, EEG_RATE, 28, false),
        0x12 to SensorCfg(SensorCfg.KIND_EEG, 8, 2, EEG_RATE, 28, false),
        0x34 to SensorCfg(SensorCfg.KIND_OPTICS, 4, 3, OPTICS_RATE, 30, false),
        0x35 to SensorCfg(SensorCfg.KIND_OPTICS, 8, 2, OPTICS_RATE, 40, false),
        0x36 to SensorCfg(SensorCfg.KIND_OPTICS, 16, 1, OPTICS_RATE, 40, false),
        0x47 to SensorCfg(SensorCfg.KIND_ACC_GYRO, 6, 3, IMU_RATE, 36, false),
        0x53 to SensorCfg(SensorCfg.KIND_UNKNOWN, 2, 6, 32.0, 24, false),
        0x88 to SensorCfg(SensorCfg.KIND_BATTERY, 1, 1, 0.2, 0, true),
        0x98 to SensorCfg(SensorCfg.KIND_BATTERY, 1, 1, 1.0, 20, false),
    )

    /** 传感器块（主块或子包） */
    class Block(val tag: Int, val packageNum: Int, val payload: ByteArray)

    /** 将一次 notify 拆分为若干长度前缀帧 */
    fun splitPackets(data: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>(4)
        var offset = 0
        val size = data.size
        while (offset < size) {
            if (size - offset < ATHENA_PACKET_HEADER_SIZE) break
            val packetLen = data[offset].toInt() and 0xFF
            if (packetLen < ATHENA_PACKET_HEADER_SIZE || offset + packetLen > size) break
            out.add(data.copyOfRange(offset, offset + packetLen))
            offset += packetLen
        }
        return out
    }

    /** 解析一帧中的全部传感器块（主块 + 子包） */
    fun blocks(packet: ByteArray): List<Block> {
        val out = ArrayList<Block>(4)
        if (packet.size < ATHENA_PACKET_HEADER_SIZE) return out
        val packetIndex = ((packet[2].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
        val primaryTag = packet[9].toInt() and 0xFF
        val payloadStart = ATHENA_PACKET_HEADER_SIZE
        val payload = packet.copyOfRange(payloadStart, packet.size)
        var offset = 0
        val remaining = payload.size

        var cfg = SENSOR_CONFIG[primaryTag]
        if (cfg != null) {
            var primaryLen = if (cfg.variable) remaining else cfg.dataLen
            if (primaryLen > remaining) primaryLen = remaining
            if (primaryLen >= 0) {
                val blockIndex = packet[10].toInt() and 0xFF
                out.add(Block(primaryTag, (packetIndex shl 8) or blockIndex, payload.copyOfRange(0, primaryLen)))
                offset = primaryLen
            }
        } else {
            offset = remaining
        }

        while (offset + ATHENA_SUBPACKET_HEADER_SIZE <= remaining) {
            val tag = payload[offset].toInt() and 0xFF
            val subIndex = payload[offset + 1].toInt() and 0xFF
            cfg = SENSOR_CONFIG[tag] ?: break
            val subRemaining = remaining - offset - ATHENA_SUBPACKET_HEADER_SIZE
            var sensorLen = if (cfg.variable) subRemaining else cfg.dataLen
            if (sensorLen <= 0 || sensorLen > subRemaining) break
            val start = offset + ATHENA_SUBPACKET_HEADER_SIZE
            out.add(Block(tag, (packetIndex shl 8) or subIndex, payload.copyOfRange(start, start + sensorLen)))
            offset = start + sensorLen
        }
        return out
    }

    /** LSB-first 读取 bitWidth 位（与 BrainFlow extract_lsb_bits 一致） */
    fun extractLsbBits(data: ByteArray, bitStart: Int, bitWidth: Int): Int {
        var value = 0
        for (bit in 0 until bitWidth) {
            val abs = bitStart + bit
            if ((data[abs ushr 3].toInt() ushr (abs and 7)) and 0x01 != 0) {
                value = value or (1 shl bit)
            }
        }
        return value
    }

    /**
     * 解码 Athena EEG：14-bit LSB-first，样本主序交错。
     * 输出 [nChannels][nSamples] µV（零中心化）。tag 0x12 解码全部 8 通道，
     * 调用方取前 4 通道。
     */
    fun decodeAthenaEeg(payload: ByteArray, nChannels: Int, nSamples: Int): Array<FloatArray> {
        val out = Array(nChannels) { FloatArray(nSamples) }
        for (s in 0 until nSamples) {
            for (c in 0 until nChannels) {
                val bitStart = (s * nChannels + c) * 14
                val raw = extractLsbBits(payload, bitStart, 14)
                out[c][s] = (raw - ATHENA_EEG_MIDPOINT) * ATHENA_EEG_SCALE
            }
        }
        return out
    }

    /** 解码 Athena ACC+GYRO：6ch int16 LE × nSamples → (acc[3][n], gyro[3][n]) */
    fun decodeAthenaAccGyro(payload: ByteArray, nSamples: Int): Pair<Array<FloatArray>, Array<FloatArray>> {
        val acc = Array(3) { FloatArray(nSamples) }
        val gyro = Array(3) { FloatArray(nSamples) }
        for (s in 0 until nSamples) {
            for (c in 0 until 6) {
                val o = (s * 6 + c) * 2
                val raw = ((payload[o + 1].toInt() shl 8) or (payload[o].toInt() and 0xFF)).toShort().toInt()
                if (c < 3) acc[c][s] = raw * ATHENA_ACC_SCALE
                else gyro[c - 3][s] = raw * ATHENA_GYRO_SCALE
            }
        }
        return acc to gyro
    }

    /** 光学子包的规范通道号（16 通道中的一段） */
    fun opticsCanonicalIndex(tag: Int, channel: Int): Int = when (tag) {
        TAG_OPTICS4 -> if (channel in 0..3) channel + 4 else -1
        TAG_OPTICS8 -> if (channel in 0..7) channel else -1
        TAG_OPTICS16 -> if (channel in 0..15) channel else -1
        else -> -1
    }

    /** 解码光学（20-bit LSB-first）→ [16][nSamples]，未占用通道保持 0 */
    fun decodeAthenaOptics(payload: ByteArray, tag: Int, nChannels: Int, nSamples: Int): Array<FloatArray> {
        val out = Array(NB_OPTICS) { FloatArray(nSamples) }
        for (s in 0 until nSamples) {
            for (c in 0 until nChannels) {
                val bitStart = (s * nChannels + c) * 20
                val raw = extractLsbBits(payload, bitStart, 20)
                val idx = opticsCanonicalIndex(tag, c)
                if (idx >= 0) out[idx][s] = raw.toFloat()
            }
        }
        return out
    }

    /** 电池：首 uint16 LE × 1/512 → 百分比 */
    fun decodeAthenaBattery(payload: ByteArray): Int {
        if (payload.size < 2) return -1
        val raw = (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
        val pct = raw * ATHENA_BATTERY_SCALE
        return if (pct <= 0f) 0 else pct.toInt().coerceAtMost(100)
    }

    /** EEG：取前 [n] 个通道（显式复制，避免泛型数组 copyOf 的类型捕获问题） */
    fun takeChannels(src: Array<FloatArray>, n: Int): Array<FloatArray> {
        val out = Array(n) { src[it] }
        return out
    }

    // ========================= Legacy ==============================

    const val LEGACY_EEG_SCALE = 0.48828125f   // µV / LSB（12-bit, 2 mVpp）
    const val LEGACY_EEG_MIDPOINT = 2048
    const val LEGACY_ACC_SCALE = 0.0000610352f
    const val LEGACY_GYRO_SCALE = 0.0074768f
    const val LEGACY_EEG_SAMPLES = 12
    const val LEGACY_IMU_SAMPLES = 3
    const val LEGACY_PPG_SAMPLES = 6

    private fun readBitBE(data: ByteArray, bitIndex: Int): Int =
        (data[bitIndex ushr 3].toInt() ushr (7 - (bitIndex and 7))) and 1

    /** legacy EEG：uint16 BE 序号 + 12 × uint12 BE → (seq, µV[12]) */
    fun decodeLegacyEeg(packet: ByteArray): Pair<Int, FloatArray> {
        val seq = ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
        val out = FloatArray(LEGACY_EEG_SAMPLES)
        var bit = 16
        for (i in 0 until LEGACY_EEG_SAMPLES) {
            var v = 0
            for (b in 0 until 12) {
                v = (v shl 1) or readBitBE(packet, bit)
                bit++
            }
            out[i] = (v - LEGACY_EEG_MIDPOINT) * LEGACY_EEG_SCALE
        }
        return seq to out
    }

    /** legacy IMU：uint16 BE 序号 + 9 × int16 BE → (seq, [axis][sample]) */
    fun decodeLegacyImu(packet: ByteArray, scale: Float): Pair<Int, Array<FloatArray>> {
        val seq = ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
        val flat = IntArray(9)
        var p = 2
        for (i in 0 until 9) {
            val hi = packet[p++].toInt() and 0xFF
            val lo = packet[p++].toInt() and 0xFF
            flat[i] = ((hi shl 8) or lo).toShort().toInt()
        }
        // 包内顺序为样本主序 [x0 y0 z0 x1 y1 z1 x2 y2 z2]
        val out = Array(3) { FloatArray(LEGACY_IMU_SAMPLES) }
        for (s in 0 until LEGACY_IMU_SAMPLES) {
            for (a in 0 until 3) out[a][s] = flat[s * 3 + a] * scale
        }
        return seq to out
    }

    /** legacy PPG：uint16 BE 序号 + 6 × uint24 BE → (seq, counts[6]) */
    fun decodeLegacyPpg(packet: ByteArray): Pair<Int, IntArray> {
        val seq = ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
        val out = IntArray(LEGACY_PPG_SAMPLES)
        var p = 2
        for (i in 0 until LEGACY_PPG_SAMPLES) {
            out[i] = ((packet[p].toInt() and 0xFF) shl 16) or
                ((packet[p + 1].toInt() and 0xFF) shl 8) or
                (packet[p + 2].toInt() and 0xFF)
            p += 3
        }
        return seq to out
    }

    /** legacy 遥测：5 × uint16 BE（其余为填充） */
    fun decodeLegacyTelemetry(packet: ByteArray): Telemetry {
        fun u16(i: Int) = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
        return Telemetry(
            batteryPercent = u16(2) / 512.0f,
            voltageMv = u16(4) * 2.2f,
            adcVolt = u16(6),
            temperatureRaw = u16(8),
        )
    }

    // ========================= 控制通道 ============================

    /** 控制命令封帧：[len+1, ascii..., '\n'] */
    fun encodeCommand(cmd: String): ByteArray {
        val out = ByteArray(cmd.length + 2)
        out[0] = (cmd.length + 1).toByte()
        for (i in cmd.indices) out[i + 1] = cmd[i].code.toByte()
        out[out.size - 1] = '\n'.code.toByte()
        return out
    }

    /**
     * 控制通道回复重组器：每条 notify 为 [n][ascii×n]。
     * legacy 的 JSON 回复以 '}' 结束；Athena 的回复多为单行。
     * 返回 null 表示尚未形成完整消息。
     */
    class ControlAssembler {
        private val buf = StringBuilder()

        fun reset() = buf.setLength(0)

        fun feed(packet: ByteArray): String? {
            if (packet.isEmpty()) return null
            val n = packet[0].toInt() and 0xFF
            if (n <= 0 || n > packet.size - 1) return null
            val msg = String(packet, 1, n, Charsets.US_ASCII)
            buf.append(msg)
            return if (msg.isNotEmpty() && (msg.last() == '}' || msg.last() == '\n')) {
                val full = buf.toString().trim()
                buf.setLength(0)
                full
            } else if (buf.length > 2048) {
                val full = buf.toString().trim()
                buf.setLength(0)
                full
            } else {
                null
            }
        }
    }

    // ========================= 时间戳去抖 ==========================

    /**
     * 递归最小二乘时间戳校正（RLS，同 muse-lsl _update_timestamp_correction）。
     * 将单调样本序号映射到宿主时间：截距 + 斜率（≈1/rate）随每包到达时间在线拟合。
     */
    class RlsCorrector(val rateHz: Double, t0: Double) {
        private var sampleIndex = 0L
        private var p = 1e-4
        private var intercept = t0
        private var slope = 1.0 / rateHz

        /**
         * 消费接下来 n 个样本，用包到达时间 [hostTime] 拟合后输出每个样本的时间戳。
         */
        fun timestamps(n: Int, hostTime: Double, out: DoubleArray) {
            require(out.size >= n)
            val lastIdx = (sampleIndex + n - 1).toDouble()
            update(lastIdx, hostTime)
            for (k in 0 until n) {
                out[k] = slope * (sampleIndex + k) + intercept
            }
            sampleIndex += n
        }

        /** 外部检测到丢包时，将序号快进到 [index]（只前进不后退） */
        fun advanceTo(index: Long) {
            if (index > sampleIndex) sampleIndex = index
        }

        private fun update(tSource: Double, tReceiver: Double) {
            val tr = tReceiver - intercept
            val pNew = p - (p * p * tSource * tSource) / (1 - p * tSource * tSource)
            slope += pNew * tSource * (tr - tSource * slope)
            p = pNew
        }
    }
}

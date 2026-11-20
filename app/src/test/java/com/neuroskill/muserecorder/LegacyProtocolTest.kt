package com.neuroskill.muserecorder

import com.neuroskill.muserecorder.ble.MuseProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

/** Legacy（Muse 2016 / Muse 2 / Muse S 一代）协议解析测试 */
class LegacyProtocolTest {

    /** 大端位流写入：12-bit 样本依次打包 */
    private fun buildEegPacket(seq: Int, samples12bit: IntArray): ByteArray {
        val out = ByteArray(20)
        out[0] = ((seq shr 8) and 0xFF).toByte()
        out[1] = (seq and 0xFF).toByte()
        var bit = 16
        for (s in samples12bit) {
            for (b in 11 downTo 0) {
                val v = (s shr b) and 1
                if (v == 1) {
                    out[bit ushr 3] = (out[bit ushr 3].toInt() or (0x80 shr (bit and 7))).toByte()
                }
                bit++
            }
        }
        return out
    }

    @Test
    fun `legacy eeg be bit unpack`() {
        val raws = intArrayOf(2048, 0, 4095, 2047, 3000, 1000, 2049, 2050, 1234, 2345, 3456, 456)
        val packet = buildEegPacket(0x1234, raws)
        val (seq, values) = MuseProtocol.decodeLegacyEeg(packet)
        assertEquals(0x1234, seq)
        for (i in raws.indices) {
            val expect = ((raws[i] - 2048) * MuseProtocol.LEGACY_EEG_SCALE).toDouble()
            assertEquals("sample $i", expect, values[i].toDouble(), 1e-4)
        }
    }

    @Test
    fun `legacy imu be int16 axis order`() {
        // [x0 y0 z0 x1 y1 z1 x2 y2 z2] 样本主序
        val flat = shortArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90)
        val packet = ByteArray(20)
        packet[0] = 0
        packet[1] = 99
        for (i in 0 until 9) {
            val v = flat[i].toInt()
            packet[2 + i * 2] = ((v shr 8) and 0xFF).toByte()
            packet[3 + i * 2] = (v and 0xFF).toByte()
        }
        val (seq, samples) = MuseProtocol.decodeLegacyImu(packet, 1f)
        assertEquals(99, seq)
        // samples[axis][sample]
        assertEquals(10f, samples[0][0], 1e-6f)
        assertEquals(40f, samples[0][1], 1e-6f)
        assertEquals(70f, samples[0][2], 1e-6f)
        assertEquals(20f, samples[1][0], 1e-6f)
        assertEquals(90f, samples[2][2], 1e-6f)
    }

    @Test
    fun `legacy ppg uint24 be`() {
        val packet = ByteArray(20)
        packet[0] = 0
        packet[1] = 7
        val vals = intArrayOf(0x010203, 0x0A0B0C, 0x112233, 0x445566, 0x778899, 0xABCDEF)
        var p = 2
        for (v in vals) {
            packet[p++] = ((v shr 16) and 0xFF).toByte()
            packet[p++] = ((v shr 8) and 0xFF).toByte()
            packet[p++] = (v and 0xFF).toByte()
        }
        val (seq, out) = MuseProtocol.decodeLegacyPpg(packet)
        assertEquals(7, seq)
        for (i in vals.indices) assertEquals(vals[i], out[i])
    }

    @Test
    fun `legacy telemetry`() {
        fun u16(a: Int, b: Int) = ((a shl 8) or b)
        val packet = ByteArray(10)
        // index, battery_raw, fuel_gauge_raw, adc, temp
        val fields = intArrayOf(5, 512 * 50, 3000, 2800, 2210)
        for (i in 0 until 5) {
            packet[i * 2] = ((fields[i] shr 8) and 0xFF).toByte()
            packet[i * 2 + 1] = (fields[i] and 0xFF).toByte()
        }
        val t = MuseProtocol.decodeLegacyTelemetry(packet)
        assertEquals(50f, t.batteryPercent, 0.01f)
        assertEquals(3000 * 2.2f, t.voltageMv, 0.01f)
        assertEquals(2800, t.adcVolt)
        assertEquals(2210, t.temperatureRaw)
    }

    @Test
    fun `command frame`() {
        val b = MuseProtocol.encodeCommand("s")
        assertEquals(3, b.size)
        assertEquals(2, b[0].toInt())
        assertEquals('s'.code.toByte(), b[1])
        assertEquals('\n'.code.toByte(), b[2])

        val v6 = MuseProtocol.encodeCommand("v6")
        assertEquals(4, v6.size)
        assertEquals(3, v6[0].toInt())
    }

    @Test
    fun `control assembler reassembles json`() {
        val asm = MuseProtocol.ControlAssembler()
        val part1 = byteArrayOf(6) + "{'sn':".toByteArray(Charsets.US_ASCII)
        assertEquals(null, asm.feed(part1))
        // 第二段以 '}' 结尾 → 完整消息
        val tail = "'12'}x".toByteArray(Charsets.US_ASCII)
        val part2 = byteArrayOf(5) + tail.copyOf(5)
        val msg = asm.feed(part2)
        assertEquals("{'sn':'12'}", msg)
    }
}

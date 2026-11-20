package com.neuroskill.muserecorder

import com.neuroskill.muserecorder.ble.MuseProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Athena（Muse S Gen 2）协议解析测试。
 * 包格式与 BrainFlow PR #779 / muse-lsl athena.py 对齐。
 */
class AthenaProtocolTest {

    /** LSB-first 写入 bitWidth 位 */
    private fun packLsb(data: ByteArray, bitStart: Int, bitWidth: Int, value: Int) {
        for (b in 0 until bitWidth) {
            val abs = bitStart + b
            if ((value shr b) and 1 == 1) {
                data[abs ushr 3] = (data[abs ushr 3].toInt() or (1 shl (abs and 7))).toByte()
            }
        }
    }

    @Test
    fun `eeg 14bit decode matches scale`() {
        // 1 个样本 × 1 通道（借用 4ch 布局验证交错顺序）
        val nCh = 4
        val nSamples = 4
        val payload = ByteArray(28)
        val raws = intArrayOf(8192 + 100, 8192 - 50, 0, 16383)
        for (s in 0 until nSamples) {
            packLsb(payload, (s * nCh) * 14, 14, raws[s])
        }
        val out = MuseProtocol.decodeAthenaEeg(payload, nCh, nSamples)
        assertEquals((100 * MuseProtocol.ATHENA_EEG_SCALE).toDouble(), out[0][0].toDouble(), 1e-3)
        assertEquals((-50 * MuseProtocol.ATHENA_EEG_SCALE).toDouble(), out[0][1].toDouble(), 1e-3)
        assertEquals(((0 - 8192) * MuseProtocol.ATHENA_EEG_SCALE).toDouble(), out[0][2].toDouble(), 1e-3)
        assertEquals(((16383 - 8192) * MuseProtocol.ATHENA_EEG_SCALE).toDouble(), out[0][3].toDouble(), 1e-2)
    }

    @Test
    fun `acc gyro int16 le decode`() {
        // 1 样本 × 6 通道
        val payload = ByteArray(12)
        val vals = shortArrayOf(100, -100, 16384, -16384, 134, 0)
        for (c in 0 until 6) {
            val v = vals[c].toInt()
            payload[c * 2] = (v and 0xFF).toByte()
            payload[c * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val (acc, gyro) = MuseProtocol.decodeAthenaAccGyro(payload, 1)
        assertEquals((100 * MuseProtocol.ATHENA_ACC_SCALE).toDouble(), acc[0][0].toDouble(), 1e-9)
        assertEquals((-100 * MuseProtocol.ATHENA_ACC_SCALE).toDouble(), acc[1][0].toDouble(), 1e-9)
        assertEquals((16384 * MuseProtocol.ATHENA_ACC_SCALE).toDouble(), acc[2][0].toDouble(), 1e-9)
        // 陀螺为负比例
        assertEquals((-16384 * MuseProtocol.ATHENA_GYRO_SCALE).toDouble(), gyro[0][0].toDouble(), 1e-9)
        assertEquals((134 * MuseProtocol.ATHENA_GYRO_SCALE).toDouble(), gyro[1][0].toDouble(), 1e-9)
    }

    @Test
    fun `battery percent`() {
        val payload = byteArrayOf(0x00, 0x30)   // 0x3000 = 12288 → 12288/512 = 24
        assertEquals(24, MuseProtocol.decodeAthenaBattery(payload))
        // 100% = 51200（uint16 内）
        val full = byteArrayOf((51200 and 0xFF).toByte(), (51200 shr 8).toByte())
        assertEquals(100, MuseProtocol.decodeAthenaBattery(full))
    }

    @Test
    fun `frame split and block demux`() {
        // 构造一帧：主块 EEG(0x11, 28B 数据) + 子块 ACC_GYRO(0x47, 36B 数据)
        val headerLen = MuseProtocol.ATHENA_PACKET_HEADER_SIZE
        val subHeaderLen = MuseProtocol.ATHENA_SUBPACKET_HEADER_SIZE
        val eegLen = 28
        val imuLen = 36
        val total = headerLen + eegLen + subHeaderLen + imuLen
        val packet = ByteArray(total)
        packet[0] = total.toByte()                     // 长度前缀
        packet[1] = 0x05                               // packet index lo
        packet[2] = 0x00                               // packet index hi
        packet[9] = 0x11                               // 主块 tag: EEG 4ch
        packet[10] = 0x03                              // block index
        // EEG 数据保持 0（解码结果 = 中点偏移）
        var off = headerLen + eegLen
        packet[off] = 0x47                             // 子包 tag: acc_gyro
        packet[off + 1] = 0x07                         // sub index
        off += subHeaderLen

        val packets = MuseProtocol.splitPackets(packet + byteArrayOf()) // 单帧
        assertEquals(1, packets.size)
        val blocks = MuseProtocol.blocks(packets[0])
        assertEquals(2, blocks.size)
        assertEquals(0x11, blocks[0].tag)
        assertEquals(0x47, blocks[1].tag)
        assertEquals((5 shl 8) or 3, blocks[0].packageNum)
        assertEquals((5 shl 8) or 7, blocks[1].packageNum)
        assertEquals(eegLen, blocks[0].payload.size)
        assertEquals(imuLen, blocks[1].payload.size)
        // EEG 全 0 → 4ch × 4smp，每个值 = (0-8192)*scale
        val eeg = MuseProtocol.decodeAthenaEeg(blocks[0].payload, 4, 4)
        assertEquals(4, eeg.size)
        assertEquals(4, eeg[0].size)
        assertTrue(abs(eeg[0][0].toDouble() - (0 - 8192) * MuseProtocol.ATHENA_EEG_SCALE) < 1e-3)
    }

    @Test
    fun `two packets in one notification`() {
        fun make(tag: Int): ByteArray {
            val len = MuseProtocol.ATHENA_PACKET_HEADER_SIZE + 4
            val p = ByteArray(len)
            p[0] = len.toByte()
            p[9] = tag.toByte()
            p[10] = 1
            return p
        }
        val notification = make(0x98) + make(0x98)
        val packets = MuseProtocol.splitPackets(notification)
        assertEquals(2, packets.size)
    }

    @Test
    fun `optics canonical index`() {
        assertEquals(4, MuseProtocol.opticsCanonicalIndex(0x34, 0))
        assertEquals(7, MuseProtocol.opticsCanonicalIndex(0x34, 3))
        assertEquals(0, MuseProtocol.opticsCanonicalIndex(0x35, 0))
        assertEquals(15, MuseProtocol.opticsCanonicalIndex(0x36, 15))
        assertEquals(-1, MuseProtocol.opticsCanonicalIndex(0x34, 4))
    }
}

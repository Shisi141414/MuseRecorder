package com.neuroskill.muserecorder

import com.neuroskill.muserecorder.ble.MuseProtocol
import com.neuroskill.muserecorder.core.QualityEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class CorrectorAndQualityTest {

    @Test
    fun `rls tracks 256hz stream`() {
        val rate = 256.0
        val corr = MuseProtocol.RlsCorrector(rate, t0 = 0.0)
        var host = 0.0
        val ts = DoubleArray(12)
        var last = Double.NEGATIVE_INFINITY
        var n = 0
        // 模拟 20 秒数据，包间隔 = 12/256 秒（加少量抖动）
        repeat(20 * 256 / 12) {
            corr.timestamps(12, host, ts)
            assertTrue("timestamps must be monotonic", ts[0] > last)
            last = ts[11]
            val jitter = Random.nextDouble(-0.001, 0.001)
            host += 12.0 / rate + jitter
            n += 12
        }
        // 总时长估计误差 < 2%
        assertTrue("span too far off: $last vs ${n / rate}", abs(last - n / rate) < n / rate * 0.02)
    }

    @Test
    fun `rls advanceTo skips on gap`() {
        val corr = MuseProtocol.RlsCorrector(256.0, 0.0)
        val ts = DoubleArray(12)
        corr.timestamps(12, 0.05, ts)
        assertEquals(0.0, ts[0], 1e-9)
        corr.advanceTo(36)   // 跳过 2 组（丢包）
        corr.timestamps(12, 0.15, ts)
        // 截距固定为 t0=0，斜率在线拟合；36 号样本时间 ≈ 36/256
        assertEquals(36.0 / 256.0, ts[0], 0.01)
    }

    private fun sine(freqHz: Double, amp: Double, n: Int = 256, rate: Int = 256): FloatArray =
        FloatArray(n) { (amp * kotlin.math.sin(2 * Math.PI * freqHz * it / rate)).toFloat() }

    private fun chData(vararg waves: FloatArray): Array<FloatArray> = Array(4) { waves.getOrElse(it) { waves[0] } }

    @Test
    fun `quality good for clean eeg`() {
        var result: IntArray? = null
        val q = QualityEstimator(windowSamples = 256) { levels, _ -> result = levels.copyOf() }
        val clean = chData(sine(10.0, 20.0))
        repeat(4) { q.feed(clean) }
        assertEquals(1, result?.get(0))
    }

    @Test
    fun `quality bad for flat signal`() {
        var result: IntArray? = null
        val q = QualityEstimator(windowSamples = 256) { levels, _ -> result = levels.copyOf() }
        val flat = chData(FloatArray(256) { 0f })
        repeat(4) { q.feed(flat) }
        assertEquals(4, result?.get(0))
    }

    @Test
    fun `quality bad for huge noise`() {
        var result: IntArray? = null
        val q = QualityEstimator(windowSamples = 256) { levels, _ -> result = levels.copyOf() }
        val noisy = chData(sine(90.0, 400.0))
        repeat(4) { q.feed(noisy) }
        assertEquals(4, result?.get(0))
    }

    @Test
    fun `quality medium for moderate noise`() {
        var result: IntArray? = null
        val q = QualityEstimator(windowSamples = 256) { levels, _ -> result = levels.copyOf() }
        val noisy = chData(sine(80.0, 80.0))
        repeat(4) { q.feed(noisy) }
        assertTrue("expected 2 or 4, got ${result?.get(0)}", result?.get(0) == 2 || result?.get(0) == 4)
    }
}

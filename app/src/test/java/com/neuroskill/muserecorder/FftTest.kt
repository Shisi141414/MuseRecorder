package com.neuroskill.muserecorder

import com.neuroskill.muserecorder.util.Fft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FftTest {

    @Test
    fun `sine peak lands on correct bin`() {
        val n = 512
        val fs = 256.0
        val f0 = 10.0   // Hz
        val samples = FloatArray(n) { (5.0 * sin(2 * PI * f0 * it / fs)).toFloat() }
        val window = Fft.hann(n)
        val re = FloatArray(n)
        val im = FloatArray(n)
        val db = FloatArray(n / 2 + 1)
        Fft.powerSpectrumDb(samples, window, re, im, db)

        // 峰值 bin：bin = f0 / (fs/n) = 10 / 0.5 = 20
        var peak = 0
        for (k in 1 until db.size) if (db[k] > db[peak]) peak = k
        assertEquals(20, peak)
    }

    @Test
    fun `impulse gives flat spectrum`() {
        val n = 256
        val samples = FloatArray(n)
        samples[0] = 1f
        val re = FloatArray(n)
        val im = FloatArray(n)
        Fft.fft(re.apply { this[0] = 1f }, im)
        // |X(k)| = 1 for all k
        for (k in 0 until n) {
            val mag = re[k] * re[k] + im[k] * im[k]
            assertEquals(1.0, mag.toDouble(), 1e-6)
        }
    }

    @Test
    fun `constant signal concentrates at dc`() {
        val n = 128
        val samples = FloatArray(n) { 2f }
        val window = FloatArray(n) { 1f }   // 矩形窗简化
        val re = FloatArray(n)
        val im = FloatArray(n)
        val db = FloatArray(n / 2 + 1)
        Fft.powerSpectrumDb(samples, window, re, im, db)
        var peak = 0
        for (k in 1 until db.size) if (db[k] > db[peak]) peak = k
        assertEquals(0, peak)
    }
}

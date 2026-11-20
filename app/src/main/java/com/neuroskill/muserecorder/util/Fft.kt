package com.neuroskill.muserecorder.util

/**
 * 基础 radix-2 迭代 FFT（不依赖 Android，可单元测试）。
 */
object Fft {

    /** 就地计算长度为 2 的幂的复数 FFT */
    fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        require(n > 0 && (n and (n - 1)) == 0) { "FFT 长度必须是 2 的幂" }
        require(im.size == n) { "虚部长度不匹配" }

        // 位反转置换
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
            var m = n shr 1
            while (m in 1..j) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        // 蝶形
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val ang = -2.0 * Math.PI / len
            var i = 0
            while (i < n) {
                for (k2 in 0 until half) {
                    val wr = kotlin.math.cos(ang * k2).toFloat()
                    val wi = kotlin.math.sin(ang * k2).toFloat()
                    val ar = re[i + k2 + half]
                    val ai = im[i + k2 + half]
                    val tr = ar * wr - ai * wi
                    val ti = ar * wi + ai * wr
                    re[i + k2 + half] = re[i + k2] - tr
                    im[i + k2 + half] = im[i + k2] - ti
                    re[i + k2] += tr
                    im[i + k2] += ti
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Hann 窗 */
    fun hann(n: Int): FloatArray = FloatArray(n) { i ->
        (0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (n - 1)))).toFloat()
    }

    /**
     * 功率谱（|X|²，平均到 [outDb] 为 dB）。
     * 输入 [samples]（长度为 2 的幂），输出 dB 谱：outDb[k] = 10·log10(|X(k)|²)，k=0..n/2。
     */
    fun powerSpectrumDb(samples: FloatArray, window: FloatArray, re: FloatArray, im: FloatArray, outDb: FloatArray) {
        val n = samples.size
        for (i in 0 until n) {
            re[i] = samples[i] * window[i]
            im[i] = 0f
        }
        fft(re, im)
        val half = n / 2
        for (k in 0..half.coerceAtMost(outDb.size - 1)) {
            val p = re[k] * re[k] + im[k] * im[k]
            outDb[k] = (10.0 * kotlin.math.log10(p.toDouble() + 1e-20)).toFloat()
        }
    }
}

package com.neuroskill.muserecorder.core

/**
 * 电极接触质量估计器（不依赖 Android，可单元测试）。
 *
 * Muse 原生 SDK 的 HSI（马蹄铁）指标不通过开放 GATT 数据流下发，
 * 因此这里在本地用滑动窗口统计量估计每通道质量，输出与
 * Mind Monitor 一致的等级：1=好, 2=中, 4=差。
 */
class QualityEstimator(
    private val windowSamples: Int = 512,   // 2 s @ 256 Hz
    private val onUpdate: ((levels: IntArray, headbandOn: Boolean) -> Unit)? = null,
) {
    private val nCh = 4
    private val buf = Array(nCh) { FloatArray(windowSamples) }
    private val fill = IntArray(nCh)

    /** 每通道等级（1/2/4），全部为 4 且信号平坦时 headbandOn=false */
    var levels = IntArray(nCh) { 4 }
        private set
    var headbandOn = false
        private set

    /** 喂入一批 EEG 样本（µV），data[ch][i] */
    @Synchronized
    fun feed(data: Array<FloatArray>) {
        val ch = minOf(data.size, nCh)
        for (c in 0 until ch) {
            val src = data[c]
            var pos = 0
            while (pos < src.size) {
                val n = minOf(src.size - pos, windowSamples - fill[c])
                System.arraycopy(src, pos, buf[c], fill[c], n)
                fill[c] += n
                pos += n
                if (fill[c] == windowSamples) {
                    levels[c] = classify(buf[c])
                    fill[c] = 0
                }
            }
        }
        headbandOn = levels.count { it == 4 } < nCh || !allFlat()
        onUpdate?.invoke(levels.copyOf(), headbandOn)
    }

    private fun allFlat(): Boolean = (0 until nCh).all { c -> std(buf[c]) < 0.5 }

    private fun std(w: FloatArray): Double {
        var sum = 0.0
        var sumSq = 0.0
        for (v in w) {
            sum += v
            sumSq += v.toDouble() * v
        }
        val n = w.size.toDouble()
        return sqrt(max(0.0, sumSq / n - (sum / n) * (sum / n)))
    }

    private fun classify(w: FloatArray): Int {
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        var sum = 0.0
        var sumSq = 0.0
        var d1SumSq = 0.0
        var flat = 0
        var sat = 0
        var prev = w[0]
        for (i in 0 until w.size) {
            val v = w[i]
            if (v < mn) mn = v
            if (v > mx) mx = v
            sum += v
            sumSq += v.toDouble() * v
            if (i > 0) {
                val d = v - prev
                d1SumSq += d.toDouble() * d
                if (d == 0f) flat++
            }
            if (v > SAT_UV || v < -SAT_UV) sat++
            prev = v
        }
        val n = w.size.toDouble()
        val std = sqrt(max(0.0, sumSq / n - (sum / n) * (sum / n)))
        val d1Std = sqrt(d1SumSq / (n - 1))
        val range = mx - mn
        val flatFrac = flat.toDouble() / n
        val satFrac = sat.toDouble() / n

        // 完全无信号：长时间平坦或持续贴轨
        if (std < 0.5f && flatFrac > 0.90f) return 4
        if (satFrac > 0.20f) return 4
        if (range > 600f || d1Std > 110f) return 4
        if (range > 300f || d1Std > 55f || flatFrac > 0.65f) return 2
        return 1
    }

    private fun sqrt(x: Double) = kotlin.math.sqrt(x)
    private fun max(a: Double, b: Double) = if (a > b) a else b

    companion object {
        /** 接近满量程（Athena ±725 µV，legacy ±1000 µV）视为饱和 */
        const val SAT_UV = 690f
    }
}

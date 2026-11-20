package com.neuroskill.muserecorder.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.neuroskill.muserecorder.core.MuseHub
import com.neuroskill.muserecorder.util.Fft
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * 实时脑电多视图控件（参考 Mind Monitor）：
 *  - RAW 原始波（4 通道，自动或固定量程）
 *  - FFT 频谱（1–60 Hz，4 通道平均，dB）
 *  - SPECTROGRAM 光谱图（瀑布热图，1–60 Hz）
 * 网格/文字/填充颜色随深浅色主题反色，保证可读。
 */
class EegWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { RAW, FFT, SPECTROGRAM }

    companion object {
        private const val DRAW_SAMPLES = 1536      // 6 s @ 256 Hz
        private const val FFT_N = 512              // 2 s 窗口
        private const val F_MIN_HZ = 1f
        private const val F_MAX_HZ = 60f
        private const val DB_RANGE = 45f
        private const val SPEC_COL_MS = 250L
        private const val SPEC_ROW_H = 3f           // px，时间行高（最新在顶）
        private const val SPEC_AXIS_H = 24f         // px，底部频率轴高
        private const val SPEC_UNIT_W = 26f         // px，右端单位位
    }

    var mode = Mode.RAW
        private set

    fun setMode(m: Mode): Boolean {
        if (m == mode) return false
        mode = m
        postInvalidateOnAnimation()
        return true
    }

    fun cycleMode(): Mode {
        val next = when (mode) {
            Mode.RAW -> Mode.FFT
            Mode.FFT -> Mode.SPECTROGRAM
            Mode.SPECTROGRAM -> Mode.RAW
        }
        setMode(next)
        return next
    }

    // ---------------- RAW ----------------

    private val buf = Array(4) { FloatArray(DRAW_SAMPLES) }
    private var sampleCount = 0

    // ---------------- FFT / SPECTROGRAM ----------------

    private val raw = Array(4) { FloatArray(FFT_N) }
    private val re = FloatArray(FFT_N)
    private val im = FloatArray(FFT_N)
    private val psd = FloatArray(FFT_N / 2 + 1)
    private val psdAvg = FloatArray(FFT_N / 2 + 1)
    private val window = Fft.hann(FFT_N)
    private var fftMaxDb = -80f
    private var specBitmapA: Bitmap? = null
    private var specBitmapB: Bitmap? = null
    private var specCanvas: Canvas? = null
    private var lastSpecMs = 0L
    private val specPaint = Paint().apply { style = Paint.Style.FILL }

    // ---------------- 画笔（主题反色） ----------------

    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2E7DD1")
        alpha = 46
    }
    private var gridPaint = Paint().apply { strokeWidth = 1f }
    private var labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private var axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val colors = intArrayOf(
        Color.parseColor("#2E7DD1"),
        Color.parseColor("#2FA84F"),
        Color.parseColor("#E8833A"),
        Color.parseColor("#9B59D6"),
    )

    private fun isNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun applyTheme() {
        val night = isNight()
        gridPaint.color = if (night) Color.argb(66, 200, 205, 220) else Color.argb(40, 110, 115, 135)
        labelPaint.color = if (night) Color.argb(200, 200, 205, 220) else Color.argb(180, 100, 105, 125)
        axisPaint.color = labelPaint.color
        labelPaint.textSize = 26f
        axisPaint.textSize = 26f
    }

    /** 由外部定时调用（约 30 fps） */
    fun refresh() {
        when (mode) {
            Mode.RAW -> {
                sampleCount = MuseHub.wave.copyOut(DRAW_SAMPLES, buf)
            }
            Mode.FFT -> computePsd()
            Mode.SPECTROGRAM -> {
                val now = System.currentTimeMillis()
                if (now - lastSpecMs >= SPEC_COL_MS) {
                    lastSpecMs = now
                    computePsd()
                    pushSpecRow()
                }
            }
        }
        postInvalidateOnAnimation()
    }

    // ---------------- 频谱计算 ----------------

    private fun computePsd() {
        val n = MuseHub.wave.copyOut(FFT_N, raw)
        if (n < FFT_N) {
            for (i in psdAvg.indices) psdAvg[i] = -100f
            return
        }
        for (k in psdAvg.indices) psdAvg[k] = 0f
        for (c in 0 until 4) {
            Fft.powerSpectrumDb(raw[c], window, re, im, psd)
            for (k in psdAvg.indices) psdAvg[k] += psd[k]
        }
        var mx = -120f
        for (k in psdAvg.indices) {
            psdAvg[k] /= 4f
            if (psdAvg[k] > mx) mx = psdAvg[k]
        }
        // 峰值缓慢衰减，保持视觉稳定
        fftMaxDb = if (mx > fftMaxDb) mx else fftMaxDb * 0.97f + mx * 0.03f
    }

    private fun freqToX(hz: Float, w: Float): Float =
        (hz - F_MIN_HZ) / (F_MAX_HZ - F_MIN_HZ) * w

    private fun dbToY(db: Float, h: Float): Float {
        val t = ((db - (fftMaxDb - DB_RANGE)) / DB_RANGE).coerceIn(0f, 1f)
        return h * (1f - t)
    }

    // ---------------- 光谱图（频率横轴，时间纵轴最新在顶） ----------------

    /** 频率 → x（绘图区右端预留单位位） */
    private fun specFx(hz: Float, w: Float): Float =
        (hz - F_MIN_HZ) / (F_MAX_HZ - F_MIN_HZ) * (w - SPEC_UNIT_W)

    private fun pushSpecRow() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val specW = (w - SPEC_UNIT_W).toInt().coerceAtLeast(10)
        val rows = (((h - SPEC_AXIS_H) / SPEC_ROW_H).toInt()).coerceAtLeast(10)
        if (specBitmapA == null || specBitmapA!!.width != specW || specBitmapA!!.height != rows) {
            specBitmapA = Bitmap.createBitmap(specW, rows, Bitmap.Config.ARGB_8888)
            specBitmapB = Bitmap.createBitmap(specW, rows, Bitmap.Config.ARGB_8888)
            specBitmapA!!.eraseColor(Color.TRANSPARENT)
            specCanvas = Canvas(specBitmapA!!)
            return
        }
        val a = specBitmapA!!
        val b = specBitmapB!!
        val canvas = specCanvas!!
        // 画到 B：A 整体下移一行（旧数据向下滚动）
        canvas.setBitmap(b)
        canvas.drawBitmap(a, 0f, SPEC_ROW_H, null)
        // 顶部写入最新一行：横轴为频率
        val dbFloor = fftMaxDb - DB_RANGE
        val binHz = 256f / FFT_N   // 0.5 Hz/bin
        var x = 0f
        while (x < specW) {
            val hz = F_MIN_HZ + x / specW * (F_MAX_HZ - F_MIN_HZ)
            val bin = (hz / binHz).toInt().coerceIn(0, psdAvg.size - 1)
            val t = ((psdAvg[bin] - dbFloor) / DB_RANGE).coerceIn(0f, 1f)
            specPaint.color = heatColor(t, isNight())
            canvas.drawRect(x, 0f, x + 2f, SPEC_ROW_H, specPaint)
            x += 2f
        }
        // 交换：A 成为最新帧
        specBitmapB = a
        specBitmapA = b
    }

    private fun heatColor(t: Float, night: Boolean): Int {
        // 深浅色主题分别标定，保证对比可读（反色适配）
        val stops = if (night) {
            arrayOf(
                0.00f to intArrayOf(16, 19, 30),
                0.25f to intArrayOf(31, 58, 147),
                0.50f to intArrayOf(31, 168, 79),
                0.75f to intArrayOf(232, 162, 58),
                1.00f to intArrayOf(245, 98, 90),
            )
        } else {
            arrayOf(
                0.00f to intArrayOf(235, 238, 246),
                0.25f to intArrayOf(120, 160, 230),
                0.50f to intArrayOf(60, 180, 120),
                0.75f to intArrayOf(240, 170, 60),
                1.00f to intArrayOf(205, 55, 55),
            )
        }
        var lo = stops[0]; var hi = stops[stops.size - 1]
        for (i in 0 until stops.size - 1) {
            if (t >= stops[i].first && t <= stops[i + 1].first) {
                lo = stops[i]; hi = stops[i + 1]
                break
            }
        }
        val span = (hi.first - lo.first).coerceAtLeast(1e-6f)
        val f = ((t - lo.first) / span).coerceIn(0f, 1f)
        val r = lo.second[0] + ((hi.second[0] - lo.second[0]) * f).toInt()
        val g = lo.second[1] + ((hi.second[1] - lo.second[1]) * f).toInt()
        val b2 = lo.second[2] + ((hi.second[2] - lo.second[2]) * f).toInt()
        return Color.rgb(r, g, b2)
    }

    // ---------------- 绘制 ----------------

    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        applyTheme()
        when (mode) {
            Mode.RAW -> drawRaw(canvas)
            Mode.FFT -> drawFft(canvas)
            Mode.SPECTROGRAM -> drawSpectrogram(canvas)
        }
    }

    /** 原始视图：四通道独立分框（上下四栏，各自裁剪互不干扰，共用同一量程） */
    private val laneNames = arrayOf("TP9", "AF7", "AF8", "TP10")

    private fun drawRaw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val padTop = 22f
        val padBottom = 20f
        val plotH = h - padTop - padBottom
        val lanes = 4
        val laneH = plotH / lanes

        // 量程：设置页可改（0 = 自动），四通道共用保证可比
        val prefRange = MuseHub.prefs.eegRangeUv
        var maxAbs = 1f
        if (prefRange > 0) {
            maxAbs = prefRange.toFloat()
        } else {
            if (sampleCount > 0) {
                for (c in 0 until 4) {
                    val arr = buf[c]
                    for (i in 0 until sampleCount) {
                        val v = kotlin.math.abs(arr[i])
                        if (v > maxAbs) maxAbs = v
                    }
                }
            }
            maxAbs = maxOf(maxAbs, 50f)
        }

        if (sampleCount == 0) {
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("…", w / 2, h / 2, labelPaint)
            return
        }

        val stepX = w / (DRAW_SAMPLES - 1).coerceAtLeast(1).toFloat()
        for (c in 0 until lanes) {
            val laneTop = padTop + c * laneH
            val center = laneTop + laneH / 2f
            // 中线
            canvas.drawLine(0f, center, w, center, gridPaint)
            // 通道裁剪区（互不干扰）
            canvas.save()
            canvas.clipRect(0f, laneTop, w, laneTop + laneH)
            tracePaint.color = colors[c]
            val arr = buf[c]
            path.reset()
            var started = false
            for (i in 0 until sampleCount) {
                val x = i * stepX
                val y = center - (arr[i] / maxAbs).coerceIn(-1f, 1f) * (laneH / 2f - 3f)
                if (!started) {
                    path.moveTo(x, y); started = true
                } else path.lineTo(x, y)
            }
            canvas.drawPath(path, tracePaint)
            canvas.restore()
            // 通道名（通道色）
            labelPaint.textAlign = Paint.Align.LEFT
            labelPaint.color = colors[c]
            canvas.drawText(laneNames[c], 8f, laneTop + 16f, labelPaint)
        }
        // 量程标注
        labelPaint.color = if (isNight()) Color.argb(200, 200, 205, 220) else Color.argb(180, 100, 105, 125)
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("+${maxAbs.toInt()}µV", w - 8f, padTop + 14f, labelPaint)
        canvas.drawText("-${maxAbs.toInt()}µV", w - 8f, h - 6f, labelPaint)
    }

    private fun drawFft(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val topPad = 30f
        val bottomPad = 34f
        val plotH = h - topPad - bottomPad
        // 网格：10 Hz 竖线
        var hz = 10
        while (hz <= 50) {
            val x = freqToX(hz.toFloat(), w)
            canvas.drawLine(x, topPad, x, h - bottomPad, gridPaint)
            axisPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("${hz}", x, h - 10f, axisPaint)
            hz += 10
        }
        canvas.drawLine(freqToX(F_MIN_HZ, w), topPad, freqToX(F_MAX_HZ, w), topPad, gridPaint)
        canvas.drawLine(freqToX(F_MIN_HZ, w), h - bottomPad, freqToX(F_MAX_HZ, w), h - bottomPad, gridPaint)

        // 频谱曲线（4 通道平均），裁剪到图框内
        val binHz = 256f / FFT_N
        canvas.save()
        canvas.clipRect(0f, topPad, w, h - bottomPad)
        path.reset()
        var started = false
        var k = (F_MIN_HZ / binHz).toInt()
        while (k <= (F_MAX_HZ / binHz).toInt() && k < psdAvg.size) {
            val x = freqToX(k * binHz, w)
            val y = topPad + (1f - ((psdAvg[k] - (fftMaxDb - DB_RANGE)) / DB_RANGE).coerceIn(0f, 1f)) * plotH
            if (!started) {
                path.moveTo(x, y); started = true
            } else path.lineTo(x, y)
            k++
        }
        canvas.drawPath(path, tracePaint.apply { color = colors[0] })
        // 曲线下填充
        if (started) {
            path.lineTo(freqToX(F_MAX_HZ, w), h - bottomPad)
            path.lineTo(freqToX(F_MIN_HZ, w), h - bottomPad)
            path.close()
            canvas.drawPath(path, fillPaint)
        }
        canvas.restore()
        axisPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Hz", w - 30f, h - 10f, axisPaint)
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("0dB", 8f, topPad - 8f, labelPaint)
        canvas.drawText("-${DB_RANGE.toInt()}dB", 8f, h - bottomPad - 6f, labelPaint)
    }

    private fun drawSpectrogram(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val plotH = h - SPEC_AXIS_H
        if (plotH <= 0) return
        val bmp = specBitmapA
        if (bmp != null && bmp.width > 0) {
            canvas.drawBitmap(bmp, null, android.graphics.RectF(0f, 0f, w - SPEC_UNIT_W, plotH), null)
        } else {
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("…", w / 2, h / 2, labelPaint)
        }
        // 5 Hz 分度竖参考线 + 底部数字（只写数字）
        axisPaint.textSize = 24f
        var hz = 5
        while (hz <= 60) {
            val x = specFx(hz.toFloat(), w)
            canvas.drawLine(x, 0f, x, plotH, gridPaint)
            axisPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("$hz", x, h - 7f, axisPaint)
            hz += 5
        }
        // 起点标 1，单位 Hz 仅在最右端标注一次
        axisPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("1", 2f, h - 7f, axisPaint)
        axisPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("Hz", w - 1f, h - 7f, axisPaint)
        axisPaint.textAlign = Paint.Align.LEFT
        axisPaint.textSize = 26f
    }
}

package com.neuroskill.muserecorder.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 电池图标（设计稿 v3）：饱满圆角造型 + 左侧同色实心凸点 +
 * 电量从左向右扣除（填充锚定右侧）+ 数字双层裁切（填充区白色 / 空区电池同色）。
 * 电量分档：60–100% 绿，20–59% 琥珀，0–19% 红，未知灰色。
 */
class BatteryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        private const val GREEN = 0xFF2FA84F.toInt()
        private const val AMBER = 0xFFE8A23A.toInt()
        private const val RED = 0xFFD84B4B.toInt()
        private const val GRAY = 0xFF9AA0B5.toInt()
    }

    private var percent = -1   // -1 = 未知

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val density = resources.displayMetrics.density

    fun setPercent(p: Int) {
        if (p != percent) {
            percent = p
            invalidate()
        }
    }

    private fun tierColor(p: Int): Int = when {
        p < 0 -> GRAY
        p < 20 -> RED
        p < 60 -> AMBER
        else -> GREEN
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val d = density
        val color = tierColor(percent)

        val nubW = 6f * d
        val gap = 3f * d
        val stroke = 3f * d
        val bodyLeft = nubW + gap
        val bodyRight = w
        val radius = 9f * d

        // 左侧凸点（实心同色）
        paint.style = Paint.Style.FILL
        paint.color = color
        val nubH = h * 0.5f
        canvas.drawRoundRect(
            0f, (h - nubH) / 2f, nubW, (h + nubH) / 2f, 2.5f * d, 2.5f * d, paint,
        )

        // 电池外框
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        canvas.drawRoundRect(
            bodyLeft + stroke / 2f, stroke / 2f, bodyRight - stroke / 2f, h - stroke / 2f,
            radius, radius, paint,
        )

        // 填充：锚定右侧（电量从左向右扣除）
        val pad = stroke + 1.5f * d
        val innerLeft = bodyLeft + pad
        val innerRight = bodyRight - pad
        val innerW = innerRight - innerLeft
        var fillLeft = innerRight
        if (percent > 0 && innerW > 0) {
            val fillW = innerW * percent / 100f
            fillLeft = innerRight - fillW
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(fillLeft, pad, innerRight, h - pad, 4.5f * d, 4.5f * d, paint)
        }

        // 数字：底层电池同色；上层白色裁切到填充区
        val label = if (percent >= 0) percent.toString() else "--"
        textPaint.textSize = 15f * d
        val centerX = bodyLeft + (bodyRight - bodyLeft) / 2f
        val textY = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        textPaint.color = color
        canvas.drawText(label, centerX, textY, textPaint)
        if (percent > 0) {
            canvas.save()
            canvas.clipRect(fillLeft, 0f, bodyRight, h)
            textPaint.color = Color.WHITE
            canvas.drawText(label, centerX, textY, textPaint)
            canvas.restore()
        }
    }
}

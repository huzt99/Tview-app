package com.example.trackview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.sqrt

/**
 * شاشة النقش: شبكة 3×3 يرسم عليها المستخدم النقش.
 * onPatternComplete ينادى لما يرفع إصبعه بعد رسم نقطتين على الأقل.
 */
class PatternLockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onPatternComplete: ((String) -> Unit)? = null

    private val selectedDots = mutableListOf<Int>()
    private var touchX = 0f
    private var touchY = 0f
    private var drawing = false

    private val dotRadius get() = width / 10f
    private val activeRadius get() = dotRadius * 1.6f

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#90CAF9")
        style = Paint.Style.FILL
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1565C0")
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#42A5F5")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#42A5F5")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        alpha = 180
    }
    private val curLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#42A5F5")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        alpha = 100
    }

    private fun dotCenter(index: Int): Pair<Float, Float> {
        val col = index % 3
        val row = index / 3
        val cellW = width / 3f
        val cellH = height / 3f
        return Pair(cellW * col + cellW / 2f, cellH * row + cellH / 2f)
    }

    private fun nearestDot(x: Float, y: Float): Int {
        for (i in 0..8) {
            val (cx, cy) = dotCenter(i)
            val dist = sqrt(((x - cx) * (x - cx) + (y - cy) * (y - cy)).toDouble()).toFloat()
            if (dist < activeRadius * 1.2f) return i
        }
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // رسم الخطوط بين النقاط المختارة
        for (i in 0 until selectedDots.size - 1) {
            val (x1, y1) = dotCenter(selectedDots[i])
            val (x2, y2) = dotCenter(selectedDots[i + 1])
            canvas.drawLine(x1, y1, x2, y2, linePaint)
        }

        // رسم خط متحرك من آخر نقطة لموضع الإصبع
        if (drawing && selectedDots.isNotEmpty()) {
            val (lx, ly) = dotCenter(selectedDots.last())
            canvas.drawLine(lx, ly, touchX, touchY, curLinePaint)
        }

        // رسم النقاط
        for (i in 0..8) {
            val (cx, cy) = dotCenter(i)
            if (selectedDots.contains(i)) {
                canvas.drawCircle(cx, cy, activeRadius, activePaint)
                canvas.drawCircle(cx, cy, activeRadius, ringPaint)
            } else {
                canvas.drawCircle(cx, cy, dotRadius, dotPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                selectedDots.clear()
                drawing = true
                handleTouch(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> handleTouch(event.x, event.y)
            MotionEvent.ACTION_UP -> {
                drawing = false
                touchX = 0f
                touchY = 0f
                if (selectedDots.size >= 2) {
                    onPatternComplete?.invoke(selectedDots.joinToString(","))
                }
                invalidate()
            }
        }
        return true
    }

    private fun handleTouch(x: Float, y: Float) {
        touchX = x
        touchY = y
        val dot = nearestDot(x, y)
        if (dot >= 0 && !selectedDots.contains(dot)) {
            selectedDots.add(dot)
        }
        invalidate()
    }

    fun reset() {
        selectedDots.clear()
        drawing = false
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(size, size)
    }
}

package dev.andrea.perroquet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class CircularCountdownView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f  // Slightly thinner for better visibility
        color = Color.parseColor("#4CAF50")  // Green color for consistency
    }
    
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        color = Color.parseColor("#E0E0E0")  // Lighter gray background
    }
    
    private val rectF = RectF()
    
    // Progress from 0.0 to 1.0
    var progress: Float = 1.0f
        set(value) {
            field = value
            invalidate()
        }
    
    // Text to display in the center (not used anymore but kept for API compatibility)
    var countdownText: String = ""
        set(value) {
            field = value
            // No invalidate needed since we don't display text
        }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val width = width.toFloat()
        val height = height.toFloat()
        val size = minOf(width, height) * 0.6f  // Consistent size for both states
        val strokeWidth = paint.strokeWidth
        
        // Calculate the rectangle for the circle - centered with consistent positioning
        val left = (width - size) / 2
        val top = (height - size) / 2
        val right = left + size
        val bottom = top + size
        
        rectF.set(left, top, right, bottom)
        
        // Draw background circle
        canvas.drawArc(rectF, 0f, 360f, false, backgroundPaint)
        
        // Draw progress arc (clockwise from top)
        val sweepAngle = 360f * progress
        canvas.drawArc(rectF, -90f, sweepAngle, false, paint)
    }
}

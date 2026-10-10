package com.example.trafficlightdetector.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.trafficlightdetector.ml.Detection
import com.example.trafficlightdetector.ml.TrafficLightClass

/**
 * Draws the detection overlay on top of the `PreviewView`.
 *
 * The view is a transparent, non-interactive sheet: it knows nothing about
 * CameraX or TFLite, it only renders the normalised boxes handed to it by the
 * ViewModel, which is what keeps drawing and detection decoupled.
 *
 * What is drawn per detection:
 * * a rounded bounding box in the class colour,
 * * HUD-style corner brackets for the box outline,
 * * a label chip (`GREEN 92%`) pinned above the box,
 * * extra emphasis on the box of the class that currently triggers the alarm.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Detections of the latest frame, in normalised `0f..1f` coordinates. */
    var detections: List<Detection> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            contentDescription = if (value.isEmpty()) {
                null
            } else {
                value.joinToString(", ") { it.displayLabel }
            }
            invalidate()
        }

    /** Size of the frame the detections were normalised against. */
    var imageWidth: Int = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var imageHeight: Int = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Class to emphasise, typically [TrafficLightClass.GREEN_LIGHT] while armed. */
    var highlightedClass: TrafficLightClass? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val emphasisFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * scaledDensity
        isFakeBoldText = true
        textAlign = Paint.Align.LEFT
    }

    /** Reused by every draw pass: onDraw always runs on the UI thread. */
    private val bracketPath = Path()
    private val boxRect = RectF()
    private val chipRect = RectF()

    private val cornerRadius = 6f * density
    private val chipCornerRadius = 5f * density
    private val chipPaddingX = 8f * density
    private val chipPaddingY = 4f * density
    private val chipGap = 4f * density
    private val bracketLength = 22f * density

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frames = detections
        if (frames.isEmpty()) return

        val content = BoxMapper.contentRect(
            viewWidth = width.toFloat(),
            viewHeight = height.toFloat(),
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )
        if (content.width <= 0f || content.height <= 0f) return

        for (detection in frames) {
            val box = detection.toRectF(content)
            if (box.width() <= 0f || box.height() <= 0f) continue

            val color = detection.trafficLightClass.color
            val emphasized = detection.trafficLightClass == highlightedClass

            if (emphasized) {
                emphasisFillPaint.color = color
                emphasisFillPaint.alpha = EMPHASIS_ALPHA
                canvas.drawRoundRect(box, cornerRadius, cornerRadius, emphasisFillPaint)
            }

            boxPaint.color = color
            boxPaint.strokeWidth = (if (emphasized) 4f else 2.5f) * density
            canvas.drawRoundRect(box, cornerRadius, cornerRadius, boxPaint)

            bracketPaint.color = color
            drawCornerBrackets(canvas, box)
            drawLabelChip(canvas, box, detection, content)
        }
    }

    /** Box corners drawn as brackets, the classic HUD look for detections. */
    private fun drawCornerBrackets(canvas: Canvas, box: RectF) {
        val length = minOf(bracketLength, box.width() / 3f, box.height() / 3f)
        if (length <= 0f) return
        bracketPath.reset()
        // top-left
        bracketPath.moveTo(box.left, box.top + length)
        bracketPath.lineTo(box.left, box.top)
        bracketPath.lineTo(box.left + length, box.top)
        // top-right
        bracketPath.moveTo(box.right - length, box.top)
        bracketPath.lineTo(box.right, box.top)
        bracketPath.lineTo(box.right, box.top + length)
        // bottom-right
        bracketPath.moveTo(box.right, box.bottom - length)
        bracketPath.lineTo(box.right, box.bottom)
        bracketPath.lineTo(box.right - length, box.bottom)
        // bottom-left
        bracketPath.moveTo(box.left + length, box.bottom)
        bracketPath.lineTo(box.left, box.bottom)
        bracketPath.lineTo(box.left, box.bottom - length)
        canvas.drawPath(bracketPath, bracketPaint)
    }

    /**
     * Label chip above the box. It flips below the box when there is no room
     * above, and is clamped horizontally so text never leaves the frame.
     */
    private fun drawLabelChip(
        canvas: Canvas,
        box: RectF,
        detection: Detection,
        content: ViewportRect,
    ) {
        val text = detection.displayLabel
        val textWidth = textPaint.measureText(text)
        val chipWidth = textWidth + chipPaddingX * 2f
        val chipHeight = textPaint.textSize + chipPaddingY * 2f

        val chipTop = if (box.top - chipHeight - chipGap >= content.top) {
            box.top - chipHeight - chipGap
        } else {
            box.bottom + chipGap
        }
        val minLeft = content.left
        val maxLeft = (content.right - chipWidth).coerceAtLeast(minLeft)
        val chipLeft = box.left.coerceIn(minLeft, maxLeft)

        chipRect.set(chipLeft, chipTop, chipLeft + chipWidth, chipTop + chipHeight)

        chipPaint.color = detection.trafficLightClass.color
        chipPaint.alpha = CHIP_ALPHA
        canvas.drawRoundRect(chipRect, chipCornerRadius, chipCornerRadius, chipPaint)

        textPaint.color = detection.trafficLightClass.onColor
        val baseline = chipRect.top + chipPaddingY - textPaint.fontMetrics.top
        canvas.drawText(text, chipRect.left + chipPaddingX, baseline, textPaint)
    }

    private fun Detection.toRectF(content: ViewportRect): RectF {
        boxRect.set(
            content.mapX(left),
            content.mapY(top),
            content.mapX(right),
            content.mapY(bottom),
        )
        return boxRect
    }

    private companion object {
        const val EMPHASIS_ALPHA = 48
        const val CHIP_ALPHA = 235
    }
}

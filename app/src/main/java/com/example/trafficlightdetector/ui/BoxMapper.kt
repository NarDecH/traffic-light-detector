package com.example.trafficlightdetector.ui

/**
 * The rectangle (in view pixels) in which the camera frame is actually drawn.
 *
 * PreviewView uses `scaleType="fitCenter"`, i.e. a uniform scale plus black
 * letterbox bars, so the frame content is *not* the whole view — every box must
 * be mapped into this rect, otherwise the boxes drift when aspect ratios differ.
 */
data class ViewportRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)

    /** Maps a normalised `0f..1f` x coordinate to a view x coordinate. */
    fun mapX(normalizedX: Float): Float = left + normalizedX * width

    /** Maps a normalised `0f..1f` y coordinate to a view y coordinate. */
    fun mapY(normalizedY: Float): Float = top + normalizedY * height
}

/**
 * Normalised detection coordinates -> view coordinates.
 *
 * Kept as a pure object (no `Canvas`, no `RectF`) so the maths can be unit
 * tested without an emulator.
 */
object BoxMapper {

    /**
     * @param viewWidth width of the overlay view in pixels
     * @param viewHeight height of the overlay view in pixels
     * @param imageWidth width of the analysed (upright) frame
     * @param imageHeight height of the analysed (upright) frame
     */
    fun contentRect(
        viewWidth: Float,
        viewHeight: Float,
        imageWidth: Int,
        imageHeight: Int,
    ): ViewportRect {
        if (viewWidth <= 0f || viewHeight <= 0f) return ViewportRect(0f, 0f, 0f, 0f)
        // Before the first frame arrives, use the whole view.
        if (imageWidth <= 0 || imageHeight <= 0) return ViewportRect(0f, 0f, viewWidth, viewHeight)

        val imageAspect = imageWidth.toFloat() / imageHeight.toFloat()
        val viewAspect = viewWidth / viewHeight

        return if (imageAspect > viewAspect) {
            // Frame is wider than the view: bars top and bottom.
            val height = viewWidth / imageAspect
            val top = (viewHeight - height) / 2f
            ViewportRect(0f, top, viewWidth, top + height)
        } else {
            // Frame is taller than the view: bars left and right.
            val width = viewHeight * imageAspect
            val left = (viewWidth - width) / 2f
            ViewportRect(left, 0f, left + width, viewHeight)
        }
    }
}

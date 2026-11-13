package com.agentsanywhere.app.feature.remotedesktop.input

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-screen box that shows the remote desktop, plus the local pan/zoom the viewer applied.
 *
 * `(width, height)` is the view size in view pixels, [scale] is the local magnification
 * (`1f` = the whole frame fits), and `(offsetX, offsetY)` is where the scaled content's
 * top-left corner sits in view coordinates. Nothing is zoomed to below `1f`, so at the
 * default scale the content always fits and panning collapses to zero.
 *
 * The remote desktop is *not* resized by zooming: only the mapping from touch points to
 * the wire coordinates changes, which is the "点不准" trap called out in
 * `docs/remote-desktop.md` section 8.1.
 */
data class RemoteViewport(
    val width: Float,
    val height: Float,
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    internal val safeWidth: Float
        get() = width.takeIf { it.isFinite() && it > 0f } ?: 0f

    internal val safeHeight: Float
        get() = height.takeIf { it.isFinite() && it > 0f } ?: 0f

    internal val safeScale: Float
        get() = scale.takeIf { it.isFinite() && it >= REMOTE_VIEW_MIN_SCALE } ?: REMOTE_VIEW_MIN_SCALE
}

/** Smallest local magnification; below it the frame would be smaller than the view. */
const val REMOTE_VIEW_MIN_SCALE: Float = 1f

/** Largest local magnification; pointer precision on a phone does not survive more. */
const val REMOTE_VIEW_MAX_SCALE: Float = 5f

/**
 * Maps a touch point in view coordinates to the remote desktop's normalized `0..1` space,
 * clamping touches that fall outside the picture (letterboxing, overscroll) to the edge.
 */
fun normalizeTouch(x: Float, y: Float, viewport: RemoteViewport): Pair<Float, Float> {
    val width = viewport.safeWidth
    val height = viewport.safeHeight
    if (width == 0f || height == 0f) return 0f to 0f
    val scale = viewport.safeScale
    val contentX = (x - viewport.offsetX) / scale
    val contentY = (y - viewport.offsetY) / scale
    return (contentX / width).clampToUnit() to (contentY / height).clampToUnit()
}

/** Normalized `0..1` coordinates to the `aa.pointer` wire range, with `round(value * 65535)`. */
fun toWireCoordinates(normalized: Pair<Float, Float>): Pair<Int, Int> {
    return wireCoordinate(normalized.first) to wireCoordinate(normalized.second)
}

/**
 * Moves the content by `(dx, dy)` view pixels and clamps the result so the content never
 * leaves a gap at any edge. With the default [REMOTE_VIEW_MIN_SCALE] there is nothing to
 * pan, so the offset stays at zero.
 */
fun panBy(viewport: RemoteViewport, dx: Float, dy: Float): RemoteViewport {
    val scale = viewport.safeScale
    return viewport.copy(
        scale = scale,
        offsetX = clampOffset(viewport.offsetX + dx.orZero(), viewport.safeWidth, scale),
        offsetY = clampOffset(viewport.offsetY + dy.orZero(), viewport.safeHeight, scale),
    )
}

/**
 * Scales around `(focusX, focusY)`: the point under the finger keeps pointing at the same
 * remote pixel, which is what makes pinch zoom feel attached to the content. [factor] is
 * the gesture's relative change (`1.1f` = 10% larger); the resulting scale stays inside
 * `1f..5f` and the offsets are re-clamped for the new content size.
 */
fun zoomBy(viewport: RemoteViewport, factor: Float, focusX: Float, focusY: Float): RemoteViewport {
    val scale = viewport.safeScale
    val newScale = (scale * factor.orZero()).coerceIn(REMOTE_VIEW_MIN_SCALE, REMOTE_VIEW_MAX_SCALE)
    if (viewport.safeWidth == 0f || viewport.safeHeight == 0f) {
        return viewport.copy(scale = newScale, offsetX = 0f, offsetY = 0f)
    }
    val anchorX = (focusX.orZero() - viewport.offsetX) / scale
    val anchorY = (focusY.orZero() - viewport.offsetY) / scale
    return viewport.copy(
        scale = newScale,
        offsetX = clampOffset(focusX.orZero() - anchorX * newScale, viewport.safeWidth, newScale),
        offsetY = clampOffset(focusY.orZero() - anchorY * newScale, viewport.safeHeight, newScale),
    )
}

/**
 * Centers [imageWidth] x [imageHeight] (the decoded frame size) inside a
 * [viewWidth] x [viewHeight] view with aspect fit, which is the starting viewport before
 * the user pans or zooms. Returns a viewport whose content box is the fitted picture.
 */
fun aspectFitViewport(
    viewWidth: Float,
    viewHeight: Float,
    imageWidth: Float,
    imageHeight: Float,
): RemoteViewport {
    if (viewWidth <= 0f || viewHeight <= 0f || imageWidth <= 0f || imageHeight <= 0f) {
        return RemoteViewport(width = viewWidth, height = viewHeight)
    }
    val fit = min(viewWidth / imageWidth, viewHeight / imageHeight)
    val contentWidth = imageWidth * fit
    val contentHeight = imageHeight * fit
    return RemoteViewport(
        width = contentWidth,
        height = contentHeight,
        scale = REMOTE_VIEW_MIN_SCALE,
        offsetX = (viewWidth - contentWidth) / 2f,
        offsetY = (viewHeight - contentHeight) / 2f,
    )
}

private fun Float.clampToUnit(): Float = when {
    isNaN() -> 0f
    else -> coerceIn(0f, 1f)
}

private fun Float.orZero(): Float = if (isFinite()) this else 0f

private fun wireCoordinate(value: Float): Int {
    val unit = value.clampToUnit()
    return (unit * 65535f).roundToInt().coerceIn(0, 65535)
}

/**
 * The content is `width * scale` wide and drawn from [offset], so the offset lives in
 * `[width - width * scale, 0]`: zero when the content exactly fits, negative once zoomed in.
 */
private fun clampOffset(offset: Float, size: Float, scale: Float): Float {
    if (size <= 0f) return 0f
    val min = size - size * scale
    return offset.orZero().coerceIn(min, 0f)
}

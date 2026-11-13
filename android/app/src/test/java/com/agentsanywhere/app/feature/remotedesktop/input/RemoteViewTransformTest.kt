package com.agentsanywhere.app.feature.remotedesktop.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The viewport is where "点不准" comes from: a touch has to survive the local pan/zoom
 * inverse before it becomes a remote coordinate (`docs/remote-desktop.md` section 8.1).
 */
class RemoteViewTransformTest {

    private val viewport = RemoteViewport(width = 1000f, height = 500f)

    @Test
    fun unscaledViewportMapsCornersToTheUnitSquare() {
        assertEquals(0f, normalizeTouch(0f, 0f, viewport).first, TOLERANCE)
        assertEquals(0f, normalizeTouch(0f, 0f, viewport).second, TOLERANCE)
        assertEquals(1f, normalizeTouch(1000f, 500f, viewport).first, TOLERANCE)
        assertEquals(1f, normalizeTouch(1000f, 500f, viewport).second, TOLERANCE)
        assertEquals(0.5f, normalizeTouch(500f, 250f, viewport).first, TOLERANCE)
        assertEquals(0.5f, normalizeTouch(500f, 250f, viewport).second, TOLERANCE)
    }

    @Test
    fun touchesOutsideTheContentClampToTheEdges() {
        val outsideNegative = normalizeTouch(-400f, -400f, viewport)
        val outsidePositive = normalizeTouch(4000f, 4000f, viewport)
        val nonFinite = normalizeTouch(Float.NaN, Float.POSITIVE_INFINITY, viewport)

        assertEquals(0f to 0f, outsideNegative)
        assertEquals(1f to 1f, outsidePositive)
        assertEquals(0f to 1f, nonFinite)
    }

    @Test
    fun theSameRemotePointKeepsItsNormalizedCoordinateAfterZooming() {
        val zoomed = zoomBy(viewport, factor = 2f, focusX = 500f, focusY = 250f)

        assertEquals(2f, zoomed.scale, TOLERANCE)
        assertSameNormalizedPoint(500f, 250f, viewport, zoomed)

        val zoomedOut = zoomBy(zoomed, factor = 2.5f, focusX = 200f, focusY = 100f)
        assertEquals(5f, zoomedOut.scale, TOLERANCE)
    }

    @Test
    fun zoomingKeepsTheGestureFocusFixed() {
        val before = normalizeTouch(320f, 180f, viewport)
        val zoomed = zoomBy(viewport, factor = 3f, focusX = 320f, focusY = 180f)
        val after = normalizeTouch(320f, 180f, zoomed)

        assertEquals(3f, zoomed.scale, TOLERANCE)
        assertEquals(before.first, after.first, TOLERANCE)
        assertEquals(before.second, after.second, TOLERANCE)
    }

    @Test
    fun scaleIsClampedBetweenOneAndFive() {
        val zoomedTooFar = zoomBy(viewport, factor = 100f, focusX = 500f, focusY = 250f)
        val zoomedToNothing = zoomBy(zoomBy(viewport, 4f, 500f, 250f), factor = 0.01f, focusX = 500f, focusY = 250f)

        assertEquals(REMOTE_VIEW_MAX_SCALE, zoomedTooFar.scale, TOLERANCE)
        // The gesture focus stays under the finger: content 500 * 5 = 2500, offset 500 - 2500.
        assertEquals(-2000f, zoomedTooFar.offsetX, TOLERANCE)

        assertEquals(REMOTE_VIEW_MIN_SCALE, zoomedToNothing.scale, TOLERANCE)
        assertEquals(0f, zoomedToNothing.offsetX, TOLERANCE)
        assertEquals(0f, zoomedToNothing.offsetY, TOLERANCE)
    }

    @Test
    fun panningMovesTheNormalizedPointAndClampsAtTheEdges() {
        val zoomed = zoomBy(viewport, factor = 2f, focusX = 500f, focusY = 250f)
        val panned = panBy(zoomed, dx = 100f, dy = 50f)

        assertEquals(zoomed.offsetX + 100f, panned.offsetX, TOLERANCE)
        assertEquals(zoomed.offsetY + 50f, panned.offsetY, TOLERANCE)
        assertEquals(0.45f, normalizeTouch(500f, 250f, panned).first, TOLERANCE)
        assertEquals(0.45f, normalizeTouch(500f, 250f, panned).second, TOLERANCE)

        val clampedTopLeft = panBy(zoomed, dx = 10_000f, dy = 10_000f)
        assertEquals(0f, clampedTopLeft.offsetX, TOLERANCE)
        assertEquals(0f, clampedTopLeft.offsetY, TOLERANCE)

        val clampedBottomRight = panBy(zoomed, dx = -10_000f, dy = -10_000f)
        assertEquals(-1000f, clampedBottomRight.offsetX, TOLERANCE)
        assertEquals(-500f, clampedBottomRight.offsetY, TOLERANCE)
    }

    @Test
    fun panningIsANoOpAtTheMinimumScale() {
        assertEquals(viewport, panBy(viewport, dx = 300f, dy = -300f))
        assertEquals(viewport, panBy(viewport, dx = Float.NaN, dy = Float.POSITIVE_INFINITY))
    }

    @Test
    fun wireCoordinatesUseRoundTimes65535() {
        assertEquals(0 to 0, toWireCoordinates(0f to 0f))
        assertEquals(65535 to 65535, toWireCoordinates(1f to 1f))
        assertEquals(32768 to 32768, toWireCoordinates(0.5f to 0.5f))
        assertEquals(6554 to 6554, toWireCoordinates(0.1f to 0.1f))
    }

    @Test
    fun wireCoordinatesClampOutOfRangeAndNonFiniteInput() {
        assertEquals(65535 to 0, toWireCoordinates(1.5f to -1f))
        assertEquals(0 to 0, toWireCoordinates(Float.NaN to Float.NEGATIVE_INFINITY))
    }

    @Test
    fun aspectFitCentersThePictureInsideTheView() {
        val fitted = aspectFitViewport(viewWidth = 1000f, viewHeight = 1000f, imageWidth = 1920f, imageHeight = 1080f)

        assertEquals(1000f, fitted.width, 0.01f)
        assertEquals(562.5f, fitted.height, 0.01f)
        assertEquals(0f, fitted.offsetX, 0.01f)
        assertEquals(218.75f, fitted.offsetY, 0.01f)
        assertEquals(0f, normalizeTouch(500f, 219f, fitted).second, 0.01f)
        assertEquals(1f, normalizeTouch(500f, 781f, fitted).second, 0.01f)
    }

    private fun assertSameNormalizedPoint(viewX: Float, viewY: Float, before: RemoteViewport, after: RemoteViewport) {
        val first = normalizeTouch(viewX, viewY, before)
        val second = normalizeTouch(viewX, viewY, after)
        assertEquals(first.first, second.first, TOLERANCE)
        assertEquals(first.second, second.second, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 1e-4f
    }
}

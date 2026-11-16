package com.agentsanywhere.app.feature.remotedesktop.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePointerGainTest {
    @Test
    fun slowMotionIsFineAndFastMotionIsCoarse() {
        val slow = RemotePointerGain.gain(distancePx = 1f, elapsedMillis = 20L)
        val fast = RemotePointerGain.gain(distancePx = 24f, elapsedMillis = 16L)

        assertTrue("Slow motion should be below the base gain", slow < RemotePointerGain.BASE_GAIN)
        assertTrue("Fast motion should be above the base gain", fast > RemotePointerGain.BASE_GAIN)
        assertTrue(fast > slow * 2f)
    }

    @Test
    fun theCurveIsMonotonicAndBounded() {
        var previous = 0f
        for (distance in 1..100) {
            val gain = RemotePointerGain.gain(distance.toFloat(), elapsedMillis = 16L)
            assertTrue("gain must not decrease: $distance", gain >= previous)
            assertTrue(
                "gain must stay between fine and coarse: $gain",
                gain >= RemotePointerGain.BASE_GAIN * RemotePointerGain.FINE_GAIN - 0.001f &&
                    gain <= RemotePointerGain.BASE_GAIN * RemotePointerGain.COARSE_GAIN + 0.001f,
            )
            previous = gain
        }
    }

    @Test
    fun tinySampleIntervalsDoNotJumpToMaximumGain() {
        // 2 px in 1 ms would look like 2 px/ms without the floor; with the 8 ms floor it
        // is a normal slow sample and must stay well below the coarse end.
        val gain = RemotePointerGain.gain(distancePx = 2f, elapsedMillis = 1L)
        assertTrue(
            "A 1 ms historical sample must not max out acceleration: $gain",
            gain < RemotePointerGain.BASE_GAIN * RemotePointerGain.COARSE_GAIN * 0.8f,
        )
    }

    @Test
    fun degenerateSamplesDoNotProduceNaN() {
        val gain = RemotePointerGain.gain(distancePx = Float.NaN, elapsedMillis = 0L)
        assertEquals(RemotePointerGain.BASE_GAIN * RemotePointerGain.FINE_GAIN, gain, 0.001f)
    }
}

package com.agentsanywhere.app.feature.remotedesktop.input

import kotlin.math.abs

/**
 * Speed-dependent pointer gain for [RemotePointerMode.Mouse].
 *
 * A fixed multiplier cannot be both precise on a 16 px button and fast across a 1440p
 * desktop. Trackpads solve this with an acceleration curve: slow motion stays at 1:1
 * tracking, fast motion gets a gain above 1. The curve is intentionally smooth (no
 * piecewise jump) so the cursor does not lurch when a swipe changes speed.
 *
 * `distancePx` is the finger travel of one pointer sample and `elapsedMillis` the time
 * since the previous sample; speed is therefore in view px/ms. The default base gain
 * keeps a full-width swipe useful on a phone while the fine end makes one-pixel
 * corrections possible.
 */
object RemotePointerGain {
    /**
     * Gain at the slowest speed, multiplied by [BASE_GAIN]. With [BASE_GAIN] = 1.25 this
     * is 1.0: the cursor follows a slow finger one-for-one, which is what makes small
     * targets and text carets feel planted instead of spongy.
     */
    const val FINE_GAIN: Float = 0.8f

    /** Gain at [REFERENCE_SPEED_PX_PER_MS] and above, multiplied by [BASE_GAIN]. */
    const val COARSE_GAIN: Float = 2.2f

    /** Speed at which the curve reaches the coarse end. */
    const val REFERENCE_SPEED_PX_PER_MS: Float = 0.9f

    /**
     * Floor for one sample's duration. Compose can deliver historical samples only a
     * few milliseconds apart; dividing by 1–2 ms would turn a 2 px jitter into the
     * maximum acceleration. Treating sub-8 ms samples as 8 ms acts as a cheap low-pass
     * without adding cursor lag.
     */
    private const val MIN_SAMPLE_MILLIS: Long = 8L

    /**
     * Overall multiplier. The fine end is 1.0x and the coarse end 2.75x, giving the quick
     * flick-to-cross-the-screen response of a game pad without sacrificing the 1:1 slow
     * tracking.
     */
    const val BASE_GAIN: Float = 1.25f

    fun gain(distancePx: Float, elapsedMillis: Long): Float {
        val distance = if (distancePx.isFinite()) abs(distancePx) else 0f
        val elapsed = elapsedMillis.coerceAtLeast(MIN_SAMPLE_MILLIS).toFloat()
        val speed = distance / elapsed
        val t = (speed / REFERENCE_SPEED_PX_PER_MS).coerceIn(0f, 1f)
        // Smoothstep: C1-continuous at both ends, unlike a linear clamp.
        val curved = t * t * (3f - 2f * t)
        return BASE_GAIN * (FINE_GAIN + (COARSE_GAIN - FINE_GAIN) * curved)
    }
}

package com.agentsanywhere.app.feature.remotedesktop

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteDesktopQualityPresetTest {
    @Test
    fun clearUsesNativeDesktopWidthOnALargePhone() {
        assertEquals(2_560, RemoteDesktopQualityPreset.Clear.qualityFor(2_800)?.maxWidth)
    }

    @Test
    fun portraitPhoneKeepsZoomHeadroomInsteadOfCapturing2560() {
        assertEquals(1_920, RemoteDesktopQualityPreset.Clear.qualityFor(1_260)?.maxWidth)
    }

    @Test
    fun smoothStaysAtItsOwnSmallWidth() {
        assertEquals(1_280, RemoteDesktopQualityPreset.Smooth.qualityFor(2_800)?.maxWidth)
    }

    @Test
    fun unknownViewportLeavesThePresetAlone() {
        assertEquals(
            RemoteDesktopQualityPreset.Clear.quality,
            RemoteDesktopQualityPreset.Clear.qualityFor(null),
        )
    }

    @Test
    fun autoHasAnExplicitDefaultQuality() {
        assertEquals(1_920, RemoteDesktopQualityPreset.Auto.qualityFor(null)?.maxWidth)
    }
}

package com.agentsanywhere.app.feature.remotedesktop

import com.agentsanywhere.app.api.RemoteDesktopScreenshot
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteScreenshotFileTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun fileNameUsesTheActualFormat() {
        assertEquals(
            "remote-conn_abc-20261113183000.jpg",
            RemoteScreenshotFile.fileName("conn_abc", "2026-11-13T18:30:00Z", "jpeg"),
        )
        assertEquals(
            "remote-conn_abc-20261113183000.png",
            RemoteScreenshotFile.fileName("conn_abc", "2026-11-13T18:30:00Z", null),
        )
        assertEquals(
            "remote-conn_abc-20261113183000.png",
            RemoteScreenshotFile.fileName("conn_abc", "2026-11-13T18:30:00Z", "webp"),
        )
    }

    @Test
    fun fileNameSanitisesTheDeviceLabel() {
        assertEquals(
            "remote-My-Device-dev-1-20261113183000.png",
            RemoteScreenshotFile.fileName("My Device / dev:1", "2026-11-13T18:30:00.123Z", "png"),
        )
        assertEquals(
            "remote-remote-unknown.png",
            RemoteScreenshotFile.fileName("", "", null),
        )
    }

    @Test
    fun writePutsTheBytesUnderTheProviderPath() {
        val cache = temporary.newFolder("cache")
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val file = RemoteScreenshotFile.write(cache, "remote-conn-20261113183000.jpg", bytes)

        assertEquals("remote-screenshots", file.parentFile?.name)
        assertTrue(file.isFile)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun dtoOverloadFollowsTheParsedFormat() {
        val shot = RemoteDesktopScreenshot(
            width = 640,
            height = 360,
            capturedAt = "2026-11-13T18:30:00Z",
            dataBase64 = "iVBORw0KGgo=",
            format = "png",
        )
        assertEquals(
            "remote-conn_x-20261113183000.png",
            RemoteScreenshotFile.fileName("conn_x", shot),
        )
    }
}

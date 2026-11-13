package com.agentsanywhere.app.feature.remotedesktop

import com.agentsanywhere.app.api.RemoteDesktopScreenshot
import java.io.File

/**
 * Naming and storage of captured still frames.
 *
 * Kept out of the Compose layer so the naming rules can be unit tested on the JVM: the
 * file name must be stable (device + timestamp), filesystem safe, and its extension must
 * follow the *actual* format returned by the server, not the one we asked for.
 */
object RemoteScreenshotFile {
    /** Cache subdirectory also exposed by the app's FileProvider (see `attachment_paths.xml`). */
    const val DIRECTORY = "remote-screenshots"

    fun extension(format: String?): String =
        when (format?.trim()?.lowercase()) {
            "jpeg", "jpg" -> "jpg"
            else -> "png"
        }

    /** `remote-<device>-20261113183000.jpg`. */
    fun fileName(connectorId: String, capturedAt: String, format: String?): String {
        val label = connectorId
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-')
            .take(48)
            .ifEmpty { "remote" }
        val digits = capturedAt.filter { it.isDigit() }
        val stamp = if (digits.length >= 14) digits.take(14) else digits.ifEmpty { "unknown" }
        return "remote-$label-$stamp.${extension(format)}"
    }

    fun fileName(connectorId: String, screenshot: RemoteDesktopScreenshot): String =
        fileName(connectorId, screenshot.capturedAt, screenshot.format)

    /** Writes [bytes] under `<cacheDir>/remote-screenshots/` and returns the file. */
    fun write(cacheDir: File, fileName: String, bytes: ByteArray): File {
        val directory = File(cacheDir, DIRECTORY)
        if (!directory.isDirectory) directory.mkdirs()
        val target = File(directory, fileName)
        target.writeBytes(bytes)
        return target
    }
}

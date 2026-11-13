package com.agentsanywhere.app.ui.screens.remotedesktop

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.agentsanywhere.app.R
import com.agentsanywhere.app.api.RemoteDesktopScreenshot
import com.agentsanywhere.app.feature.remotedesktop.RemoteScreenshotFile
import java.io.File

/**
 * Saves a captured frame into the app cache and hands it to the system share sheet.
 *
 * There is no "gallery" write on purpose: the screenshot is a transient artifact of the
 * session, and sharing lets the user put it wherever they actually want it.
 */
internal fun saveAndShareScreenshot(
    context: Context,
    connectorId: String,
    screenshot: RemoteDesktopScreenshot,
): File {
    val name = RemoteScreenshotFile.fileName(connectorId, screenshot)
    val file = RemoteScreenshotFile.write(context.cacheDir, name, screenshot.bytes())
    shareScreenshot(context, file, screenshot.mimeType(), name)
    return file
}

private fun shareScreenshot(context: Context, file: File, mimeType: String, name: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, name)
        clipData = ClipData.newRawUri(name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, context.getString(R.string.remote_desktop_screenshot_share))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )
}

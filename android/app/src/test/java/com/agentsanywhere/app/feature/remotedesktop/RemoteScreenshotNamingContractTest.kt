package com.agentsanywhere.app.feature.remotedesktop

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the shared naming rule: the same cases live in
 * `contracts/remote-desktop/1.0/screenshot-naming.json`, where the Web and Desktop
 * clients also read them, so a change in one client breaks the other two suites.
 */
class RemoteScreenshotNamingContractTest {
    private fun contractFile(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val candidate = File(directory, "contracts/remote-desktop/1.0/screenshot-naming.json")
            if (candidate.isFile) return candidate
            directory = directory?.parentFile
        }
        error("screenshot-naming.json not found above ${System.getProperty("user.dir")}")
    }

    @Test
    fun fileNameMatchesEveryContractCase() {
        val contract = JSONObject(contractFile().readText())
        val cases = contract.getJSONArray("cases")
        assertTrue("contract has cases", cases.length() > 0)
        for (index in 0 until cases.length()) {
            val item = cases.getJSONObject(index)
            val format = if (item.isNull("format")) null else item.getString("format")
            assertEquals(
                "case $index (${item.getString("connectorId")})",
                item.getString("fileName"),
                RemoteScreenshotFile.fileName(
                    item.getString("connectorId"),
                    item.getString("capturedAt"),
                    format,
                ),
            )
        }
    }
}

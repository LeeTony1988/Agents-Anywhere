package com.agentsanywhere.app.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket and cursor are query values, so they must survive URL encoding: a `+`, `/` or `&`
 * in a ticket would otherwise change the query or truncate the cursor.
 */
class RemoteDesktopWebSocketUrlTest {

    @Test
    fun buildsTheSessionSocketWithATicketOnly() {
        val url = remoteDesktopSessionWebSocketUrl(
            serverUrl = "https://example.com",
            sessionId = "rds_01JABCDEF",
            ticket = "wst_1",
        )

        assertEquals(
            "wss://example.com/api/v2/remote-desktop/sessions/rds_01JABCDEF/ws?ticket=wst_1",
            url,
        )
    }

    @Test
    fun appendsTheReplayCursor() {
        val url = remoteDesktopSessionWebSocketUrl(
            serverUrl = "https://example.com",
            sessionId = "rds_01JABCDEF",
            ticket = "wst_1",
            cursor = "42",
        )

        assertTrue(url.endsWith("?ticket=wst_1&cursor=42"))
    }

    @Test
    fun omitsABlankCursor() {
        val blank = remoteDesktopSessionWebSocketUrl("https://example.com", "rds_1", "wst_1", cursor = "")
        val space = remoteDesktopSessionWebSocketUrl("https://example.com", "rds_1", "wst_1", cursor = "  ")

        assertFalse(blank.contains("cursor="))
        assertFalse(space.contains("cursor="))
    }

    @Test
    fun encodesTicketCursorAndSessionId() {
        val url = remoteDesktopSessionWebSocketUrl(
            serverUrl = "https://example.com",
            sessionId = "rds 1",
            ticket = "wst+1/2=",
            cursor = "12 34&x",
        )

        assertEquals(
            "wss://example.com/api/v2/remote-desktop/sessions/rds%201/ws" +
                "?ticket=wst%2B1%2F2%3D&cursor=12%2034%26x",
            url,
        )
        assertEquals(1, url.count { it == '?' })
        assertEquals(1, url.count { it == '&' })
    }

    @Test
    fun plainHttpServersUseWs() {
        val url = remoteDesktopSessionWebSocketUrl("http://192.168.1.20:8000", "rds_1", "wst_1")

        assertTrue(url.startsWith("ws://192.168.1.20:8000/api/v2/remote-desktop/sessions/rds_1/ws"))
    }

    @Test
    fun serverUrlsWithTheApiNamespaceProduceOneNamespace() {
        val url = remoteDesktopSessionWebSocketUrl("https://example.com/api/v2", "rds_1", "wst_1")

        assertEquals(1, url.split("/api/v2").size - 1)
    }
}

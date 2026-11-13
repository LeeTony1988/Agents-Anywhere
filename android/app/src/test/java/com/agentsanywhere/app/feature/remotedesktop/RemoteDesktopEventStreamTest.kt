package com.agentsanywhere.app.feature.remotedesktop

import com.agentsanywhere.app.api.RemoteDesktopQuality
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the stream through fakes: a socket factory instead of okhttp, a scheduler instead
 * of a delayed executor. Cursor handling and control-plane frames are the wire-visible parts.
 */
class RemoteDesktopEventStreamTest {

    private val sockets = RecordingSocketFactory()
    private val scheduler = FakeScheduler()
    private val events = mutableListOf<RemoteDesktopServerEvent>()
    private val stream = RemoteDesktopEventStream(
        sockets = sockets,
        scheduler = scheduler,
        clock = { 1_762_944_000_123L },
    )

    @Test
    fun opensWithTheCreateTicketAndNoCursor() {
        stream.open(request(ticket = "wst_1"), listener())

        assertEquals(1, sockets.urls.size)
        assertTrue(sockets.urls[0].endsWith("/ws?ticket=wst_1"))
        assertFalse(sockets.urls[0].contains("cursor="))
        assertNull(stream.cursor)
    }

    @Test
    fun remembersTheSubscribedCursorAndReplaysItOnReconnect() {
        stream.open(request(ticket = "wst_1", refreshTicket = { "wst_2" }), listener())
        sockets.listeners[0].onOpen()
        sockets.listeners[0].onText(
            """{"type":"remote_desktop.subscribed","payload":{"sessionId":"rds_1","cursor":"7"}}""",
        )

        assertEquals("7", stream.cursor)

        sockets.listeners[0].onFailure(java.io.IOException("socket closed"))
        assertEquals(1, scheduler.pending)
        scheduler.runNext()

        assertEquals(2, sockets.urls.size)
        assertTrue(sockets.urls[1].endsWith("/ws?ticket=wst_2&cursor=7"))
    }

    @Test
    fun backsOffWithGrowingDelays() {
        stream.open(request(ticket = "wst_1", refreshTicket = { "wst_2" }), listener())

        sockets.listeners[0].onFailure(java.io.IOException("boom"))
        assertEquals(REMOTE_DESKTOP_RECONNECT_BASE_MILLIS, scheduler.runNext())

        sockets.listeners[1].onFailure(java.io.IOException("boom again"))
        assertEquals(REMOTE_DESKTOP_RECONNECT_BASE_MILLIS * 2, scheduler.runNext())

        sockets.listeners[2].onFailure(java.io.IOException("still down"))
        assertEquals(REMOTE_DESKTOP_RECONNECT_BASE_MILLIS * 4, scheduler.runNext())
    }

    @Test
    fun deliversParsedEventsAndIgnoresUnknownTypes() {
        stream.open(request(ticket = "wst_1"), listener())

        sockets.listeners[0].onText(
            """{"type":"remote_desktop.subscribed","payload":{"sessionId":"rds_1","cursor":""}}""",
        )
        sockets.listeners[0].onText(
            """{"type":"remote_desktop.control.revoked","payload":{"sessionId":"rds_1","reason":"taken_over"}}""",
        )
        sockets.listeners[0].onText("""{"type":"remote_desktop.mystery","payload":{"sessionId":"rds_1"}}""")
        sockets.listeners[0].onText("not json")

        assertEquals(2, events.size)
        assertTrue(events[0] is RemoteDesktopServerEvent.Subscribed)
        assertTrue(events[1] is RemoteDesktopServerEvent.ControlRevoked)
    }

    @Test
    fun sendsOnlyControlPlaneFrames() {
        stream.open(request(ticket = "wst_1"), listener())
        val socket = sockets.sockets[0]

        assertTrue(stream.ping(at = 1_762_944_000_200L))
        assertTrue(stream.requestKeyframe())
        assertTrue(
            stream.updateQuality(
                RemoteDesktopQuality(maxFps = 15, maxBitrateKbps = 1500, preferCodec = "vp8"),
            ),
        )

        val ping = JSONObject(socket.sent[0])
        assertEquals("ping", ping.getString("type"))
        assertEquals(1_762_944_000_200L, ping.getLong("at"))

        val keyframe = JSONObject(socket.sent[1])
        assertEquals("request_keyframe", keyframe.getString("type"))
        assertEquals(1, keyframe.length())

        val quality = JSONObject(socket.sent[2])
        assertEquals("update_quality", quality.getString("type"))
        assertEquals(15, quality.getInt("maxFps"))
        assertEquals(1500, quality.getInt("maxBitrateKbps"))
        // client-ws-message.schema.json forbids additional fields on update_quality.
        assertFalse(quality.has("preferCodec"))
    }

    @Test
    fun closeStopsReconnectsAndSending() {
        stream.open(request(ticket = "wst_1", refreshTicket = { "wst_2" }), listener())
        val socket = sockets.sockets[0]

        stream.close()

        assertTrue(socket.closed)
        assertFalse(stream.ping(1L))
        sockets.listeners[0].onFailure(java.io.IOException("closed"))
        assertEquals(0, scheduler.pending)
        assertNull(stream.cursor)
    }

    @Test
    fun reconnectWithoutATicketSourceStopsInsteadOfSpinning() {
        stream.open(request(ticket = "wst_1", refreshTicket = null), listener())

        sockets.listeners[0].onFailure(java.io.IOException("boom"))

        assertEquals(0, scheduler.pending)
        assertEquals(1, sockets.urls.size)
    }

    @Test
    fun aFailedTicketRefreshRetriesWithTheNextBackoff() {
        var attempts = 0
        stream.open(
            request(ticket = "wst_1", refreshTicket = { if (attempts++ == 0) null else "wst_3" }),
            listener(),
        )

        sockets.listeners[0].onFailure(java.io.IOException("boom"))
        scheduler.runNext()
        assertEquals("No ticket yet, so no second connection", 1, sockets.urls.size)
        assertEquals(1, scheduler.pending)

        scheduler.runNext()
        assertEquals(2, sockets.urls.size)
        assertTrue(sockets.urls[1].endsWith("ticket=wst_3"))
        assertNotNull(sockets.sockets[1])
    }

    private fun listener() = RemoteDesktopEventStream.Listener { event -> events += event }

    private fun request(ticket: String, refreshTicket: (() -> String?)? = null) = RemoteDesktopStreamRequest(
        serverUrl = "https://example.com",
        sessionId = "rds_1",
        ticket = ticket,
        refreshTicket = refreshTicket,
    )

    private class RecordingSocketFactory : RemoteDesktopSocketFactory {
        val urls = mutableListOf<String>()
        val listeners = mutableListOf<RemoteDesktopSocketListener>()
        val sockets = mutableListOf<FakeSocket>()

        override fun open(url: String, listener: RemoteDesktopSocketListener): RemoteDesktopSocket {
            urls += url
            listeners += listener
            val socket = FakeSocket()
            sockets += socket
            return socket
        }
    }

    private class FakeSocket : RemoteDesktopSocket {
        val sent = mutableListOf<String>()
        var closed = false

        override fun send(text: String): Boolean {
            if (closed) return false
            sent += text
            return true
        }

        override fun close(code: Int, reason: String?) {
            closed = true
        }
    }

    private class FakeScheduler : RemoteDesktopScheduler {
        private val tasks = mutableListOf<Pair<Long, () -> Unit>>()

        val pending: Int
            get() = tasks.size

        override fun schedule(delayMillis: Long, block: () -> Unit): RemoteDesktopCancellable {
            val entry = delayMillis to block
            tasks += entry
            return RemoteDesktopCancellable { tasks.remove(entry) }
        }

        fun runNext(): Long {
            val (delay, block) = tasks.removeAt(0)
            block()
            return delay
        }
    }
}

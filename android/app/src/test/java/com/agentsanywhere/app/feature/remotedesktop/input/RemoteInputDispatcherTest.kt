package com.agentsanywhere.app.feature.remotedesktop.input

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire half of the input pipeline: gestures arrive in view pixels, get mapped through the
 * current [RemoteViewport] (the inverse of the local pan/zoom) and leave as `aa.input` JSON or
 * 12-byte `aa.pointer` frames.
 */
class RemoteInputDispatcherTest {

    private val inputs = mutableListOf<JSONObject>()
    private val frames = mutableListOf<ByteArray>()
    private var now = 0L
    private val dispatcher = RemoteInputDispatcher(
        sink = object : RemoteInputSink {
            override fun sendInput(json: String) {
                inputs += JSONObject(json)
            }

            override fun sendPointerFrame(bytes: ByteArray) {
                frames += bytes
            }
        },
        throttle = RemoteInputThrottle(clock = { now }),
    )

    private val viewport = RemoteViewport(width = 400f, height = 200f)

    @Test
    fun aTapClicksAtTheTouchedPointInNormalizedSpace() {
        dispatcher.clickAt(x = 100f, y = 50f, button = PointerButton.Left, viewport = viewport)

        assertEquals(2, inputs.size)
        val down = inputs[0]
        assertEquals("pointer", down.getString("t"))
        assertEquals("down", down.getString("a"))
        assertEquals(0.25, down.getDouble("x"), 0.001)
        assertEquals(0.25, down.getDouble("y"), 0.001)
        assertEquals("left", down.getString("b"))
        assertEquals("up", inputs[1].getString("a"))
    }

    @Test
    fun aTapOnAZoomedAndPannedPictureMapsThroughTheInverseTransform() {
        // 2x zoom, content shifted left by 100 px: view x=300 is content x=(300+100)/2=200.
        val zoomed = RemoteViewport(width = 400f, height = 200f, scale = 2f, offsetX = -100f, offsetY = 0f)

        dispatcher.clickAt(x = 300f, y = 50f, button = PointerButton.Left, viewport = zoomed)

        assertEquals(0.5, inputs[0].getDouble("x"), 0.001)
        assertEquals(0.125, inputs[0].getDouble("y"), 0.001)
    }

    @Test
    fun aRightClickCarriesTheRightButton() {
        dispatcher.clickAt(x = 200f, y = 100f, button = PointerButton.Right, viewport = viewport)

        assertEquals("right", inputs[0].getString("b"))
        assertEquals("down", inputs[0].getString("a"))
    }

    @Test
    fun draggingSendsTwelveBytePointerFrames() {
        dispatcher.movePointer(dxView = 40f, dyView = 20f, viewport = viewport)

        assertEquals(1, frames.size)
        val frame = decodePointerFrame(frames.single())
        assertNotNull(frame)
        assertEquals(REMOTE_POINTER_FRAME_SIZE, frames.single().size)
        // 0.5 + 40/400 * 1.8 = 0.68 and 0.5 + 20/200 * 1.8 = 0.68.
        val expected = (0.68f * 65535f).toInt().toDouble()
        assertEquals(expected, frame!!.x.toDouble(), 1.0)
        assertEquals(expected, frame.y.toDouble(), 1.0)
    }

    @Test
    fun thePointerStaysInsideTheUnitSquare() {
        repeat(10) { dispatcher.movePointer(dxView = 400f, dyView = 200f, viewport = viewport) }

        assertEquals(1f, dispatcher.cursorX, 0.0001f)
        assertEquals(1f, dispatcher.cursorY, 0.0001f)
    }

    @Test
    fun aHeldButtonSetsThePointerFrameButtonBits() {
        dispatcher.setButton(PointerButton.Left, down = true)
        dispatcher.movePointer(dxView = 10f, dyView = 0f, viewport = viewport)

        val frame = decodePointerFrame(frames.last())!!
        assertEquals(REMOTE_POINTER_BUTTON_LEFT, frame.buttons)
        assertEquals(REMOTE_POINTER_FLAG_BUTTONS_CHANGED, frame.flags)

        dispatcher.setButton(PointerButton.Left, down = false)
        assertEquals(0, dispatcher.buttons)
    }

    @Test
    fun scrollingSendsAWheelEventInPixels() {
        dispatcher.scroll(dx = 0f, dy = 48f)

        val wheel = inputs.single()
        assertEquals("wheel", wheel.getString("t"))
        assertEquals(48.0, wheel.getDouble("dy"), 0.001)
        assertEquals("pixel", wheel.getString("unit"))
    }

    @Test
    fun aKeyCarriesTheArmedModifiersAndClearsThem() {
        dispatcher.modifiers.toggle(RemoteModifierKey.Ctrl)
        dispatcher.sendKey("KeyC", down = true)

        val key = inputs.single()
        assertEquals("key", key.getString("t"))
        assertEquals("KeyC", key.getString("code"))
        assertEquals("down", key.getString("a"))
        assertEquals("ctrl", key.getJSONArray("mods").getString(0))
        assertTrue(dispatcher.modifiers.isEmpty)
    }

    @Test
    fun releaseAllResetsTheLocalButtonMaskAndSendsTheEvent() {
        dispatcher.setButton(PointerButton.Left, down = true)
        inputs.clear()

        dispatcher.releaseAll(RELEASE_REASON_PAGE_HIDE)

        val event = inputs.single()
        assertEquals("release_all", event.getString("t"))
        assertEquals("pagehide", event.getString("reason"))
        assertEquals(0, dispatcher.buttons)
    }

    @Test
    fun gestureActionsAreDispatchedAndLocalOnesAreReturned() {
        val local = dispatcher.handle(
            actions = listOf(
                RemoteGestureAction.LocalZoom(factor = 1.2f, focusX = 10f, focusY = 20f),
                RemoteGestureAction.RemoteClick(PointerButton.Left, x = 200f, y = 100f),
                RemoteGestureAction.Scroll(dx = 0f, dy = 30f),
                RemoteGestureAction.DragLock(locked = true),
            ),
            viewport = viewport,
        )

        assertEquals(2, inputs.count { it.getString("t") == "pointer" })
        assertEquals(1, inputs.count { it.getString("t") == "wheel" })
        assertEquals(
            listOf<RemoteGestureAction>(RemoteGestureAction.LocalZoom(1.2f, 10f, 20f)),
            local.filterIsInstance<RemoteGestureAction.LocalZoom>(),
        )
        assertTrue(local.any { it is RemoteGestureAction.DragLock })
    }

    @Test
    fun theRateLimitCoalescesMotionInsteadOfSendingEverySample() {
        // The 120/s window fills with discrete wheel events, then motion is coalesced away.
        repeat(MAX_REMOTE_INPUT_EVENTS_PER_SECOND) { dispatcher.scroll(dx = 0f, dy = 1f) }
        frames.clear()

        dispatcher.movePointer(dxView = 10f, dyView = 0f, viewport = viewport)

        assertTrue("A coalesced move is not sent immediately", frames.isEmpty())
        // Once a slot frees up the newest sample is flushed instead of every intermediate one.
        now = REMOTE_INPUT_WINDOW_MILLIS + 1L
        dispatcher.flushCoalescedMove()
        assertEquals(1, frames.size)
    }

    @Test
    fun aZeroDeltaMoveSendsNothingBecauseTheViewportIsEmpty() {
        dispatcher.movePointer(dxView = 10f, dyView = 10f, viewport = RemoteViewport(0f, 0f))

        assertTrue(frames.isEmpty())
    }
}

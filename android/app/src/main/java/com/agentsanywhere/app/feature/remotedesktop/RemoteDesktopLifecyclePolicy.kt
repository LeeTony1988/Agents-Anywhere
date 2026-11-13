package com.agentsanywhere.app.feature.remotedesktop

/**
 * How long the viewer keeps the room after the app leaves the foreground
 * (`docs/remote-desktop.md` section 8.3: "Android 进入 onStop 后停止渲染但保持 30 s 重连窗口").
 *
 * Inside the window the LiveKit SDK reconnects on its own and the viewer only has to attach
 * the renderer again; after it the session is ended, because a background room nobody is
 * watching still costs the host an encoder and the user a controller lease.
 */
const val REMOTE_DESKTOP_BACKGROUND_GRACE_MILLIS: Long = 30_000L

/** What the screen does about one lifecycle event. */
enum class RemoteDesktopLifecycleAction {
    /** Stop drawing but keep the room: the renderer is detached, the session stays. */
    DetachRenderer,

    /** Still inside the grace window: attach the renderer again and ask for a keyframe. */
    ReattachRenderer,

    /** The grace window is over: end the session on the Server. */
    EndSession,

    /** Pause input: send exactly one `release_all {reason: "pagehide"}`. */
    ReleaseInput,
}

/**
 * The 30 s background rule as a pure state machine with an injected clock.
 *
 * `onStop`/`onStart` are the activity lifecycle, `onPauseInput` covers the screen locking or
 * a call arriving while the app is still foregrounded. Input is released once per pause
 * cycle: a second `onPauseInput` without an intervening resume is a no-op, so a burst of
 * lifecycle events cannot flood `aa.input`.
 */
class RemoteDesktopLifecyclePolicy(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val graceMillis: Long = REMOTE_DESKTOP_BACKGROUND_GRACE_MILLIS,
) {
    private var backgroundedAt: Long? = null
    private var inputReleased = false

    /** True between [onStop] and the next [onStart]. */
    val isBackgrounded: Boolean
        get() = backgroundedAt != null

    /** How long the app has been in the background, or null while it is foregrounded. */
    val backgroundedForMillis: Long?
        get() = backgroundedAt?.let { (clock() - it).coerceAtLeast(0L) }

    fun onStop(): List<RemoteDesktopLifecycleAction> {
        backgroundedAt = clock()
        val actions = mutableListOf(RemoteDesktopLifecycleAction.DetachRenderer)
        if (!inputReleased) {
            inputReleased = true
            actions += RemoteDesktopLifecycleAction.ReleaseInput
        }
        return actions
    }

    /** Resumes inside the window, or asks for the session to end once it has passed. */
    fun onStart(): List<RemoteDesktopLifecycleAction> {
        val since = backgroundedAt ?: return emptyList()
        backgroundedAt = null
        inputReleased = false
        val elapsed = (clock() - since).coerceAtLeast(0L)
        return if (elapsed >= graceMillis) {
            listOf(RemoteDesktopLifecycleAction.EndSession)
        } else {
            listOf(RemoteDesktopLifecycleAction.ReattachRenderer)
        }
    }

    /** Screen off or an incoming call: input pauses even though the app may still be started. */
    fun onPauseInput(): List<RemoteDesktopLifecycleAction> {
        if (inputReleased) return emptyList()
        inputReleased = true
        return listOf(RemoteDesktopLifecycleAction.ReleaseInput)
    }

    /** Forgets the window, for a new session on the same screen. */
    fun reset() {
        backgroundedAt = null
        inputReleased = false
    }
}

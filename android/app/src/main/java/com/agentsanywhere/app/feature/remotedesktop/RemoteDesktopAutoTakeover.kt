package com.agentsanywhere.app.feature.remotedesktop

/**
 * Decides whether a newly live viewer should request control automatically.
 *
 * The viewer opens in control mode, and the create response usually already makes this
 * client the controller. The automatic takeover must therefore be keyed by **Server
 * session id**, not by "currently not controlling": a manual release also flips
 * `controlling` to false, and treating that as a fresh session made the first
 * 「释放控制」 immediately take control again.
 */
internal class RemoteDesktopAutoTakeover {
    private var handledSessionId: String? = null

    /** Returns true only the first time [sessionId] becomes live and this client is not controller. */
    fun shouldTakeControl(sessionId: String, controlling: Boolean): Boolean {
        if (handledSessionId == sessionId) return false
        handledSessionId = sessionId
        return !controlling
    }
}

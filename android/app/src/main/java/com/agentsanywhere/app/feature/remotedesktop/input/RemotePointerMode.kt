package com.agentsanywhere.app.feature.remotedesktop.input

/**
 * How a one-finger gesture maps to the remote mouse.
 *
 * [Direct] keeps the touchscreen metaphor: tap where you want to click, drag where you
 * want to select. [Mouse] shows a compact translucent trackpad in the bottom-right
 * corner: swipe inside it to move the cursor without holding a button, tap to click at
 * the cursor, hold the cursor ring to drag with the left button held (the ring turns
 * light blue), drag its right rail to scroll, and double tap to toggle the drag lock.
 * The picture itself stays direct-touch. Phone users need the second mode for small
 * targets and text selection; the two modes are exposed as a single toggle in the
 * control bar.
 */
enum class RemotePointerMode {
    Direct,
    Mouse,
}

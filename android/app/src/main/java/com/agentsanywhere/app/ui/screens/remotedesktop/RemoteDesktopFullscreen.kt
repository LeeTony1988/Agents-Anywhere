package com.agentsanywhere.app.ui.screens.remotedesktop

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Hides the system bars while the viewer is in landscape, so the remote screen uses the
 * whole display. The bars come back when the viewer leaves fullscreen or leaves the screen.
 */
@Composable
internal fun RemoteDesktopImmersiveBars(hidden: Boolean) {
    val view = LocalView.current
    val activity = view.context.findActivity() ?: return
    DisposableEffect(hidden, activity, view) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        if (hidden) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Requests a specific orientation from the hosting activity. */
internal fun Context.requestViewerOrientation(landscape: Boolean) {
    val activity = findActivity() ?: return
    activity.requestedOrientation = if (landscape) {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    } else {
        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

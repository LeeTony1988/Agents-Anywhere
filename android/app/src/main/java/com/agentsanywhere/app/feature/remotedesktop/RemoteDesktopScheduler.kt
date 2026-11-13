package com.agentsanywhere.app.feature.remotedesktop

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Handle of one pending [RemoteDesktopScheduler] block; [cancel] makes it never run. */
fun interface RemoteDesktopCancellable {
    fun cancel()

    companion object {
        val None: RemoteDesktopCancellable = RemoteDesktopCancellable { }
    }
}

/**
 * Delayed execution seam for the remote desktop lifecycle (heartbeat every 20 s, token
 * renewal, socket reconnect backoff). Injected so tests can advance time instead of
 * sleeping: a fake scheduler runs the due blocks when the test says so.
 */
fun interface RemoteDesktopScheduler {
    fun schedule(delayMillis: Long, block: () -> Unit): RemoteDesktopCancellable
}

/**
 * Production scheduler: a single daemon thread, so a blocked heartbeat or token call can
 * never delay the next one and nothing keeps the process alive.
 */
class ThreadRemoteDesktopScheduler(
    threadName: String = "remote-desktop-timer",
) : RemoteDesktopScheduler, AutoCloseable {
    private val threadFactory = java.util.concurrent.ThreadFactory { runnable ->
        Thread(runnable, "$threadName-${THREAD_INDEX.getAndIncrement()}").apply { isDaemon = true }
    }
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(threadFactory)

    override fun schedule(delayMillis: Long, block: () -> Unit): RemoteDesktopCancellable {
        if (executor.isShutdown) return RemoteDesktopCancellable.None
        val future: ScheduledFuture<*> = executor.schedule(
            { runCatching(block) },
            delayMillis.coerceAtLeast(0L),
            TimeUnit.MILLISECONDS,
        )
        return RemoteDesktopCancellable { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }

    private companion object {
        val THREAD_INDEX = AtomicInteger(0)
    }
}

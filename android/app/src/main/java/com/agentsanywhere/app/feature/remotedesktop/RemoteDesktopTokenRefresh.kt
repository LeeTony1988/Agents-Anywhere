package com.agentsanywhere.app.feature.remotedesktop

import com.agentsanywhere.app.api.RemoteDesktopMediaGrant
import java.time.Instant
import java.time.OffsetDateTime

/**
 * LiveKit validates a join token only when a participant joins, so an expired
 * token means a full rejoin. Viewer tokens live 600 s and controller tokens 120 s
 * (docs/api/remote-desktop.md), therefore the client renews with 120 s to spare.
 */
const val REMOTE_DESKTOP_TOKEN_REFRESH_THRESHOLD_MILLIS: Long = 120_000L

/**
 * Milliseconds until the media token should be renewed, measured from [nowMillis].
 *
 * The result is 0 once the token is inside its last [thresholdMillis] (including
 * exactly at the threshold) or already expired, so callers can `delay(...)` and
 * renew immediately. A missing or unparseable `tokenExpiresAt` also yields 0:
 * renewing early is cheap, joining with a dead token is not.
 */
fun tokenRefreshDelayMillis(
    expiresAt: String?,
    nowMillis: Long,
    thresholdMillis: Long = REMOTE_DESKTOP_TOKEN_REFRESH_THRESHOLD_MILLIS,
): Long {
    val expiresAtMillis = parseRemoteDesktopTimestampMillis(expiresAt) ?: return 0L
    val remainingMillis = expiresAtMillis - nowMillis
    return (remainingMillis - thresholdMillis).coerceAtLeast(0L)
}

fun tokenRefreshDelayMillis(
    grant: RemoteDesktopMediaGrant,
    nowMillis: Long,
    thresholdMillis: Long = REMOTE_DESKTOP_TOKEN_REFRESH_THRESHOLD_MILLIS,
): Long = tokenRefreshDelayMillis(grant.tokenExpiresAt, nowMillis, thresholdMillis)

fun shouldRefreshToken(
    expiresAt: String?,
    nowMillis: Long,
    thresholdMillis: Long = REMOTE_DESKTOP_TOKEN_REFRESH_THRESHOLD_MILLIS,
): Boolean = tokenRefreshDelayMillis(expiresAt, nowMillis, thresholdMillis) == 0L

/** RFC 3339 UTC timestamp to epoch milliseconds; both `...Z` and `+00:00` forms are accepted. */
fun parseRemoteDesktopTimestampMillis(value: String?): Long? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    runCatching { Instant.parse(text) }.getOrNull()?.let { return it.toEpochMilli() }
    return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
}

"""Deadline sweeper for remote desktop sessions.

PostgreSQL is the source of truth, so every transition is a conditional UPDATE
(``state IN (...) AND ended_at IS NULL``). Several Server workers may run this
loop concurrently; exactly one of them wins a given transition. Redis offers no
deadline notifications of its own and its keys expire without running code, so
nothing here depends on Redis TTL to advance a session.
"""

from __future__ import annotations

import asyncio
from collections.abc import Callable
from datetime import UTC, datetime
from typing import Protocol

from loguru import logger

from agent_server.core.remote_desktop import (
    IDLE_TIMEOUT_SECONDS,
    INTERRUPTED_GRACE_SECONDS,
    MAX_SESSION_SECONDS,
    STATE_AWAITING_CONSENT,
    STATE_DEGRADED,
    STATE_INTERRUPTED,
    STATE_LIVE,
    RemoteDesktopSession,
    parse_iso,
)

# Highest priority first. A session that crossed several deadlines at once gets
# the first matching reason, which keeps two workers from racing on different
# reasons for the same row.
MAINTENANCE_INTERVAL_SECONDS = 5.0


class RemoteDesktopMaintenanceStore(Protocol):
    async def list_active_remote_desktop_sessions(
        self, limit: int = 500
    ) -> list[RemoteDesktopSession]: ...


class RemoteDesktopMaintenanceService(Protocol):
    async def end_from_system(
        self, session_id: str, reason: str
    ) -> RemoteDesktopSession | None: ...

    async def expire_controller(self, session_id: str) -> bool: ...


class _Presence(Protocol):
    async def participants_seen(self, session_id: str) -> dict[str, float]: ...


class RemoteDesktopMaintenanceLoop:
    def __init__(
        self,
        store: RemoteDesktopMaintenanceStore,
        service: RemoteDesktopMaintenanceService,
        *,
        presence: _Presence | None = None,
        interval_seconds: float = MAINTENANCE_INTERVAL_SECONDS,
        idle_timeout_seconds: float = IDLE_TIMEOUT_SECONDS,
        now: Callable[[], datetime] | None = None,
    ) -> None:
        self._store = store
        self._service = service
        self._presence = presence
        self._interval_seconds = interval_seconds
        self._idle_timeout_seconds = idle_timeout_seconds
        self._now = now or (lambda: datetime.now(UTC))
        self._task: asyncio.Task[None] | None = None

    async def start(self) -> None:
        if self._task is None:
            self._task = asyncio.create_task(
                self._run(), name="remote-desktop-maintenance"
            )

    async def close(self) -> None:
        if self._task is not None:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            self._task = None

    async def _run(self) -> None:
        while True:
            try:
                await self.tick()
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001 - a bad row must not stop the loop
                logger.exception("remote desktop maintenance tick failed")
            await asyncio.sleep(self._interval_seconds)

    async def tick(self) -> int:
        """Advance every due session once; returns the number of actions."""
        sessions = await self._store.list_active_remote_desktop_sessions(limit=500)
        now = self._now()
        acted = 0
        for session in sessions:
            try:
                if await self._advance(session, now):
                    acted += 1
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001 - one session must not block the rest
                logger.exception(
                    "remote desktop maintenance failed session_id={}", session.id
                )
        return acted

    async def _advance(self, session: RemoteDesktopSession, now: datetime) -> bool:
        # 8 h absolute cap beats every other deadline.
        started = parse_iso(session.startedAt or session.createdAt)
        if started is not None and (now - started).total_seconds() >= MAX_SESSION_SECONDS:
            await self._service.end_from_system(session.id, "duration_limit")
            return True

        if session.state == STATE_AWAITING_CONSENT:
            deadline = parse_iso(session.consentDeadlineAt)
            if deadline is not None and (now - deadline).total_seconds() >= 0:
                await self._service.end_from_system(session.id, "consent_timeout")
                return True
            return False

        if session.state == STATE_INTERRUPTED:
            interrupted = parse_iso(session.interruptedAt)
            if (
                interrupted is not None
                and (now - interrupted).total_seconds() >= INTERRUPTED_GRACE_SECONDS
            ):
                reason = "connector_offline"
                await self._service.end_from_system(session.id, reason)
                return True
            return False

        if session.state in {STATE_LIVE, STATE_DEGRADED}:
            # Expired lease only drops control; it never ends the session.
            lease = parse_iso(session.controllerLeaseExpiresAt)
            if (
                session.controller
                and lease is not None
                and (now - lease).total_seconds() >= 0
                and await self._service.expire_controller(session.id)
            ):
                return True

            if await self._idle(session, now):
                await self._service.end_from_system(session.id, "idle_timeout")
                return True
        return False

    async def _idle(self, session: RemoteDesktopSession, now: datetime) -> bool:
        if session.controller:
            return False
        if not await self._no_viewers(session):
            return False
        last_activity = (
            session.lastInputAt
            or session.lastViewerLeftAt
            or session.startedAt
            or session.createdAt
        )
        parsed = parse_iso(last_activity)
        if parsed is None:
            return False
        return (now - parsed).total_seconds() >= self._idle_timeout_seconds

    async def _no_viewers(self, session: RemoteDesktopSession) -> bool:
        if self._presence is None:
            return not session.participants
        seen = await self._presence.participants_seen(session.id)
        if seen:
            return False
        # A live DB participant list may still be fresh even when Redis was
        # flushed; treat any participant seen in the last presence window as
        # present so a restart cannot end an active session prematurely.
        now = self._now()
        for participant in session.participants:
            seen_at = parse_iso(participant.lastSeenAt)
            if seen_at is not None and (now - seen_at).total_seconds() <= 90:
                return False
        return True



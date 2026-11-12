from __future__ import annotations

import asyncio
from datetime import UTC, datetime

from agent_server.core.remote_desktop import RemoteDesktopSession
from agent_server.services.remote_desktop_maintenance import (
    RemoteDesktopMaintenanceLoop,
)


def _session(**overrides):
    base = {
        "id": "rds_01JABCDEFGH",
        "connectorId": "conn_x",
        "userId": "user",
        "mode": "control",
        "state": "live",
        "mediaProvider": "livekit",
        "createdAt": "2026-11-13T00:00:00Z",
        "updatedAt": "2026-11-13T00:00:00Z",
    }
    base.update(overrides)
    return RemoteDesktopSession(**base)


class FakeStore:
    def __init__(self, sessions):
        self.sessions = sessions

    async def list_active_remote_desktop_sessions(self, limit=500):
        return list(self.sessions)


class FakeService:
    def __init__(self):
        self.ended = []
        self.expired = []

    async def end_from_system(self, session_id, reason):
        self.ended.append((session_id, reason))

    async def expire_controller(self, session_id):
        self.expired.append(session_id)
        return True


class FakePresence:
    def __init__(self, seen=None):
        self.seen = seen or {}

    async def participants_seen(self, session_id):
        return self.seen.get(session_id, {})


def _loop(sessions, now, presence=None):
    service = FakeService()
    loop = RemoteDesktopMaintenanceLoop(
        FakeStore(sessions),
        service,
        presence=presence or FakePresence(),
        now=lambda: now,
    )
    return loop, service


def test_consent_timeout_ends_session() -> None:
    now = datetime(2026, 11, 13, 0, 1, 1, tzinfo=UTC)
    session = _session(
        state="awaiting_consent",
        consentDeadlineAt="2026-11-13T00:01:00Z",
    )
    loop, service = _loop([session], now)
    assert asyncio.run(loop.tick()) == 1
    assert service.ended == [("rds_01JABCDEFGH", "consent_timeout")]


def test_duration_limit_beats_consent_timeout() -> None:
    now = datetime(2026, 11, 13, 8, 0, 1, tzinfo=UTC)
    session = _session(
        state="awaiting_consent",
        startedAt="2026-11-13T00:00:00Z",
        consentDeadlineAt="2026-11-13T00:01:00Z",
    )
    loop, service = _loop([session], now)
    asyncio.run(loop.tick())
    assert service.ended == [("rds_01JABCDEFGH", "duration_limit")]


def test_interrupted_grace_ends_session() -> None:
    now = datetime(2026, 11, 13, 0, 2, 31, tzinfo=UTC)
    session = _session(
        state="interrupted",
        interruptedAt="2026-11-13T00:01:00Z",
    )
    loop, service = _loop([session], now)
    asyncio.run(loop.tick())
    assert service.ended == [("rds_01JABCDEFGH", "connector_offline")]


def test_expired_lease_is_cleared_without_ending() -> None:
    now = datetime(2026, 11, 13, 0, 2, 0, tzinfo=UTC)
    session = _session(
        controller="viewer:pt_abcdefghijklmnopqrstuvwx",
        controllerLeaseExpiresAt="2026-11-13T00:01:00Z",
    )
    loop, service = _loop([session], now)
    assert asyncio.run(loop.tick()) == 1
    assert service.expired == ["rds_01JABCDEFGH"]
    assert service.ended == []


def test_idle_requires_no_controller_and_no_viewers() -> None:
    now = datetime(2026, 11, 13, 0, 15, 1, tzinfo=UTC)
    idle = _session(
        startedAt="2026-11-13T00:00:00Z",
        lastInputAt="2026-11-13T00:00:01Z",
    )
    loop, service = _loop([idle], now)
    asyncio.run(loop.tick())
    assert service.ended == [("rds_01JABCDEFGH", "idle_timeout")]

    busy = _session(
        startedAt="2026-11-13T00:00:00Z",
        lastInputAt="2026-11-13T00:00:01Z",
        controller="viewer:pt_abcdefghijklmnopqrstuvwx",
        controllerLeaseExpiresAt="2026-11-13T00:20:00Z",
    )
    loop, service = _loop([busy], now)
    asyncio.run(loop.tick())
    assert service.ended == []


def test_healthy_session_is_left_alone() -> None:
    now = datetime(2026, 11, 13, 0, 1, 0, tzinfo=UTC)
    loop, service = _loop([_session(startedAt="2026-11-13T00:00:00Z")], now)
    assert asyncio.run(loop.tick()) == 0
    assert service.ended == [] and service.expired == []

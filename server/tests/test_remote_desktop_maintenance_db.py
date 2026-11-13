"""Two workers advancing the same deadline against the real database.

The maintenance loop relies on PostgreSQL conditional updates, not on a lock:
every worker runs the same sweep and at most one of them wins a transition.
These tests start two independent loops (each with its own service instance)
over one shared Store and assert the session still ends exactly once.
"""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from typing import Any

from agent_server.core.device_settings import ConnectorDeviceSettings
from agent_server.core.remote_desktop import RemoteDesktopParticipant
from agent_server.infra.repositories.facade import Store
from agent_server.services.remote_desktop import RemoteDesktopService
from agent_server.services.remote_desktop_maintenance import (
    RemoteDesktopMaintenanceLoop,
)
from agent_server.services.remote_desktop_media import NoopMediaProvider


class _NoopRequester:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str, dict[str, Any]]] = []

    async def request(
        self,
        connector_id: str,
        method: str,
        params: dict[str, Any],
        *,
        timeout: float = 30,
    ) -> Any:
        self.calls.append((connector_id, method, params))
        return {"stopped": True}


class _NoopBroker:
    async def publish_event(self, *args: Any, **kwargs: Any) -> str:
        return "1"

    async def get_json(self, key: str) -> Any | None:
        return None

    async def set_json(self, key: str, value: Any, *, ttl_seconds: int) -> None:
        return None

    async def add_stats(self, session_id: str, stats: dict[str, Any]) -> None:
        return None

    async def stats_summary(self, session_id: str) -> dict[str, Any] | None:
        return None

    async def participants_seen(self, session_id: str) -> dict[str, float]:
        return {}

    async def rate_limit(
        self, key: str, *, limit: int, window_seconds: int
    ) -> int:
        return 0


class _NoopTickets:
    async def issue(self, **kwargs: Any) -> tuple[str, str]:
        return "wst_test", "2026-11-13T00:10:00Z"


def _service(store: Store, requester: _NoopRequester) -> RemoteDesktopService:
    return RemoteDesktopService(
        store,
        requester,  # type: ignore[arg-type]
        NoopMediaProvider(),
        _NoopBroker(),  # type: ignore[arg-type]
        _NoopTickets(),  # type: ignore[arg-type]
        lambda connector_id: _settings(),
    )


def _settings() -> ConnectorDeviceSettings:
    return ConnectorDeviceSettings(
        remoteDesktopEnabled=True,
        remoteDesktopAllowControl=True,
        remoteDesktopRequireConsent=True,
    )


def _participant() -> RemoteDesktopParticipant:
    return RemoteDesktopParticipant(
        participantId="pt_a",
        userId="user",
        platform="windows",
        createdAt="2026-11-13T00:00:00Z",
        lastSeenAt="2026-11-13T00:00:00Z",
    )


def _clock(now: datetime):
    return lambda: now.isoformat().replace("+00:00", "Z")


def test_two_workers_end_an_expired_consent_session_once() -> None:
    store = Store()
    requester = _NoopRequester()
    now = datetime(2026, 11, 13, 0, 5, 0, tzinfo=UTC)

    async def run() -> None:
        session = await store.create_remote_desktop_session(
            connector_id="conn_race",
            connector_name="Race",
            user_id="user",
            created_by_participant_id="pt_a",
            mode="control",
            display_id="0",
            quality={"maxFps": 30, "maxBitrateKbps": 4000},
            media_provider="noop",
            participants=[_participant().model_dump()],
            state="awaiting_consent",
            consent_deadline_at="2026-11-13T00:01:00Z",
        )
        worker_a = RemoteDesktopMaintenanceLoop(
            store,
            _service(store, requester),
            now=_clock(now),
        )
        worker_b = RemoteDesktopMaintenanceLoop(
            store,
            _service(store, requester),
            now=_clock(now),
        )
        acted = await asyncio.gather(worker_a.tick(), worker_b.tick())
        assert sum(acted) == 1, acted

        loaded = await store.get_remote_desktop_session(session.id)
        assert loaded.state == "ended"
        assert loaded.endReason == "consent_timeout"
        # Only the winner asked the connector to stop.
        stops = [call for call in requester.calls if call[1] == "remoteDesktop.stop"]
        assert len(stops) == 1

        # A later sweep sees nothing left to do.
        assert await worker_a.tick() == 0


def test_second_worker_cannot_end_an_already_ended_session() -> None:
    store = Store()

    async def run() -> None:
        session = await store.create_remote_desktop_session(
            connector_id="conn_end_race",
            connector_name="Race",
            user_id="user",
            created_by_participant_id="pt_a",
            mode="view",
            display_id="0",
            quality={"maxFps": 30, "maxBitrateKbps": 4000},
            media_provider="noop",
            participants=[_participant().model_dump()],
            state="starting",
        )
        ended = await asyncio.gather(
            store.end_remote_desktop_session(
                session.id, reason="client_ended", ended_at="2026-11-13T00:00:10Z"
            ),
            store.end_remote_desktop_session(
                session.id, reason="host_ended", ended_at="2026-11-13T00:00:11Z"
            ),
        )
        assert sorted(ended) == [False, True]
        loaded = await store.get_remote_desktop_session(session.id)
        assert loaded.state == "ended"
        # The loser's reason never overwrote the winner's.
        assert loaded.endReason == "client_ended"


def test_active_sweep_lists_only_unfinished_sessions() -> None:
    store = Store()

    async def run() -> None:
        active = await store.create_remote_desktop_session(
            connector_id="conn_active",
            connector_name="Active",
            user_id="user",
            created_by_participant_id="pt_a",
            mode="view",
            display_id="0",
            quality={"maxFps": 30, "maxBitrateKbps": 4000},
            media_provider="noop",
            participants=[_participant().model_dump()],
            state="live",
            started_at="2026-11-13T00:00:00Z",
        )
        finished = await store.create_remote_desktop_session(
            connector_id="conn_finished",
            connector_name="Finished",
            user_id="user",
            created_by_participant_id="pt_a",
            mode="view",
            display_id="0",
            quality={"maxFps": 30, "maxBitrateKbps": 4000},
            media_provider="noop",
            participants=[_participant().model_dump()],
            state="starting",
        )
        await store.end_remote_desktop_session(
            finished.id, reason="client_ended", ended_at="2026-11-13T00:00:10Z"
        )
        sessions = await store.list_active_remote_desktop_sessions(limit=100)
        ids = {session.id for session in sessions}
        assert active.id in ids
        assert finished.id not in ids

    asyncio.run(run())

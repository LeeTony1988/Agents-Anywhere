from __future__ import annotations

import asyncio
import time
from typing import Any

import pytest

from agent_server.core.device_settings import ConnectorDeviceSettings
from agent_server.core.remote_desktop import (
    HostFacts,
    RemoteDesktopLimitError,
    RemoteDesktopParticipant,
    RemoteDesktopQuality,
    RemoteDesktopSession,
    RemoteDesktopStats,
    viewer_identity,
)
from agent_server.services.remote_desktop import RemoteDesktopService
from agent_server.services.remote_desktop_media import MediaGrant
from agent_server.services.remote_desktop_notifications import (
    RemoteDesktopNotificationHandler,
)


class RpcFailure(Exception):
    def __init__(self, code: str = "rd_host_not_running") -> None:
        super().__init__(code)
        self.code = code


class FakeConnector:
    def __init__(self, connector_id: str = "conn_1", user_id: str = "user_1") -> None:
        self.id = connector_id
        self.name = "Test device"
        self.userId = user_id


class FakeStore:
    def __init__(self) -> None:
        self.connector = FakeConnector()
        self.sessions: dict[str, RemoteDesktopSession] = {}
        self.summaries: dict[str, dict[str, Any]] = {}

    async def get_connector(self, connector_id: str) -> FakeConnector:
        if connector_id != self.connector.id:
            raise KeyError(connector_id)
        return self.connector

    async def get_connector_device_settings(self, connector_id: str) -> dict[str, Any]:
        return {}

    async def create_remote_desktop_session(self, **kwargs: Any) -> RemoteDesktopSession:
        session = RemoteDesktopSession(
            id=kwargs["session_id"],
            connectorId=kwargs["connector_id"],
            connectorName=kwargs.get("connector_name"),
            userId=kwargs["user_id"],
            createdByParticipantId=kwargs.get("created_by_participant_id"),
            mode=kwargs["mode"],
            displayId=kwargs.get("display_id"),
            state=kwargs["state"],
            mediaProvider=kwargs["media_provider"],
            mediaRoom=kwargs.get("media_room"),
            mediaUrl="wss://rtc.example.com" if kwargs.get("media_room") else None,
            quality=RemoteDesktopQuality.model_validate(kwargs["quality"]),
            participants=[
                RemoteDesktopParticipant.model_validate(item)
                for item in kwargs.get("participants", [])
            ],
            consentDeadlineAt=kwargs.get("consent_deadline_at"),
            startedAt=kwargs.get("started_at"),
            linkedSessionId=kwargs.get("linked_session_id"),
            createdAt="2026-11-13T00:00:00Z",
            updatedAt="2026-11-13T00:00:00Z",
            viewerCount=len(kwargs.get("participants", [])),
        )
        self.sessions[session.id] = session
        return session.model_copy(deep=True)

    async def get_remote_desktop_session(
        self, session_id: str, *, user_id: str | None = None, for_update: bool = False
    ) -> RemoteDesktopSession:
        session = self.sessions.get(session_id)
        if session is None or (user_id is not None and session.userId != user_id):
            raise KeyError(session_id)
        return session.model_copy(deep=True)

    async def find_active_remote_desktop_session(
        self, connector_id: str
    ) -> RemoteDesktopSession | None:
        for session in self.sessions.values():
            if session.connectorId == connector_id and session.state != "ended":
                return session.model_copy(deep=True)
        return None

    async def list_remote_desktop_sessions(
        self,
        *,
        user_id: str,
        connector_id: str | None = None,
        state: str | None = None,
        limit: int = 50,
        cursor: str | None = None,
    ) -> tuple[list[RemoteDesktopSession], str | None]:
        sessions = [
            item
            for item in self.sessions.values()
            if item.userId == user_id
            and (connector_id is None or item.connectorId == connector_id)
            and (state is None or item.state == state)
        ]
        return [item.model_copy(deep=True) for item in sessions[:limit]], None

    def _apply(self, session: RemoteDesktopSession, fields: dict[str, Any]) -> None:
        if "quality" in fields:
            session.quality = RemoteDesktopQuality.model_validate(fields["quality"])
        if "stats" in fields:
            session.stats = (
                RemoteDesktopStats.model_validate(fields["stats"])
                if fields["stats"] is not None
                else None
            )
        if "error" in fields:
            from agent_server.core.remote_desktop import RemoteDesktopErrorInfo

            session.error = (
                RemoteDesktopErrorInfo.model_validate(fields["error"])
                if fields["error"] is not None
                else None
            )
        for key, value in fields.items():
            if key in {"quality", "stats", "error"}:
                continue
            if hasattr(session, key):
                setattr(session, key, value)

    async def update_remote_desktop_session(
        self, session_id: str, **fields: Any
    ) -> RemoteDesktopSession:
        session = self.sessions[session_id]
        self._apply(session, fields)
        session.updatedAt = "2026-11-13T00:01:00Z"
        return session.model_copy(deep=True)

    async def transition_remote_desktop_session(
        self,
        session_id: str,
        *,
        expected_states: set[str],
        target_state: str,
        **fields: Any,
    ) -> bool:
        session = self.sessions[session_id]
        if session.state not in expected_states or session.state == "ended":
            return False
        if target_state != session.state:
            from agent_server.core.remote_desktop import validate_state_transition

            validate_state_transition(session.state, target_state)
        session.state = target_state
        self._apply(session, fields)
        return True

    async def end_remote_desktop_session(
        self, session_id: str, *, reason: str, ended_at: str
    ) -> bool:
        session = self.sessions[session_id]
        if session.endedAt is not None:
            return False
        session.state = "ended"
        session.endReason = reason
        session.endedAt = ended_at
        return True

    async def update_remote_desktop_participants(
        self, session_id: str, participants: list[Any]
    ) -> bool:
        session = self.sessions[session_id]
        session.participants = [
            item
            if isinstance(item, RemoteDesktopParticipant)
            else RemoteDesktopParticipant.model_validate(item)
            for item in participants
        ]
        return True

    async def save_remote_desktop_stats_summary(
        self, session_id: str, summary: dict[str, Any]
    ) -> None:
        self.summaries[session_id] = summary


class FakeRequester:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str, dict[str, Any], float]] = []
        self.fail_methods: set[str] = set()
        self.screenshot_result: dict[str, Any] = {
            "width": 1280,
            "height": 720,
            "capturedAt": "2026-11-13T00:00:00Z",
            "dataBase64": "aGVsbG8=",
        }

    async def request(
        self,
        connector_id: str,
        method: str,
        params: dict[str, Any],
        *,
        timeout: float = 30,
    ) -> Any:
        self.calls.append((connector_id, method, params, timeout))
        if method in self.fail_methods:
            raise RpcFailure()
        if method == "remoteDesktop.hostInfo":
            return HostFacts(
                supported=True,
                available=True,
                platform="windows",
                arch="x86_64",
                hostVersion="1.0.0",
                maxFps=60,
                activeSessions=0,
            ).model_dump()
        if method == "remoteDesktop.start":
            return {"accepted": True, "state": "starting"}
        if method == "remoteDesktop.screenshot":
            return dict(self.screenshot_result)
        return {"applied": True}


class FakeMedia:
    available = True
    name = "livekit"
    join_url = "wss://rtc.example.com"
    viewer_token_ttl_seconds = 600
    controller_token_ttl_seconds = 120

    def __init__(self) -> None:
        self.grants: list[tuple[str, str, int]] = []
        self.permission_updates: list[tuple[str, str, bool]] = []
        self.deleted_rooms: list[str] = []

    async def create_grant(
        self, session: Any, participant_id: str, *, role: str, ttl_seconds: int
    ) -> MediaGrant:
        self.grants.append((participant_id, role, ttl_seconds))
        return MediaGrant(
            provider="livekit",
            url=self.join_url,
            room=session.mediaRoom or f"rd_{session.id}",
            identity=viewer_identity(participant_id),
            token=f"{role}-token",
            tokenExpiresAt="2026-11-13T01:00:00Z",
            canPublishData=role == "controller",
        )

    async def create_host_grant(
        self, session: Any, connector_id: str, *, ttl_seconds: int = 8 * 60 * 60
    ) -> MediaGrant:
        return MediaGrant(
            provider="livekit",
            url=self.join_url,
            room=session.mediaRoom or f"rd_{session.id}",
            identity=f"host:{connector_id}",
            token="host-token",
            tokenExpiresAt="2026-11-13T01:00:00Z",
            canPublishData=True,
        )

    async def update_can_publish_data(
        self, session: Any, identity: str, allowed: bool
    ) -> None:
        self.permission_updates.append((identity, "publishData", allowed))

    async def delete_room(self, room: str) -> None:
        self.deleted_rooms.append(room)

    async def remove_participant(self, room: str, identity: str) -> None:
        return None


class FakeBroker:
    def __init__(self) -> None:
        self.events: list[tuple[str, dict[str, Any], str | None]] = []
        self.values: dict[str, Any] = {}
        self.counters: dict[str, int] = {}
        self.rate_counts: dict[str, int] = {}
        self.presence: dict[tuple[str, str], float] = {}
        self.stats: dict[str, list[dict[str, Any]]] = {}

    async def publish_event(
        self,
        session_id: str,
        event: dict[str, Any],
        *,
        target_participant: str | None = None,
    ) -> str:
        self.events.append((session_id, event, target_participant))
        return str(len(self.events))

    async def get_json(self, key: str) -> Any | None:
        return self.values.get(key)

    async def set_json(self, key: str, value: Any, *, ttl_seconds: int) -> None:
        self.values[key] = value

    async def increment(self, key: str, *, ttl_seconds: int | None = None) -> int:
        self.counters[key] = self.counters.get(key, 0) + 1
        return self.counters[key]

    async def rate_limit(
        self, key: str, *, limit: int, window_seconds: int
    ) -> int:
        count = self.rate_counts.get(key, 0) + 1
        self.rate_counts[key] = count
        return 0 if count <= limit else 2000

    async def add_stats(self, session_id: str, stats: dict[str, Any]) -> None:
        self.stats.setdefault(session_id, []).append(stats)

    async def stats_summary(self, session_id: str) -> dict[str, Any] | None:
        return {"samples": 1, "avgFps": 30.0}

    async def set_presence(
        self, session_id: str, participant_id: str, *, ttl_seconds: int = 90
    ) -> None:
        self.presence[(session_id, participant_id)] = 1.0

    async def clear_presence(self, session_id: str, participant_id: str) -> None:
        self.presence.pop((session_id, participant_id), None)

    async def participants_seen(self, session_id: str) -> dict[str, float]:
        return {
            participant_id: value
            for (seen_session, participant_id), value in self.presence.items()
            if seen_session == session_id
        }


class FakeTickets:
    def __init__(self) -> None:
        self.issued: list[dict[str, Any]] = []

    async def issue(
        self,
        *,
        user_id: str,
        client_id: str,
        session_id: str | None = None,
        scope: str | None = None,
    ) -> tuple[str, str]:
        self.issued.append(
            {
                "user_id": user_id,
                "client_id": client_id,
                "scope": scope,
            }
        )
        return "wst_test", "2026-11-13T01:00:00Z"


def _settings(**overrides: Any) -> ConnectorDeviceSettings:
    values = {
        "remoteDesktopEnabled": True,
        "remoteDesktopAllowControl": True,
        "remoteDesktopRequireConsent": False,
        "remoteDesktopAllowUnattended": True,
    }
    values.update(overrides)
    return ConnectorDeviceSettings(**values)


def _service(
    *,
    settings: ConnectorDeviceSettings | None = None,
    store: FakeStore | None = None,
    requester: FakeRequester | None = None,
    media: FakeMedia | None = None,
    broker: FakeBroker | None = None,
    tickets: FakeTickets | None = None,
) -> tuple[
    RemoteDesktopService, FakeStore, FakeRequester, FakeMedia, FakeBroker, FakeTickets
]:
    store = store or FakeStore()
    requester = requester or FakeRequester()
    media = media or FakeMedia()
    broker = broker or FakeBroker()
    tickets = tickets or FakeTickets()
    resolved_settings = settings or _settings()
    service = RemoteDesktopService(
        store,
        requester,
        media,
        broker,
        tickets,
        lambda _connector_id: asyncio.sleep(0, result=resolved_settings),
        max_viewers_per_session=3,
    )
    return service, store, requester, media, broker, tickets


def test_create_view_issues_viewer_grant_and_starts_host() -> None:
    service, _, requester, media, broker, tickets = _service()

    result = asyncio.run(
        service.create_or_join(
            "conn_1",
            "user_1",
            {"connectorId": "conn_1", "mode": "view"},
            client_platform="web",
            app_version="2.3.0",
        )
    )

    assert result["created"] is True
    assert result["session"]["state"] == "starting"
    assert result["media"]["canPublishData"] is False
    assert result["media"]["identity"] == f"viewer:{result['participantId']}"
    assert result["ws"]["ticket"] == "wst_test"
    start = next(call for call in requester.calls if call[1] == "remoteDesktop.start")
    assert start[2]["media"]["identity"] == "host:conn_1"
    assert start[2]["media"]["token"] == "host-token"
    assert start[2]["consent"]["required"] is False
    assert start[2]["controllerIdentity"] is None
    assert tickets.issued[0]["client_id"] == result["participantId"]
    assert media.grants[0][1] == "viewer"
    assert broker.events == []


def test_control_waits_for_consent_without_media() -> None:
    service, _, requester, media, _, _ = _service(
        settings=_settings(remoteDesktopRequireConsent=True, remoteDesktopAllowUnattended=False)
    )

    result = asyncio.run(
        service.create_or_join(
            "conn_1",
            "user_1",
            {"connectorId": "conn_1", "mode": "control"},
            client_platform="desktop",
            app_version="2.3.0",
        )
    )

    assert result["session"]["state"] == "awaiting_consent"
    assert result["session"]["consentDeadlineAt"] is not None
    assert result["session"]["media"] is None
    assert result["media"] is None
    start = next(call for call in requester.calls if call[1] == "remoteDesktop.start")
    assert start[2]["consent"] == {"required": True, "timeoutMs": 60000}
    # The host gets its own publish token up front so it can join immediately
    # after the local consent prompt; the client still gets media=null.
    assert start[2]["media"]["identity"] == "host:conn_1"
    assert media.grants == []


def test_control_with_unattended_gets_controller_grant() -> None:
    service, _, _, media, _, _ = _service(
        settings=_settings(remoteDesktopRequireConsent=True, remoteDesktopAllowUnattended=True)
    )
    result = asyncio.run(
        service.create_or_join(
            "conn_1",
            "user_1",
            {"connectorId": "conn_1", "mode": "control"},
            client_platform="web",
            app_version=None,
        )
    )
    assert result["media"]["canPublishData"] is True
    assert result["session"]["controller"] == f"viewer:{result['participantId']}"
    assert result["session"]["controllerLeaseExpiresAt"] is not None
    assert media.grants[0][1] == "controller"
    assert media.grants[0][2] == 120


def test_join_rejects_when_viewer_limit_reached() -> None:
    store = FakeStore()
    service, _, _, _, _, _ = _service(store=store)
    participants = [
        RemoteDesktopParticipant(
            participantId=f"pt_{index:020d}",
            userId="user_1",
            platform="windows",
            createdAt="2026-11-13T00:00:00Z",
        )
        for index in range(4)
    ]
    store.sessions["rds_old"] = RemoteDesktopSession(
        id="rds_old",
        connectorId="conn_1",
        userId="user_1",
        mode="view",
        state="live",
        mediaProvider="livekit",
        mediaRoom="rd_rds_old",
        mediaUrl="wss://rtc.example.com",
        participants=participants,
        createdAt="2026-11-13T00:00:00Z",
        updatedAt="2026-11-13T00:00:00Z",
    )

    with pytest.raises(RemoteDesktopLimitError) as caught:
        asyncio.run(
            service.create_or_join(
                "conn_1",
                "user_1",
                {"connectorId": "conn_1", "mode": "view"},
                client_platform="web",
                app_version=None,
            )
        )
    assert caught.value.status_code == 429
    assert caught.value.code == "remote_desktop_limit_reached"


def test_takeover_revokes_old_controller_and_grants_new_one() -> None:
    store = FakeStore()
    service, _, requester, media, broker, _ = _service(store=store)
    old_identity = "viewer:pt_old000000000000000000"
    participants = [
        RemoteDesktopParticipant(
            participantId="pt_old000000000000000000",
            userId="user_1",
            platform="windows",
            createdAt="2026-11-13T00:00:00Z",
        ),
        RemoteDesktopParticipant(
            participantId="pt_creator00000000000000",
            userId="user_1",
            platform="windows",
            createdAt="2026-11-13T00:00:00Z",
        ),
    ]
    store.sessions["rds_live"] = RemoteDesktopSession(
        id="rds_live",
        connectorId="conn_1",
        userId="user_1",
        mode="control",
        state="live",
        controller=old_identity,
        controllerLeaseExpiresAt="2026-11-13T00:05:00Z",
        mediaProvider="livekit",
        mediaRoom="rd_rds_live",
        mediaUrl="wss://rtc.example.com",
        participants=participants,
        createdAt="2026-11-13T00:00:00Z",
        updatedAt="2026-11-13T00:00:00Z",
    )

    result = asyncio.run(
        service.create_or_join(
            "conn_1",
            "user_1",
            {"connectorId": "conn_1", "mode": "control"},
            client_platform="web",
            app_version=None,
        )
    )

    new_identity = f"viewer:{result['participantId']}"
    assert result["session"]["controller"] == new_identity
    assert result["media"]["canPublishData"] is True
    assert (old_identity, "publishData", False) in media.permission_updates
    assert any(
        event["type"] == "remote_desktop.control.revoked"
        and target == "pt_old000000000000000000"
        for _, event, target in broker.events
    )
    grant_calls = [call for call in requester.calls if call[1] == "remoteDesktop.control.grant"]
    assert grant_calls
    assert grant_calls[-1][2]["controllerIdentity"] == new_identity


def test_token_roles_and_consent_pending() -> None:
    service, _, _, _, _, _ = _service(
        settings=_settings(remoteDesktopRequireConsent=True, remoteDesktopAllowUnattended=False)
    )
    created = asyncio.run(
        service.create_or_join(
            "conn_1",
            "user_1",
            {"connectorId": "conn_1", "mode": "control"},
            client_platform="web",
            app_version=None,
        )
    )
    session_id = created["session"]["id"]
    participant_id = created["participantId"]

    with pytest.raises(Exception) as consent:
        asyncio.run(service.issue_token(session_id, "user_1", participant_id, "controller"))
    assert getattr(consent.value, "code", "") == "remote_desktop_consent_pending"
    with pytest.raises(Exception) as viewer_pending:
        asyncio.run(service.issue_token(session_id, "user_1", participant_id, "viewer"))
    assert getattr(viewer_pending.value, "code", "") == "remote_desktop_consent_pending"

    handler = RemoteDesktopNotificationHandler(service)
    asyncio.run(
        handler.apply(
            "conn_1",
            "remoteDesktop.consent",
            {"sessionId": session_id, "decision": "granted"},
        )
    )
    viewer = asyncio.run(service.issue_token(session_id, "user_1", participant_id, "viewer"))
    assert viewer["canPublishData"] is False


def test_heartbeat_renews_controller_lease_and_end_is_idempotent() -> None:
    service, _, requester, media, broker, _ = _service()
    created = asyncio.run(
        service.create_or_join(
            "conn_1",
            "user_1",
            {"connectorId": "conn_1", "mode": "control"},
            client_platform="web",
            app_version=None,
        )
    )
    session_id = created["session"]["id"]
    participant_id = created["participantId"]
    before = created["session"]["controllerLeaseExpiresAt"]
    time.sleep(0.002)
    heartbeat = asyncio.run(service.heartbeat(session_id, "user_1", participant_id))
    assert heartbeat["session"]["controllerLeaseExpiresAt"] != before

    ended = asyncio.run(service.end(session_id, "user_1", "client_ended"))
    assert ended["session"]["state"] == "ended"
    assert ended["session"]["endReason"] == "client_ended"
    ended_again = asyncio.run(service.end(session_id, "user_1", "client_ended"))
    assert ended_again["session"]["state"] == "ended"
    stop_calls = [call for call in requester.calls if call[1] == "remoteDesktop.stop"]
    assert len(stop_calls) == 1
    assert media.deleted_rooms == ["rd_" + session_id]
    ended_events = [
        event for _, event, _ in broker.events if event["type"] == "remote_desktop.ended"
    ]
    assert len(ended_events) == 1


def test_screenshot_is_rate_limited_per_connector() -> None:
    service, _, _, _, _, _ = _service()
    created = asyncio.run(
        service.create_or_join(
            "conn_1",
            "user_1",
            {"connectorId": "conn_1", "mode": "view"},
            client_platform="web",
            app_version=None,
        )
    )
    session_id = created["session"]["id"]
    participant_id = created["participantId"]
    first = asyncio.run(
        service.screenshot(
            session_id,
            "user_1",
            participant_id,
            {"format": "jpeg", "maxWidth": 1280},
        )
    )
    assert first["dataBase64"] == "aGVsbG8="
    with pytest.raises(Exception) as caught:
        asyncio.run(
            service.screenshot(
                session_id,
                "user_1",
                participant_id,
                {"format": "jpeg"},
            )
        )
    assert getattr(caught.value, "code", "") == "remote_desktop_rate_limited"
    assert caught.value.status_code == 429


def test_foreign_user_cannot_read_session() -> None:
    service, _, _, _, _, _ = _service()
    created = asyncio.run(
        service.create_or_join(
            "conn_1",
            "user_1",
            {"connectorId": "conn_1", "mode": "view"},
            client_platform="web",
            app_version=None,
        )
    )
    with pytest.raises(Exception) as caught:
        asyncio.run(service.get_session(created["session"]["id"], "user_2"))
    assert getattr(caught.value, "code", "") == "remote_desktop_not_found"


def test_failed_host_start_ends_session_and_maps_reason() -> None:
    requester = FakeRequester()
    requester.fail_methods.add("remoteDesktop.start")
    service, store, _, _, _, _ = _service(requester=requester)

    with pytest.raises(Exception) as caught:
        asyncio.run(
            service.create_or_join(
                "conn_1",
                "user_1",
                {"connectorId": "conn_1", "mode": "view"},
                client_platform="web",
                app_version=None,
            )
        )
    assert caught.value.code == "remote_desktop_unavailable"
    assert caught.value.details["reason"] == "host_not_running"
    assert next(iter(store.sessions.values())).state == "ended"

def test_host_publishing_notification_moves_session_live() -> None:
    service, store, _, _, broker, _ = _service()
    created = asyncio.run(
        service.create_or_join(
            "conn_1",
            "user_1",
            {"connectorId": "conn_1", "mode": "view"},
            client_platform="web",
            app_version=None,
        )
    )
    session_id = created["session"]["id"]
    handler = RemoteDesktopNotificationHandler(service)
    effect = asyncio.run(
        handler.apply(
            "conn_1",
            "remoteDesktop.state",
            {"sessionId": session_id, "state": "publishing"},
        )
    )
    assert effect is not None
    assert store.sessions[session_id].state == "live"
    assert any(
        event["type"] == "remote_desktop.session.updated"
        for _, event, _ in broker.events
    )


def test_consent_granted_moves_awaiting_session_live() -> None:
    service, store, _, _, broker, _ = _service(
        settings=_settings(
            remoteDesktopRequireConsent=True,
            remoteDesktopAllowUnattended=False,
        )
    )
    created = asyncio.run(
        service.create_or_join(
            "conn_1",
            "user_1",
            {"connectorId": "conn_1", "mode": "control"},
            client_platform="web",
            app_version=None,
        )
    )
    session_id = created["session"]["id"]
    handler = RemoteDesktopNotificationHandler(service)
    asyncio.run(
        handler.apply(
            "conn_1",
            "remoteDesktop.consent",
            {"sessionId": session_id, "decision": "granted"},
        )
    )
    session = store.sessions[session_id]
    assert session.state == "live"
    assert session.controller == f"viewer:{created['participantId']}"
    assert any(
        event["type"] == "remote_desktop.session.updated"
        for _, event, _ in broker.events
    )

def test_release_control_clears_controller_without_body_identity() -> None:
    service, store, _, media, _, _ = _service()
    created = asyncio.run(
        service.create_or_join(
            "conn_1",
            "user_1",
            {"connectorId": "conn_1", "mode": "control"},
            client_platform="web",
            app_version=None,
        )
    )
    session_id = created["session"]["id"]
    result = asyncio.run(service.release_control(session_id, "user_1"))
    assert result["session"]["controller"] is None
    assert store.sessions[session_id].controller is None
    assert (
        f"viewer:{created['participantId']}",
        "publishData",
        False,
    ) in media.permission_updates

def test_view_mode_with_no_media_is_unavailable() -> None:
    from agent_server.services.remote_desktop_media import NoopMediaProvider

    media = NoopMediaProvider()
    service, store, _, _, _, _ = _service(media=media)  # type: ignore[arg-type]
    with pytest.raises(Exception) as caught:
        asyncio.run(
            service.create_or_join(
                "conn_1",
                "user_1",
                {"connectorId": "conn_1", "mode": "view"},
                client_platform="web",
                app_version=None,
            )
        )
    assert getattr(caught.value, "code", "") == "media_unavailable"
    assert store.sessions == {}

from __future__ import annotations

import hashlib
import json
import time
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from agent_server.api import remote_desktop as remote_desktop_api
from agent_server.core.remote_desktop import RemoteDesktopNotFoundError
from agent_server.deps import current_user_id
from agent_server.services.remote_desktop_media import sign_hs256_jwt


class FakeStore:
    async def get_connector(self, connector_id: str) -> Any:
        if connector_id != "conn_1":
            raise KeyError(connector_id)
        return SimpleNamespace(id=connector_id, name="Test", userId="user_1")


class FakeBroker:
    def __init__(self) -> None:
        self.values: dict[str, Any] = {}

    async def get_json(self, key: str) -> Any | None:
        return self.values.get(key)

    async def set_json(self, key: str, value: Any, *, ttl_seconds: int) -> None:
        self.values[key] = value


class FakeTicket:
    user_id = "user_1"
    client_id = "pt_abcdefghijklmnop"


class FakeTickets:
    def __init__(self) -> None:
        self.consumed: list[tuple[str, str]] = []

    async def consume_scope(self, token: str, *, scope: str) -> FakeTicket | None:
        self.consumed.append((token, scope))
        return FakeTicket() if token == "wst_good" else None


class FakeService:
    def __init__(self) -> None:
        self.ended: list[tuple[str, str]] = []
        self.created = True

    async def get_host_info(self, connector_id: str, user_id: str) -> dict[str, Any]:
        return {
            "connectorId": connector_id,
            "capabilities": [],
            "host": None,
            "serverTime": "2026-11-13T00:00:00Z",
        }

    async def create_or_join(
        self,
        connector_id: str,
        user_id: str,
        request: dict[str, Any],
        *,
        client_platform: str,
        app_version: str | None,
    ) -> dict[str, Any]:
        return {
            "session": {"id": "rds_1", "state": "starting"},
            "participantId": "pt_abcdefghijklmnop",
            "media": None,
            "ws": {
                "url": "/api/v2/remote-desktop/sessions/rds_1/ws",
                "ticket": "wst_1",
                "expiresAt": "2026-11-13T01:00:00Z",
            },
            "created": self.created,
        }

    async def get_session(self, session_id: str, user_id: str) -> dict[str, Any]:
        if session_id == "missing":
            raise RemoteDesktopNotFoundError(
                message="Remote desktop session not found.",
                details={"sessionId": session_id},
            )
        return {"session": {"id": session_id, "state": "live"}}

    async def list_sessions(self, **kwargs: Any) -> dict[str, Any]:
        return {"sessions": [], "nextCursor": None}

    async def issue_token(self, *args: Any) -> dict[str, Any]:
        return {
            "provider": "livekit",
            "url": "wss://rtc.example.com",
            "room": "rd_rds_1",
            "identity": "viewer:pt_abcdefghijklmnop",
            "token": "token",
            "tokenExpiresAt": "2026-11-13T01:00:00Z",
            "canPublishData": True,
        }

    async def take_control(self, *args: Any) -> dict[str, Any]:
        return {"session": {"id": "rds_1"}, "pending": True}

    async def release_control(self, *args: Any) -> dict[str, Any]:
        return {"session": {"id": "rds_1"}}

    async def heartbeat(self, *args: Any) -> dict[str, Any]:
        return {"session": {"id": "rds_1"}}

    async def end(self, *args: Any) -> dict[str, Any]:
        return {"session": {"id": "rds_1", "state": "ended"}}

    async def issue_ticket(self, *args: Any) -> dict[str, Any]:
        return {
            "url": "/api/v2/remote-desktop/sessions/rds_1/ws",
            "ticket": "wst_1",
            "expiresAt": "2026-11-13T01:00:00Z",
        }

    async def screenshot(self, *args: Any) -> dict[str, Any]:
        return {
            "width": 1280,
            "height": 720,
            "capturedAt": "2026-11-13T00:00:00Z",
            "dataBase64": "aGVsbG8=",
        }

    async def end_from_system(self, session_id: str, reason: str) -> None:
        self.ended.append((session_id, reason))

    async def mark_live_from_webhook(self, session_id: str) -> None:
        return None

    async def mark_interrupted_from_webhook(self, session_id: str) -> None:
        return None

    def session_snapshot(self, session: Any) -> dict[str, Any]:
        return session.to_api()


@pytest.fixture
def api_client() -> tuple[TestClient, FakeService, FakeBroker, FakeTickets]:
    app = FastAPI()
    app.include_router(remote_desktop_api.router)
    service = FakeService()
    broker = FakeBroker()
    tickets = FakeTickets()
    app.state.store = FakeStore()
    app.state.remote_desktop_service = service
    app.state.remote_desktop_broker = broker
    app.state.ws_tickets = tickets
    app.dependency_overrides[current_user_id] = lambda: "user_1"
    return TestClient(app), service, broker, tickets


def test_host_info_and_create_status(api_client: tuple[TestClient, FakeService, FakeBroker, FakeTickets]) -> None:
    client, service, _, _ = api_client
    host = client.get("/connectors/conn_1/remote-desktop")
    assert host.status_code == 200
    assert host.json()["connectorId"] == "conn_1"

    created = client.post(
        "/remote-desktop/sessions",
        json={"connectorId": "conn_1", "mode": "view"},
    )
    assert created.status_code == 201
    assert created.json()["participantId"] == "pt_abcdefghijklmnop"
    service.created = False
    joined = client.post(
        "/remote-desktop/sessions",
        json={"connectorId": "conn_1", "mode": "view"},
    )
    assert joined.status_code == 200
    assert "created" not in joined.json()


def test_session_error_envelope(api_client: tuple[TestClient, FakeService, FakeBroker, FakeTickets]) -> None:
    client, _, _, _ = api_client
    response = client.get("/remote-desktop/sessions/missing")
    assert response.status_code == 404
    detail = response.json()["detail"]
    assert detail["code"] == "remote_desktop_not_found"
    assert detail["details"]["sessionId"] == "missing"


def test_take_control_pending_returns_202(api_client: tuple[TestClient, FakeService, FakeBroker, FakeTickets]) -> None:
    client, _, _, _ = api_client
    response = client.post(
        "/remote-desktop/sessions/rds_1/take-control",
        json={"participantId": "pt_abcdefghijklmnop"},
    )
    assert response.status_code == 202
    assert response.json()["session"]["id"] == "rds_1"


def test_livekit_webhook_requires_configuration_and_handles_events(
    api_client: tuple[TestClient, FakeService, FakeBroker, FakeTickets],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client, service, _, _ = api_client
    no_config = client.post("/internal/livekit/webhook", content=b"{}")
    assert no_config.status_code == 503

    monkeypatch.setenv("AGENT_SERVER_LIVEKIT_API_KEY", "key")
    monkeypatch.setenv("AGENT_SERVER_LIVEKIT_API_SECRET", "secret")
    body = json.dumps(
        {"event": "room_finished", "room": {"name": "rd_rds_1"}, "id": "evt_1"}
    ).encode()
    token = sign_hs256_jwt(
        {
            "iss": "key",
            "exp": int(time.time()) + 60,
            "sha256": hashlib.sha256(body).hexdigest(),
        },
        "secret",
    )
    first = client.post(
        "/internal/livekit/webhook",
        content=body,
        headers={"Authorization": f"Bearer {token}"},
    )
    assert first.status_code == 200
    assert first.json() == {"ok": True}
    assert service.ended == [("rds_1", "media_error")]

    duplicate = client.post(
        "/internal/livekit/webhook",
        content=body,
        headers={"Authorization": f"Bearer {token}"},
    )
    assert duplicate.status_code == 200
    assert duplicate.json()["duplicate"] is True


def test_livekit_webhook_rejects_oversized_body(
    api_client: tuple[TestClient, FakeService, FakeBroker, FakeTickets],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client, _, _, _ = api_client
    monkeypatch.setenv("AGENT_SERVER_LIVEKIT_API_KEY", "key")
    monkeypatch.setenv("AGENT_SERVER_LIVEKIT_API_SECRET", "secret")
    response = client.post(
        "/internal/livekit/webhook",
        content=b"x" * (1024 * 1024 + 1),
        headers={"Authorization": "Bearer invalid"},
    )
    assert response.status_code == 413


def test_invalid_websocket_ticket_is_rejected(
    api_client: tuple[TestClient, FakeService, FakeBroker, FakeTickets],
) -> None:
    client, _, _, tickets = api_client
    with pytest.raises(WebSocketDisconnect), client.websocket_connect(
        "/remote-desktop/sessions/rds_1/ws?ticket=bad"
    ) as socket:
        socket.receive_json()
    assert tickets.consumed == [("bad", "remote-desktop:rds_1")]

def test_release_control_accepts_empty_contract_body(
    api_client: tuple[TestClient, FakeService, FakeBroker, FakeTickets],
) -> None:
    client, _, _, _ = api_client
    response = client.post(
        "/remote-desktop/sessions/rds_1/release-control",
        json={},
    )
    assert response.status_code == 200
    assert response.json()["session"]["id"] == "rds_1"

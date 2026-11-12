from __future__ import annotations

import asyncio
import hashlib
import json
import time
from typing import Any

import pytest

from agent_server.infra.livekit_webhook import (
    LiveKitWebhookError,
    claim_webhook_event,
    parse_webhook_event,
    verify_livekit_webhook,
)
from agent_server.services.remote_desktop_media import sign_hs256_jwt


class FakeBroker:
    def __init__(self) -> None:
        self.values: dict[str, Any] = {}

    async def get_json(self, key: str) -> Any | None:
        return self.values.get(key)

    async def set_json(self, key: str, value: Any, *, ttl_seconds: int) -> None:
        self.values[key] = value


def _token(
    body: bytes,
    *,
    api_key: str = "key",
    secret: str = "secret",
    expires_in: int = 60,
    body_hash: str | None = None,
) -> str:
    payload = {
        "iss": api_key,
        "sub": "webhook",
        "iat": int(time.time()),
        "exp": int(time.time()) + expires_in,
        "sha256": body_hash or hashlib.sha256(body).hexdigest(),
        "video": {"room": "rd_x"},
    }
    return sign_hs256_jwt(payload, secret)


def test_verify_valid_webhook() -> None:
    body = json.dumps({"event": "room_finished", "room": {"name": "rd_x"}}).encode()
    payload = verify_livekit_webhook(
        body, f"Bearer {_token(body)}", "key", "secret"
    )
    assert payload["iss"] == "key"


def test_verify_accepts_base64_sha256_claim() -> None:
    body = b'{"event":"track_published"}'
    digest = hashlib.sha256(body).digest()
    claim = __import__("base64").b64encode(digest).decode()
    payload = verify_livekit_webhook(
        body, f"Bearer {_token(body, body_hash=claim)}", "key", "secret"
    )
    assert payload["sha256"] == claim


def test_rejects_tampered_body() -> None:
    body = b'{"event":"room_finished"}'
    token = _token(body)
    with pytest.raises(LiveKitWebhookError) as caught:
        verify_livekit_webhook(body + b" ", f"Bearer {token}", "key", "secret")
    assert caught.value.status_code == 401


def test_rejects_expired_token() -> None:
    body = b"{}"
    token = _token(body, expires_in=-1)
    with pytest.raises(LiveKitWebhookError):
        verify_livekit_webhook(body, f"Bearer {token}", "key", "secret")


def test_rejects_wrong_signature_and_issuer() -> None:
    body = b"{}"
    token = _token(body)
    with pytest.raises(LiveKitWebhookError):
        verify_livekit_webhook(body, f"Bearer {token}", "other-key", "secret")
    with pytest.raises(LiveKitWebhookError):
        verify_livekit_webhook(body, f"Bearer {token}", "key", "other-secret")


def test_rejects_missing_or_malformed_authorization() -> None:
    with pytest.raises(LiveKitWebhookError):
        verify_livekit_webhook(b"{}", None, "key", "secret")
    with pytest.raises(LiveKitWebhookError):
        verify_livekit_webhook(b"{}", "Basic abc", "key", "secret")
    with pytest.raises(LiveKitWebhookError):
        verify_livekit_webhook(b"{}", "Bearer not.a.jwt", "key", "secret")


def test_webhook_event_parsing() -> None:
    event = parse_webhook_event(
        {
            "event": "participant_left",
            "room": {"name": "rd_rds_1"},
            "participant": {"identity": "host:conn_1"},
            "id": "evt_1",
        }
    )
    assert event.event == "participant_left"
    assert event.room_name == "rd_rds_1"
    assert event.participant_identity == "host:conn_1"
    assert event.event_id == "evt_1"


def test_webhook_dedupe_returns_false_for_replay() -> None:
    broker = FakeBroker()
    payload = {"event": "room_finished", "room": {"name": "rd_x"}, "id": "evt_1"}

    assert asyncio.run(claim_webhook_event(broker, payload)) is True
    assert asyncio.run(claim_webhook_event(broker, payload)) is False

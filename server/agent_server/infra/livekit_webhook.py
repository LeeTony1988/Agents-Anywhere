"""LiveKit webhook authentication helpers.

LiveKit signs webhook deliveries with a short-lived HS256 JWT whose ``sha256``
claim binds the token to the raw request body. Verification happens before any
JSON parsing so a tampered body can never reach a handler.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any, Protocol


class LiveKitWebhookError(Exception):
    def __init__(self, message: str, *, status_code: int = 401) -> None:
        super().__init__(message)
        self.message = message
        self.status_code = status_code


@dataclass(frozen=True, slots=True)
class WebhookEvent:
    event: str
    room: dict[str, Any] | None
    participant: dict[str, Any] | None
    event_id: str | None = None

    @property
    def room_name(self) -> str | None:
        if isinstance(self.room, Mapping):
            value = self.room.get("name")
            return value if isinstance(value, str) else None
        return None

    @property
    def participant_identity(self) -> str | None:
        if isinstance(self.participant, Mapping):
            value = self.participant.get("identity")
            return value if isinstance(value, str) else None
        return None


def _b64url_decode(value: str) -> bytes:
    padding = "=" * (-len(value) % 4)
    try:
        return base64.urlsafe_b64decode(value + padding)
    except (ValueError, TypeError) as exc:
        raise LiveKitWebhookError("invalid webhook token encoding") from exc


def _bearer_token(authorization: str | None) -> str:
    if not authorization:
        raise LiveKitWebhookError("missing webhook authorization")
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise LiveKitWebhookError("invalid webhook authorization")
    return token.strip()


def _sha256_matches(claim: str, raw_body: bytes) -> bool:
    digest = hashlib.sha256(raw_body).digest()
    candidates = {
        digest.hex(),
        base64.b64encode(digest).decode("ascii"),
        base64.b64encode(digest).decode("ascii").rstrip("="),
        base64.urlsafe_b64encode(digest).decode("ascii"),
        base64.urlsafe_b64encode(digest).decode("ascii").rstrip("="),
    }
    return any(hmac.compare_digest(claim, candidate) for candidate in candidates)


def verify_livekit_webhook(
    raw_body: bytes,
    authorization: str | None,
    api_key: str,
    api_secret: str,
) -> dict[str, Any]:
    """Verify signature, issuer, expiry and the body ``sha256`` claim.

    Raises :class:`LiveKitWebhookError` (401) on every failure; callers decide
    whether the webhook endpoint is configured at all.
    """

    if not api_key or not api_secret:
        raise LiveKitWebhookError("livekit webhook is not configured", status_code=503)
    token = _bearer_token(authorization)
    parts = token.split(".")
    if len(parts) != 3:
        raise LiveKitWebhookError("invalid webhook token")
    encoded_header, encoded_payload, encoded_signature = parts
    try:
        header = json.loads(_b64url_decode(encoded_header))
    except (ValueError, UnicodeDecodeError) as exc:
        raise LiveKitWebhookError("invalid webhook token header") from exc
    if not isinstance(header, dict) or header.get("alg") != "HS256":
        raise LiveKitWebhookError("unsupported webhook token algorithm")
    signing_input = f"{encoded_header}.{encoded_payload}".encode("ascii")
    expected = hmac.new(
        api_secret.encode("utf-8"), signing_input, hashlib.sha256
    ).digest()
    try:
        provided = _b64url_decode(encoded_signature)
    except LiveKitWebhookError as exc:
        raise LiveKitWebhookError("invalid webhook signature") from exc
    if not hmac.compare_digest(expected, provided):
        raise LiveKitWebhookError("invalid webhook signature")
    try:
        payload = json.loads(_b64url_decode(encoded_payload))
    except (ValueError, UnicodeDecodeError) as exc:
        raise LiveKitWebhookError("invalid webhook payload") from exc
    if not isinstance(payload, dict):
        raise LiveKitWebhookError("invalid webhook payload")
    if payload.get("iss") != api_key:
        raise LiveKitWebhookError("invalid webhook issuer")
    try:
        expires_at = int(payload["exp"])
    except (KeyError, TypeError, ValueError) as exc:
        raise LiveKitWebhookError("webhook token has no expiry") from exc
    if expires_at <= int(time.time()):
        raise LiveKitWebhookError("webhook token has expired")
    not_before = payload.get("nbf")
    if not_before is not None:
        try:
            if int(not_before) > int(time.time()) + 30:
                raise LiveKitWebhookError("webhook token is not valid yet")
        except (TypeError, ValueError) as exc:
            raise LiveKitWebhookError("invalid webhook not-before claim") from exc
    claim = payload.get("sha256")
    if not isinstance(claim, str) or not _sha256_matches(claim, raw_body):
        raise LiveKitWebhookError("webhook body hash mismatch")
    return payload


def parse_webhook_event(payload: Mapping[str, Any]) -> WebhookEvent:
    room = payload.get("room")
    participant = payload.get("participant")
    event_id = payload.get("id")
    return WebhookEvent(
        event=str(payload.get("event") or ""),
        room=dict(room) if isinstance(room, Mapping) else None,
        participant=dict(participant) if isinstance(participant, Mapping) else None,
        event_id=event_id if isinstance(event_id, str) and event_id else None,
    )


def webhook_event_key(payload: Mapping[str, Any]) -> str:
    event_id = payload.get("id")
    if isinstance(event_id, str) and event_id:
        return event_id
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


class DedupeCache(Protocol):
    async def get_json(self, key: str) -> Any | None: ...

    async def set_json(self, key: str, value: Any, *, ttl_seconds: int) -> None: ...


async def claim_webhook_event(
    broker: DedupeCache, payload: Mapping[str, Any], *, ttl_seconds: int = 86_400
) -> bool:
    """Return True the first time an event id is seen, False for a replay."""

    key = f"livekit-event:{webhook_event_key(payload)}"
    if await broker.get_json(key) is not None:
        return False
    await broker.set_json(key, True, ttl_seconds=ttl_seconds)
    return True

"""Media provider port and the self-hosted LiveKit implementation.

The service layer only depends on :class:`MediaProvider`; it never imports the
LiveKit SDK. Tokens are signed locally with the same HS256 scheme LiveKit uses
and are returned to the caller only — they are never persisted or logged.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import secrets
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any, Protocol, runtime_checkable

import httpx
from loguru import logger

from agent_server.core.remote_desktop import RemoteDesktopError

try:  # The domain module owns the wire error code; this fallback keeps tests usable.
    from agent_server.core.remote_desktop import RemoteDesktopMediaUnavailableError
except ImportError:  # pragma: no cover - exercised only before the core module lands

    class RemoteDesktopMediaUnavailableError(RemoteDesktopError):  # type: ignore[no-redef]
        default_code = "media_unavailable"
        default_status_code = 503


DEFAULT_VIEWER_TOKEN_TTL_SECONDS = 600
DEFAULT_CONTROLLER_TOKEN_TTL_SECONDS = 120
LIVEKIT_HTTP_TIMEOUT_SECONDS = 5.0


def _b64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _json_bytes(value: Mapping[str, Any]) -> bytes:
    return json.dumps(value, separators=(",", ":"), sort_keys=True).encode("utf-8")


def _isoformat(value: datetime) -> str:
    if value.tzinfo is None:
        value = value.replace(tzinfo=UTC)
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


def sign_hs256_jwt(payload: Mapping[str, Any], secret: str) -> str:
    """Sign ``payload`` with HS256 and return a base64url JWT (no padding)."""

    header = {"alg": "HS256", "typ": "JWT"}
    signing_input = f"{_b64url(_json_bytes(header))}.{_b64url(_json_bytes(payload))}"
    digest = hmac.new(
        secret.encode("utf-8"), signing_input.encode("ascii"), hashlib.sha256
    ).digest()
    return f"{signing_input}.{_b64url(digest)}"


@dataclass(frozen=True, slots=True)
class MediaGrant:
    provider: str
    url: str
    room: str
    identity: str
    token: str
    tokenExpiresAt: str
    canPublishData: bool

    def to_dict(self) -> dict[str, Any]:
        return {
            "provider": self.provider,
            "url": self.url,
            "room": self.room,
            "identity": self.identity,
            "token": self.token,
            "tokenExpiresAt": self.tokenExpiresAt,
            "canPublishData": self.canPublishData,
        }


@runtime_checkable
class MediaProvider(Protocol):
    @property
    def available(self) -> bool: ...

    async def create_grant(
        self,
        session: Any,
        participant_id: str,
        *,
        role: str,
        ttl_seconds: int,
    ) -> MediaGrant: ...

    async def update_can_publish_data(
        self, session: Any, identity: str, allowed: bool
    ) -> None: ...

    async def delete_room(self, room: str) -> None: ...

    async def remove_participant(self, room: str, identity: str) -> None: ...


class LiveKitMediaProvider:
    """Minimal LiveKit client: HS256 join tokens plus three RoomService calls."""

    name = "livekit"

    def __init__(
        self,
        url: str,
        api_key: str,
        api_secret: str,
        *,
        viewer_token_ttl_seconds: int = DEFAULT_VIEWER_TOKEN_TTL_SECONDS,
        controller_token_ttl_seconds: int = DEFAULT_CONTROLLER_TOKEN_TTL_SECONDS,
        clock: Callable[[], datetime] | None = None,
        client_factory: Callable[..., httpx.AsyncClient] | None = None,
    ) -> None:
        self.url = url.rstrip("/")
        self.api_key = api_key
        self.api_secret = api_secret
        self.viewer_token_ttl_seconds = viewer_token_ttl_seconds
        self.controller_token_ttl_seconds = controller_token_ttl_seconds
        self._clock = clock or (lambda: datetime.now(UTC))
        self._client_factory = client_factory or httpx.AsyncClient

    @property
    def available(self) -> bool:
        return True

    @property
    def room_service_url(self) -> str:
        base = self.url
        if base.startswith("wss://"):
            return "https://" + base[len("wss://") :]
        if base.startswith("ws://"):
            return "http://" + base[len("ws://") :]
        return base

    @property
    def join_url(self) -> str:
        if self.url.startswith("https://"):
            return "wss://" + self.url[len("https://") :]
        if self.url.startswith("http://"):
            return "ws://" + self.url[len("http://") :]
        return self.url

    @staticmethod
    def room_name(session: Any) -> str:
        room = getattr(session, "mediaRoom", None)
        if isinstance(room, str) and room:
            return room
        return f"rd_{session.id}"

    def _video_grant(self, room: str) -> dict[str, Any]:
        return {"roomJoin": True, "room": room}

    def _base_payload(self, identity: str) -> dict[str, Any]:
        now = self._clock()
        issued_at = int(now.timestamp())
        return {
            "iss": self.api_key,
            "sub": identity,
            "iat": issued_at,
            "nbf": issued_at,
        }

    def create_join_token(
        self,
        *,
        room: str,
        identity: str,
        can_publish: bool,
        can_subscribe: bool,
        can_publish_data: bool,
        ttl_seconds: int,
    ) -> tuple[str, str]:
        now = self._clock()
        payload = self._base_payload(identity)
        payload["exp"] = int(now.timestamp()) + max(1, int(ttl_seconds))
        payload["video"] = {
            **self._video_grant(room),
            "canPublish": can_publish,
            "canSubscribe": can_subscribe,
            "canPublishData": can_publish_data,
        }
        expires_at = _isoformat(now + timedelta(seconds=max(1, int(ttl_seconds))))
        return sign_hs256_jwt(payload, self.api_secret), expires_at

    def create_room_admin_token(self, room: str) -> str:
        now = self._clock()
        payload = self._base_payload("server")
        payload["exp"] = int(now.timestamp()) + 60
        payload["video"] = {"roomAdmin": True, "room": room}
        return sign_hs256_jwt(payload, self.api_secret)

    async def create_grant(
        self,
        session: Any,
        participant_id: str,
        *,
        role: str,
        ttl_seconds: int,
    ) -> MediaGrant:
        from agent_server.core.remote_desktop import viewer_identity

        identity = viewer_identity(participant_id)
        room = self.room_name(session)
        is_controller = role == "controller"
        token, expires_at = self.create_join_token(
            room=room,
            identity=identity,
            can_publish=False,
            can_subscribe=True,
            can_publish_data=is_controller,
            ttl_seconds=ttl_seconds,
        )
        return MediaGrant(
            provider=self.name,
            url=self.join_url,
            room=room,
            identity=identity,
            token=token,
            tokenExpiresAt=expires_at,
            canPublishData=is_controller,
        )

    async def update_can_publish_data(
        self, session: Any, identity: str, allowed: bool
    ) -> None:
        await self._room_service_call(
            "UpdateParticipant",
            {
                "room": self.room_name(session),
                "identity": identity,
                "permission": {
                    "canPublish": False,
                    "canSubscribe": True,
                    "canPublishData": bool(allowed),
                },
            },
        )

    async def remove_participant(self, room: str, identity: str) -> None:
        await self._room_service_call(
            "RemoveParticipant", {"room": room, "identity": identity}
        )

    async def delete_room(self, room: str) -> None:
        await self._room_service_call("DeleteRoom", {"room": room})

    async def _room_service_call(
        self, method: str, body: Mapping[str, Any]
    ) -> None:
        room = str(body.get("room") or "")
        url = f"{self.room_service_url}/twirp/livekit.RoomService/{method}"
        headers = {
            "Authorization": f"Bearer {self.create_room_admin_token(room)}",
            "Content-Type": "application/json",
        }
        try:
            async with self._client_factory(timeout=LIVEKIT_HTTP_TIMEOUT_SECONDS) as client:
                response = await client.post(url, json=dict(body), headers=headers)
            if response.status_code >= 400:
                logger.warning(
                    "livekit room service call failed method={} status={}",
                    method,
                    response.status_code,
                )
        except Exception as exc:  # noqa: BLE001 - media must never break the control plane
            logger.warning(
                "livekit room service call failed method={} error={}",
                method,
                str(exc) or exc.__class__.__name__,
            )


class NoopMediaProvider:
    """Media-disabled provider used when LiveKit is not configured."""

    name = "noop"
    viewer_token_ttl_seconds = DEFAULT_VIEWER_TOKEN_TTL_SECONDS
    controller_token_ttl_seconds = DEFAULT_CONTROLLER_TOKEN_TTL_SECONDS

    @property
    def available(self) -> bool:
        return False

    async def create_grant(
        self,
        session: Any,
        participant_id: str,
        *,
        role: str,
        ttl_seconds: int,
    ) -> MediaGrant:
        raise RemoteDesktopMediaUnavailableError(
            message="Remote desktop media is not configured.",
            details={"reason": "media_not_configured"},
        )

    async def update_can_publish_data(
        self, session: Any, identity: str, allowed: bool
    ) -> None:
        return None

    async def delete_room(self, room: str) -> None:
        return None

    async def remove_participant(self, room: str, identity: str) -> None:
        return None


def _truthy(value: str | None) -> bool:
    return (value or "").strip().lower() in {"1", "true", "yes", "on"}


def _positive_int(value: str | None, default: int) -> int:
    try:
        parsed = int((value or "").strip())
    except (TypeError, ValueError):
        return default
    return parsed if parsed > 0 else default


def build_media_provider(
    environ: Mapping[str, str] | None = None,
) -> tuple[MediaProvider, bool, str | None]:
    """Build the configured provider without crashing when LiveKit is absent."""

    env = environ or {}
    enabled = _truthy(env.get("AGENT_SERVER_REMOTE_DESKTOP_ENABLED"))
    if not enabled:
        return NoopMediaProvider(), False, None
    url = (env.get("AGENT_SERVER_LIVEKIT_URL") or "").strip()
    api_key = (env.get("AGENT_SERVER_LIVEKIT_API_KEY") or "").strip()
    api_secret = (env.get("AGENT_SERVER_LIVEKIT_API_SECRET") or "").strip()
    missing = [
        name
        for name, value in (
            ("AGENT_SERVER_LIVEKIT_URL", url),
            ("AGENT_SERVER_LIVEKIT_API_KEY", api_key),
            ("AGENT_SERVER_LIVEKIT_API_SECRET", api_secret),
        )
        if not value
    ]
    if missing:
        return (
            NoopMediaProvider(),
            True,
            "missing " + ", ".join(missing),
        )
    viewer_ttl = _positive_int(
        env.get("AGENT_SERVER_REMOTE_DESKTOP_TOKEN_TTL_SECONDS"),
        DEFAULT_VIEWER_TOKEN_TTL_SECONDS,
    )
    controller_ttl = _positive_int(
        env.get("AGENT_SERVER_REMOTE_DESKTOP_CONTROLLER_TOKEN_TTL_SECONDS"),
        DEFAULT_CONTROLLER_TOKEN_TTL_SECONDS,
    )
    return (
        LiveKitMediaProvider(
            url,
            api_key,
            api_secret,
            viewer_token_ttl_seconds=viewer_ttl,
            controller_token_ttl_seconds=controller_ttl,
        ),
        True,
        None,
    )


def new_media_request_nonce() -> str:
    """Test helper: an unpredictable nonce suitable for request ids."""

    return secrets.token_urlsafe(12)

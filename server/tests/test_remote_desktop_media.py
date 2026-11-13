from __future__ import annotations

import asyncio
import base64
import json
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

import pytest

from agent_server.services.remote_desktop_media import (
    LiveKitMediaProvider,
    MediaGrant,
    NoopMediaProvider,
    RemoteDesktopMediaUnavailableError,
    build_media_provider,
    sign_hs256_jwt,
)


def _decode(part: str) -> bytes:
    return base64.urlsafe_b64decode(part + "=" * (-len(part) % 4))


def _claims(token: str) -> dict:
    _, payload, _ = token.split(".")
    return json.loads(_decode(payload))


def _session() -> SimpleNamespace:
    return SimpleNamespace(id="rds_test", mediaRoom="rd_rds_test")


def test_livekit_grant_has_viewer_grants_and_expected_expiry() -> None:
    now = datetime(2026, 11, 13, 0, 0, 0, tzinfo=UTC)
    provider = LiveKitMediaProvider(
        "wss://rtc.example.com",
        "key",
        "secret",
        viewer_token_ttl_seconds=600,
        controller_token_ttl_seconds=120,
        clock=lambda: now,
    )

    grant = asyncio.run(
        provider.create_grant(_session(), "pt_abcdefghijklmnop", role="viewer", ttl_seconds=600)
    )

    assert isinstance(grant, MediaGrant)
    assert grant.identity == "viewer:pt_abcdefghijklmnop"
    assert grant.room == "rd_rds_test"
    assert grant.canPublishData is False
    claims = _claims(grant.token)
    assert claims["iss"] == "key"
    assert claims["sub"] == "viewer:pt_abcdefghijklmnop"
    assert claims["exp"] - claims["iat"] == 600
    assert claims["video"] == {
        "roomJoin": True,
        "room": "rd_rds_test",
        "canPublish": False,
        "canSubscribe": True,
        "canPublishData": False,
    }
    assert grant.to_dict()["token"] == grant.token


def test_livekit_controller_grant_publishes_data_with_short_ttl() -> None:
    provider = LiveKitMediaProvider(
        "wss://rtc.example.com",
        "key",
        "secret",
        controller_token_ttl_seconds=120,
    )
    grant = asyncio.run(
        provider.create_grant(
            _session(), "pt_abcdefghijklmnop", role="controller", ttl_seconds=120
        )
    )
    claims = _claims(grant.token)
    assert claims["video"]["canPublishData"] is True
    assert claims["exp"] - claims["iat"] == 120
    assert grant.canPublishData is True


def test_livekit_room_service_token_is_room_admin() -> None:
    provider = LiveKitMediaProvider("wss://rtc.example.com", "key", "secret")
    claims = _claims(provider.create_room_admin_token("rd_rds_test"))
    # LiveKit rejects DeleteRoom/RemoveParticipant with a `roomAdmin`-only
    # token (401 "permissions denied"), so the admin token has to carry
    # `roomCreate` too. Verified against livekit-server 1.13.
    assert claims["video"] == {
        "roomAdmin": True,
        "roomCreate": True,
        "roomList": True,
        "room": "rd_rds_test",
    }
    assert claims["exp"] - claims["iat"] == 60


def test_admin_token_claims_cover_every_room_service_call() -> None:
    provider = LiveKitMediaProvider("wss://rtc.example.com", "key", "secret")
    video = _claims(provider.create_room_admin_token("rd_x"))["video"]
    # One token serves DeleteRoom, RemoveParticipant and ListParticipants.
    assert video["roomCreate"] is True
    assert video["roomAdmin"] is True
    assert video["room"] == "rd_x"
    # It must not grant anything a participant could abuse (no joins, no video).
    assert "roomJoin" not in video
    assert "canPublish" not in video


def test_noop_provider_is_unavailable_and_refuses_grants() -> None:
    provider = NoopMediaProvider()
    assert provider.available is False
    with pytest.raises(RemoteDesktopMediaUnavailableError) as caught:
        asyncio.run(
            provider.create_grant(_session(), "pt_x", role="viewer", ttl_seconds=600)
        )
    assert caught.value.code == "media_unavailable"


def test_build_provider_defaults_to_noop() -> None:
    provider, enabled, error = build_media_provider({})
    assert isinstance(provider, NoopMediaProvider)
    assert enabled is False
    assert error is None


def test_build_provider_requires_all_livekit_values() -> None:
    provider, enabled, error = build_media_provider(
        {
            "AGENT_SERVER_REMOTE_DESKTOP_ENABLED": "true",
            "AGENT_SERVER_LIVEKIT_URL": "wss://rtc.example.com",
        }
    )
    assert isinstance(provider, NoopMediaProvider)
    assert enabled is True
    assert "API_KEY" in (error or "")


def test_build_provider_honours_ttl_environment() -> None:
    provider, enabled, error = build_media_provider(
        {
            "AGENT_SERVER_REMOTE_DESKTOP_ENABLED": "true",
            "AGENT_SERVER_LIVEKIT_URL": "wss://rtc.example.com",
            "AGENT_SERVER_LIVEKIT_API_KEY": "key",
            "AGENT_SERVER_LIVEKIT_API_SECRET": "secret",
            "AGENT_SERVER_REMOTE_DESKTOP_TOKEN_TTL_SECONDS": "900",
            "AGENT_SERVER_REMOTE_DESKTOP_CONTROLLER_TOKEN_TTL_SECONDS": "30",
        }
    )
    assert isinstance(provider, LiveKitMediaProvider)
    assert enabled is True
    assert error is None
    assert provider.viewer_token_ttl_seconds == 900
    assert provider.controller_token_ttl_seconds == 30
    assert provider.join_url == "wss://rtc.example.com"


def test_sign_jwt_uses_unpadded_base64url() -> None:
    token = sign_hs256_jwt({"iss": "key", "exp": 1}, "secret")
    for part in token.split("."):
        assert "=" not in part
        assert "+" not in part
        assert "/" not in part


def test_http_url_derivation() -> None:
    assert (
        LiveKitMediaProvider("wss://rtc.example.com", "k", "s").room_service_url
        == "https://rtc.example.com"
    )
    assert (
        LiveKitMediaProvider("https://rtc.example.com", "k", "s").join_url
        == "wss://rtc.example.com"
    )


def test_tokens_expire_in_the_future() -> None:
    provider = LiveKitMediaProvider("wss://rtc.example.com", "k", "s")
    _token, expires_at = provider.create_join_token(
        room="rd_x",
        identity="viewer:pt_abcdefghijklmnop",
        can_publish=False,
        can_subscribe=True,
        can_publish_data=False,
        ttl_seconds=60,
    )
    expiry = datetime.fromisoformat(expires_at)
    assert expiry > datetime.now(UTC) - timedelta(seconds=5)

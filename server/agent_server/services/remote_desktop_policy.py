"""Policy projection and host facts for remote desktop.

Capabilities are composed on the Server: the Connector only reports host facts,
never ``allowed``. Facts are cached for five seconds in the remote desktop
broker so a capability poll does not hit the device on every request.
"""

from __future__ import annotations

import asyncio
from collections.abc import Mapping
from typing import Any, Protocol

from loguru import logger
from pydantic import ValidationError

from agent_server.core.device_settings import (
    ConnectorDeviceSettings,
    device_settings_from_stored,
)
from agent_server.core.remote_desktop import (
    REMOTE_DESKTOP_CAPABILITY_CLIPBOARD,
    REMOTE_DESKTOP_CAPABILITY_CONTROL,
    REMOTE_DESKTOP_CAPABILITY_VIEW,
    HostFacts,
    RemoteDesktopCapability,
    RemoteDesktopSession,
)

HOST_FACTS_CACHE_PREFIX = "hostinfo"
HOST_FACTS_CACHE_TTL_SECONDS = 5
HOST_FACTS_TIMEOUT_SECONDS = 5.0
CLIPBOARD_MAX_BYTES = 65_536
SUPPORTED_CODECS = ["vp8"]


class HostFactsRequester(Protocol):
    async def request(
        self,
        connector_id: str,
        method: str,
        params: dict[str, Any],
        *,
        timeout: float = 30,
    ) -> Any: ...


class HostFactsCache(Protocol):
    async def get_json(self, key: str) -> Any | None: ...

    async def set_json(self, key: str, value: Any, *, ttl_seconds: int) -> None: ...


class DeviceSettingsStore(Protocol):
    async def get_connector_device_settings(self, connector_id: str) -> dict[str, Any]: ...


def _unavailable_host_facts(reason: str) -> HostFacts:
    factory = getattr(HostFacts, "unavailable", None)
    if callable(factory):
        return factory(
            reason,
            supported=reason not in {
                "connector_outdated",
                "host_not_installed",
                "unsupported_platform",
            },
        )
    payload = {
        "supported": False,
        "available": False,
        "unavailableReason": reason,
        "platform": "unknown",
        "arch": "unknown",
        "hostVersion": None,
        "displays": [],
        "codecs": SUPPORTED_CODECS,
        "maxFps": None,
        "activeSessions": 0,
    }
    try:
        return HostFacts.model_validate(payload)
    except ValidationError:  # pragma: no cover - defensive for older core models
        return HostFacts()


def _facts_reason(facts: HostFacts | None) -> str | None:
    if facts is None:
        return "connector_offline"
    return getattr(facts, "unavailableReason", None) or getattr(
        facts, "unavailable_reason", None
    )


def _facts_supported(facts: HostFacts | None) -> bool:
    if facts is None:
        return True
    return bool(getattr(facts, "supported", True))


def _facts_available(facts: HostFacts | None) -> bool:
    # A host that never reported facts is treated as offline, not unavailable.
    if facts is None:
        return False
    return bool(getattr(facts, "available", False))


def _capability(
    *,
    capability_id: str,
    connector_id: str,
    supported: bool,
    available: bool,
    allowed: bool,
    reason: str | None,
    revision: int,
    metadata: Mapping[str, Any],
) -> RemoteDesktopCapability:
    payload = {
        "capabilityId": capability_id,
        "scope": "connector",
        "connectorId": connector_id,
        "supported": supported,
        "available": available,
        "allowed": allowed,
        "unavailableReason": reason,
        "revision": revision,
        "metadata": dict(metadata),
    }
    model = RemoteDesktopCapability.model_validate(payload)
    # ``model_validate`` drops unknown fields on old core revisions; keep the
    # projected values available to callers that serialize the model directly.
    for name, value in payload.items():
        if not hasattr(model, name):
            try:
                object.__setattr__(model, name, value)
            except (AttributeError, ValueError):  # pragma: no cover - pydantic guard
                continue
    return model


def build_capabilities_sync(
    connector_id: str,
    settings: ConnectorDeviceSettings,
    host_facts: HostFacts | None,
    *,
    media_available: bool,
    active_session: RemoteDesktopSession | None,
    viewer_count: int,
    max_viewers: int,
    revision: int,
) -> list[RemoteDesktopCapability]:
    """Compose the three capability objects (pure; handy for unit tests)."""

    facts_supported = _facts_supported(host_facts)
    facts_available = _facts_available(host_facts)
    facts_reason = _facts_reason(host_facts)
    # A stopped host process is started on demand by `remoteDesktop.start`, so
    # it is not a capability blocker. Reporting available=false here would hide
    # the entry point on every device that has not been used yet.
    auto_start = facts_reason == "host_not_running"

    supported = facts_supported and media_available
    available = (facts_available or auto_start) and media_available
    if not media_available:
        reason: str | None = "media_not_configured"
    elif not facts_supported:
        reason = facts_reason or "unsupported_platform"
    elif not facts_available and not auto_start:
        reason = facts_reason or "host_not_running"
    else:
        reason = None

    session_limit_reached = bool(
        active_session is not None and viewer_count >= max_viewers
    )
    view_allowed = settings.remoteDesktopEnabled and not session_limit_reached
    if session_limit_reached:
        reason = "session_limit_reached"
    control_allowed = view_allowed and settings.remoteDesktopAllowControl
    clipboard_allowed = control_allowed and settings.remoteDesktopClipboard
    view_reason = reason
    if view_reason is None and not view_allowed:
        view_reason = "device_policy_disabled"
    control_reason = reason
    if control_reason is None and not control_allowed:
        control_reason = "device_policy_disabled"
    clipboard_reason = reason
    if clipboard_reason is None and not clipboard_allowed:
        clipboard_reason = "device_policy_disabled"

    max_fps = getattr(host_facts, "maxFps", None) if host_facts else None
    metadata: dict[str, Any] = {
        "maxFps": max_fps if isinstance(max_fps, int) else settings.remoteDesktopMaxFps,
        "codecs": list(SUPPORTED_CODECS),
        "clipboard": settings.remoteDesktopClipboard,
    }
    clipboard_metadata = {**metadata, "maxBytes": CLIPBOARD_MAX_BYTES}

    return [
        _capability(
            capability_id=REMOTE_DESKTOP_CAPABILITY_VIEW,
            connector_id=connector_id,
            supported=supported,
            available=available,
            allowed=view_allowed,
            reason=view_reason,
            revision=revision,
            metadata=metadata,
        ),
        _capability(
            capability_id=REMOTE_DESKTOP_CAPABILITY_CONTROL,
            connector_id=connector_id,
            supported=supported,
            available=available,
            allowed=control_allowed,
            reason=control_reason,
            revision=revision,
            metadata=metadata,
        ),
        _capability(
            capability_id=REMOTE_DESKTOP_CAPABILITY_CLIPBOARD,
            connector_id=connector_id,
            supported=supported,
            available=available,
            allowed=clipboard_allowed,
            reason=clipboard_reason,
            revision=revision,
            metadata=clipboard_metadata,
        ),
    ]


async def build_capabilities(
    connector_id: str,
    settings: ConnectorDeviceSettings,
    host_facts: HostFacts | None,
    *,
    media_available: bool,
    active_session: RemoteDesktopSession | None,
    viewer_count: int,
    max_viewers: int,
    revision: int,
) -> list[RemoteDesktopCapability]:
    return build_capabilities_sync(
        connector_id,
        settings,
        host_facts,
        media_available=media_available,
        active_session=active_session,
        viewer_count=viewer_count,
        max_viewers=max_viewers,
        revision=revision,
    )


def _error_reason(exc: BaseException) -> str:
    code = str(getattr(exc, "code", "") or "")
    message = str(exc).lower()
    if isinstance(exc, (TimeoutError, asyncio.TimeoutError)):
        return "connector_offline"
    if code in {"rd_host_not_running", "host_not_running"}:
        return "host_not_running"
    if code in {
        "method_not_found",
        "unknown_method",
        "unsupported_method",
        "rd_unsupported_method",
    }:
        return "connector_outdated"
    if "method not found" in message or "unknown method" in message:
        return "connector_outdated"
    if exc.__class__.__name__ in {"ConnectorOfflineError", "ConnectorUnavailableError"}:
        return "connector_offline"
    if code.startswith("rd_"):
        return "host_not_running"
    return "connector_offline"


def _extract_host_facts(result: Any) -> HostFacts:
    if isinstance(result, HostFacts):
        return result
    raw = result
    if isinstance(raw, Mapping):
        nested = raw.get("host")
        if isinstance(nested, Mapping):
            raw = nested
    if not isinstance(raw, Mapping):
        raise TypeError("remoteDesktop.hostInfo returned a non-object")
    return HostFacts.model_validate(dict(raw))


async def host_facts(
    connector_id: str,
    requester: HostFactsRequester,
    broker: HostFactsCache,
) -> HostFacts:
    """Read the five-second host facts cache, then ask the Connector."""

    cache_key = f"{HOST_FACTS_CACHE_PREFIX}:{connector_id}"
    cached = await broker.get_json(cache_key)
    if isinstance(cached, Mapping):
        try:
            return HostFacts.model_validate(dict(cached))
        except ValidationError:
            logger.warning(
                "ignoring invalid cached remote desktop host facts connector_id={}",
                connector_id,
            )
    try:
        result = await requester.request(
            connector_id,
            "remoteDesktop.hostInfo",
            {},
            timeout=HOST_FACTS_TIMEOUT_SECONDS,
        )
        facts = _extract_host_facts(result)
    except asyncio.CancelledError:
        raise
    except Exception as exc:  # noqa: BLE001 - offline/old hosts are capability states
        reason = _error_reason(exc)
        logger.info(
            "remote desktop host facts unavailable connector_id={} reason={}",
            connector_id,
            reason,
        )
        facts = _unavailable_host_facts(reason)
    await broker.set_json(
        cache_key, facts.model_dump(mode="json"), ttl_seconds=HOST_FACTS_CACHE_TTL_SECONDS
    )
    return facts


async def current_settings(
    store: DeviceSettingsStore, connector_id: str
) -> ConnectorDeviceSettings:
    return device_settings_from_stored(
        await store.get_connector_device_settings(connector_id)
    )

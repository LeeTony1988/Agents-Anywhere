from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any

import pytest

from connector.core.config import ConnectorConfig
from connector.local.remote_desktop.host import (
    ScreenHostNotRunning,
    ScreenHostRemoteError,
)
from connector.local.remote_desktop.policy import RemoteDesktopPolicy
from connector.server.remote_desktop_rpc import (
    RemoteDesktopRpcError,
    RemoteDesktopRpcHandler,
)


def _settings(**overrides: Any) -> dict[str, Any]:
    settings: dict[str, Any] = {
        "remoteDesktopEnabled": True,
        "remoteDesktopAllowControl": True,
        "remoteDesktopRequireConsent": False,
        "remoteDesktopAllowUnattended": True,
        "remoteDesktopClipboard": False,
        "remoteDesktopMaxFps": 30,
        "remoteDesktopMaxBitrateKbps": 4000,
    }
    settings.update(overrides)
    return settings


def _config() -> ConnectorConfig:
    return ConnectorConfig(
        server_url="https://example.test",
        connector_id="connector-1",
        connector_token="token",
        remote_desktop_enabled=True,
    )


class _StubSupervisor:
    def __init__(
        self,
        *,
        installed: bool = True,
        running: bool = False,
        active_session_id: str | None = None,
        controller_identity: str | None = None,
        info: dict[str, Any] | None = None,
    ) -> None:
        self.installed = installed
        self.running = running
        self.active_session_id = active_session_id
        self.controller_identity = controller_identity
        self.platform = "windows"
        self.arch = "x86_64"
        self.host_path = Path("stub-screen-host") if installed else None
        self.info_result = info or {
            "supported": True,
            "available": True,
            "platform": "windows",
            "arch": "x86_64",
            "hostVersion": "9.9.9",
            "pid": 321,
            "displays": [
                {
                    "id": "1",
                    "name": "Fake",
                    "width": 1280,
                    "height": 720,
                    "scale": 1.0,
                    "primary": True,
                }
            ],
            "codecs": ["vp8"],
            "maxFps": 30,
            "input": {
                "pointer": True,
                "keyboard": True,
                "touch": False,
                "clipboard": False,
            },
            "permissions": {
                "screenRecording": "granted",
                "accessibility": "granted",
                "inputMonitoring": "granted",
            },
            "activeSessions": 1,
        }
        self.start_calls: list[dict[str, Any]] = []
        self.quality_calls: list[dict[str, Any]] = []
        self.control_calls: list[tuple[str, str | None]] = []
        self.input_calls: list[dict[str, Any]] = []
        self.event_handler = None
        self.start_error: Exception | None = None

    def set_event_handler(self, handler) -> None:
        self.event_handler = handler

    async def info(self) -> dict[str, Any]:
        if not self.running:
            raise ScreenHostNotRunning("not running")
        return dict(self.info_result)

    async def start(self, session_id: str, **kwargs: Any) -> dict[str, Any]:
        if self.start_error is not None:
            raise self.start_error
        self.start_calls.append({"session_id": session_id, **kwargs})
        self.running = True
        self.active_session_id = session_id
        return {"accepted": True}

    async def stop(self, session_id: str | None = None, **kwargs: Any) -> dict[str, Any]:
        self.running = False
        self.active_session_id = None
        self.controller_identity = None
        return {"stopped": True}

    async def update_quality(
        self, session_id: str, quality: dict[str, Any], **kwargs: Any
    ) -> dict[str, Any]:
        self.quality_calls.append(dict(quality))
        return {"applied": True}

    async def control_grant(
        self,
        session_id: str,
        controller_identity: str | None,
        **kwargs: Any,
    ) -> dict[str, Any]:
        self.controller_identity = controller_identity
        self.control_calls.append((session_id, controller_identity))
        return {"applied": True}

    async def request_keyframe(self, session_id: str | None = None) -> dict[str, Any]:
        return {"requested": True}

    async def release_all(self, session_id: str | None = None) -> dict[str, Any]:
        return {"releasedKeys": 0, "releasedButtons": 0}

    async def screenshot(self, session_id: str | None = None, **kwargs: Any) -> dict[str, Any]:
        return {
            "dataBase64": "aGVsbG8=",
            "width": 1,
            "height": 1,
            "capturedAt": "2026-11-13T00:00:00Z",
        }

    async def input(
        self,
        session_id: str,
        events: list[dict[str, Any]],
        *,
        identity: str | None = None,
    ) -> dict[str, Any]:
        self.input_calls.append({"session_id": session_id, "events": events, "identity": identity})
        return {"appliedSeq": events[-1].get("seq", 0) if events else 0}

    async def shutdown(self) -> None:
        self.running = False


class _Prompt:
    def __init__(self, granted: bool) -> None:
        self.granted = granted
        self.calls: list[tuple[str, float]] = []

    async def request_consent(
        self, *, session_id: str, timeout_seconds: float
    ) -> bool:
        self.calls.append((session_id, timeout_seconds))
        return self.granted


def _handler(
    supervisor: _StubSupervisor | None = None,
    *,
    policy: RemoteDesktopPolicy | None = None,
    notify=None,
    state_sink=None,
    consent_prompt=None,
    stats_coalesce_seconds: float = 2.0,
) -> RemoteDesktopRpcHandler:
    stub = supervisor or _StubSupervisor()
    return RemoteDesktopRpcHandler(
        supervisor=stub,  # type: ignore[arg-type]
        policy=policy or RemoteDesktopPolicy.from_device_settings(_settings()),
        notify=notify,
        state_sink=state_sink,
        consent_prompt=consent_prompt,
        config=_config(),
        stats_coalesce_seconds=stats_coalesce_seconds,
    )


def test_host_info_reports_not_installed() -> None:
    supervisor = _StubSupervisor(installed=False, running=False)

    async def scenario() -> dict[str, Any]:
        return await _handler(supervisor).host_info({})

    facts = asyncio.run(scenario())
    assert facts["supported"] is False
    assert facts["available"] is False
    assert facts["unavailableReason"] == "host_not_installed"
    assert facts["platform"] in {"windows", "macos", "linux"}
    assert facts["codecs"] == ["vp8"]
    assert facts["permissions"]["screenRecording"] == "unsupported"


def test_host_info_reports_host_not_running() -> None:
    supervisor = _StubSupervisor(installed=True, running=False)

    async def scenario() -> dict[str, Any]:
        return await _handler(supervisor).host_info({})

    facts = asyncio.run(scenario())
    assert facts["supported"] is True
    assert facts["available"] is False
    assert facts["unavailableReason"] == "host_not_running"
    assert facts["activeSessions"] == 0


def test_host_info_normalizes_host_facts() -> None:
    supervisor = _StubSupervisor(
        installed=True, running=True, active_session_id="rds_1"
    )

    async def scenario() -> dict[str, Any]:
        return await _handler(supervisor).host_info({})

    facts = asyncio.run(scenario())
    assert facts["supported"] is True
    assert facts["available"] is True
    assert facts["hostVersion"] == "9.9.9"
    assert facts["pid"] == 321
    assert facts["codecs"] == ["vp8"]
    assert facts["maxFps"] == 30
    assert facts["input"]["pointer"] is True
    assert facts["permissions"]["inputMonitoring"] == "granted"
    assert facts["activeSessions"] == 1


def test_host_info_rejects_unknown_fields() -> None:
    async def scenario() -> None:
        with pytest.raises(RemoteDesktopRpcError) as raised:
            await _handler().host_info({"unexpected": True})
        assert raised.value.code == "rd_invalid_request"

    asyncio.run(scenario())


def test_start_rejects_unknown_fields() -> None:
    async def scenario() -> None:
        with pytest.raises(RemoteDesktopRpcError) as raised:
            await _handler().start({"sessionId": "rds_1", "unexpected": 1})
        assert raised.value.code == "rd_invalid_request"

    asyncio.run(scenario())


def test_start_clamps_quality_and_passes_consent_false() -> None:
    supervisor = _StubSupervisor()
    policy = RemoteDesktopPolicy.from_device_settings(
        _settings(
            remoteDesktopMaxFps=60,
            remoteDesktopMaxBitrateKbps=20_000,
        )
    )

    async def scenario() -> dict[str, Any]:
        return await _handler(supervisor, policy=policy).start(
            {
                "sessionId": "rds_1",
                "mode": "view",
                "quality": {
                    "maxFps": 120,
                    "maxBitrateKbps": 99_999,
                    "maxWidth": 9_999,
                },
            }
        )

    result = asyncio.run(scenario())
    assert result == {"accepted": True, "state": "starting"}
    call = supervisor.start_calls[0]
    assert call["quality"] == {
        "maxFps": 60,
        "maxBitrateKbps": 20_000,
        "maxWidth": 3840,
        "preferCodec": "vp8",
    }
    assert call["consent_required"] is False


def test_start_is_denied_when_remote_desktop_is_disabled() -> None:
    supervisor = _StubSupervisor()
    policy = RemoteDesktopPolicy.from_device_settings(
        _settings(remoteDesktopEnabled=False)
    )

    async def scenario() -> None:
        with pytest.raises(RemoteDesktopRpcError) as raised:
            await _handler(supervisor, policy=policy).start(
                {"sessionId": "rds_1", "mode": "view"}
            )
        assert raised.value.code == "rd_permission_denied"
        assert supervisor.start_calls == []

    asyncio.run(scenario())


def test_start_control_is_denied_without_allow_control() -> None:
    supervisor = _StubSupervisor()
    policy = RemoteDesktopPolicy.from_device_settings(
        _settings(remoteDesktopAllowControl=False)
    )

    async def scenario() -> None:
        with pytest.raises(RemoteDesktopRpcError) as raised:
            await _handler(supervisor, policy=policy).start(
                {"sessionId": "rds_1", "mode": "control"}
            )
        assert raised.value.code == "rd_permission_denied"

    asyncio.run(scenario())


def test_consent_fails_closed_without_a_prompt() -> None:
    supervisor = _StubSupervisor()
    notifications: list[tuple[str, dict[str, Any]]] = []

    async def notify(method: str, params: dict[str, Any]) -> None:
        notifications.append((method, params))

    policy = RemoteDesktopPolicy.from_device_settings(
        _settings(
            remoteDesktopRequireConsent=True,
            remoteDesktopAllowUnattended=False,
        )
    )

    async def scenario() -> None:
        with pytest.raises(RemoteDesktopRpcError) as raised:
            await _handler(supervisor, policy=policy, notify=notify).start(
                {"sessionId": "rds_1", "mode": "control"}
            )
        assert raised.value.code == "rd_permission_denied"
        assert supervisor.start_calls == []

    asyncio.run(scenario())
    assert notifications[0][0] == "remoteDesktop.consent"
    assert notifications[0][1]["decision"] == "denied"


def test_consent_prompt_grant_is_reported_and_started() -> None:
    supervisor = _StubSupervisor()
    notifications: list[tuple[str, dict[str, Any]]] = []
    prompt = _Prompt(True)

    async def notify(method: str, params: dict[str, Any]) -> None:
        notifications.append((method, params))

    policy = RemoteDesktopPolicy.from_device_settings(
        _settings(
            remoteDesktopRequireConsent=True,
            remoteDesktopAllowUnattended=False,
        )
    )

    async def scenario() -> dict[str, Any]:
        return await _handler(
            supervisor, policy=policy, notify=notify, consent_prompt=prompt
        ).start({"sessionId": "rds_1", "mode": "control"})

    result = asyncio.run(scenario())
    assert result["accepted"] is True
    assert prompt.calls == [("rds_1", 60.0)]
    assert notifications[0][1]["decision"] == "granted"
    assert supervisor.start_calls[0]["consent_required"] is False


def test_start_maps_host_not_running() -> None:
    supervisor = _StubSupervisor()
    supervisor.start_error = ScreenHostNotRunning("gone")

    async def scenario() -> None:
        with pytest.raises(RemoteDesktopRpcError) as raised:
            await _handler(supervisor).start({"sessionId": "rds_1"})
        assert raised.value.code == "rd_host_not_running"

    asyncio.run(scenario())


def test_update_quality_and_control_grant() -> None:
    supervisor = _StubSupervisor(active_session_id="rds_1", running=True)

    async def scenario() -> None:
        handler = _handler(supervisor)
        await handler.update_quality(
            {"sessionId": "rds_1", "maxFps": 999, "maxBitrateKbps": 99_999}
        )
        await handler.control_grant(
            {"sessionId": "rds_1", "controllerIdentity": "viewer:pt_1"}
        )

    asyncio.run(scenario())
    assert supervisor.quality_calls == [
        {"maxFps": 30, "maxBitrateKbps": 4000, "maxWidth": 1600}
    ]
    assert supervisor.control_calls == [("rds_1", "viewer:pt_1")]


def test_input_requires_a_granted_controller() -> None:
    supervisor = _StubSupervisor(active_session_id="rds_1", running=True)

    async def scenario() -> None:
        with pytest.raises(RemoteDesktopRpcError) as raised:
            await _handler(supervisor).input(
                {"sessionId": "rds_1", "events": [{"v": 1, "t": "ping"}]}
            )
        assert raised.value.code == "rd_permission_denied"

    asyncio.run(scenario())


def test_input_passes_controller_identity_and_returns_applied_seq() -> None:
    supervisor = _StubSupervisor(
        active_session_id="rds_1",
        running=True,
        controller_identity="viewer:pt_1",
    )

    async def scenario() -> dict[str, Any]:
        return await _handler(supervisor).input(
            {
                "sessionId": "rds_1",
                "events": [{"v": 1, "t": "ping", "seq": 7}],
            }
        )

    result = asyncio.run(scenario())
    assert result == {"appliedSeq": 7}
    assert supervisor.input_calls[0]["identity"] == "viewer:pt_1"


def test_screenshot_validation_and_result() -> None:
    supervisor = _StubSupervisor(active_session_id="rds_1", running=True)

    async def scenario() -> dict[str, Any]:
        return await _handler(supervisor).screenshot(
            {"sessionId": "rds_1", "format": "png"}
        )

    result = asyncio.run(scenario())
    assert result["dataBase64"] == "aGVsbG8="


def test_screenshot_rejects_unknown_format() -> None:
    supervisor = _StubSupervisor(active_session_id="rds_1", running=True)

    async def scenario() -> None:
        with pytest.raises(RemoteDesktopRpcError) as raised:
            await _handler(supervisor).screenshot(
                {"sessionId": "rds_1", "format": "webp"}
            )
        assert raised.value.code == "rd_invalid_request"

    asyncio.run(scenario())


def test_state_and_error_events_become_notifications() -> None:
    supervisor = _StubSupervisor(active_session_id="rds_1", running=True)
    notifications: list[tuple[str, dict[str, Any]]] = []

    async def notify(method: str, params: dict[str, Any]) -> None:
        notifications.append((method, params))

    async def scenario() -> None:
        handler = _handler(supervisor, notify=notify)
        await handler.handle_host_event(
            "state", {"sessionId": "rds_1", "state": "running"}
        )
        await handler.handle_host_event(
            "error", {"code": "rd_capture_failed", "message": "boom"}
        )

    asyncio.run(scenario())
    assert notifications[0] == (
        "remoteDesktop.state",
        {"sessionId": "rds_1", "state": "running"},
    )
    assert notifications[1][0] == "remoteDesktop.error"
    assert notifications[1][1]["sessionId"] == "rds_1"
    assert notifications[1][1]["retryable"] is False


def test_stats_are_coalesced_for_two_seconds_by_default() -> None:
    supervisor = _StubSupervisor(active_session_id="rds_1", running=True)
    notifications: list[tuple[str, dict[str, Any]]] = []

    async def notify(method: str, params: dict[str, Any]) -> None:
        notifications.append((method, params))

    async def scenario() -> None:
        handler = _handler(
            supervisor, notify=notify, stats_coalesce_seconds=0.05
        )
        await handler.handle_host_event(
            "stats", {"sessionId": "rds_1", "fps": 10.0}
        )
        await handler.handle_host_event(
            "stats", {"sessionId": "rds_1", "fps": 20.0}
        )
        await asyncio.sleep(0.15)
        await handler.aclose()

    asyncio.run(scenario())
    stats = [params for method, params in notifications if method == "remoteDesktop.stats"]
    assert len(stats) == 1
    assert stats[0]["fps"] == 20.0


def test_displays_event_is_wrapped_for_the_server() -> None:
    supervisor = _StubSupervisor(active_session_id="rds_1", running=True)
    notifications: list[tuple[str, dict[str, Any]]] = []

    async def notify(method: str, params: dict[str, Any]) -> None:
        notifications.append((method, params))

    async def scenario() -> None:
        await _handler(supervisor, notify=notify).handle_host_event(
            "displays", {"displays": [{"id": "1"}]}
        )

    asyncio.run(scenario())
    assert notifications[0][0] == "remoteDesktop.displays"
    assert notifications[0][1]["displays"] == [{"id": "1"}]


def test_supports_and_unknown_method() -> None:
    handler = _handler()
    assert handler.supports("remoteDesktop.hostInfo")
    assert handler.supports("remoteDesktop.control.grant")
    assert not handler.supports("remoteDesktop.unknown")

    async def scenario() -> None:
        with pytest.raises(RemoteDesktopRpcError) as raised:
            await handler.dispatch("remoteDesktop.unknown", {})
        assert raised.value.code == "rd_unsupported_method"

    asyncio.run(scenario())


def test_apply_device_settings_updates_policy() -> None:
    handler = _handler(
        policy=RemoteDesktopPolicy.from_device_settings(
            _settings(remoteDesktopEnabled=False)
        )
    )
    handler.apply_device_settings(_settings(remoteDesktopEnabled=True))
    assert handler.policy.enabled is True


def test_remote_error_code_mapping() -> None:
    from connector.server.remote_desktop_rpc import rpc_error_from_host_error

    display_error = rpc_error_from_host_error(
        ScreenHostRemoteError("no displays available", code="rd_capture_failed"),
        display_id="2",
    )
    assert display_error.code == "rd_display_not_found"

    controller_error = rpc_error_from_host_error(
        ScreenHostRemoteError("identity is not the controller", code="rd_not_controller")
    )
    assert controller_error.code == "rd_permission_denied"

def test_host_state_updates_local_sharing_indicator() -> None:
    states: list[dict[str, Any]] = []

    async def sink(value: dict[str, Any]) -> None:
        states.append(dict(value))

    async def scenario() -> None:
        handler = _handler(state_sink=sink)
        await handler.handle_host_event(
            "state", {"sessionId": "rds_1", "state": "running"}
        )
        await handler.handle_host_event(
            "state", {"sessionId": "rds_1", "state": "stopped"}
        )

    asyncio.run(scenario())
    assert states[0]["active"] is True
    assert states[0]["state"] == "running"
    assert states[1]["active"] is False
    assert states[1]["state"] == "stopped"

def test_synthetic_capture_is_forwarded_to_the_host_only_on_request(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The supervisor filters `AA_*`, so dev-only switches must be explicit."""

    from connector.server import remote_desktop_rpc

    monkeypatch.delenv("AGENT_CONNECTOR_REMOTE_DESKTOP_SYNTHETIC_CAPTURE", raising=False)
    assert remote_desktop_rpc._host_extra_env() == {}

    for value in ("1", "true", "YES", "on"):
        monkeypatch.setenv("AGENT_CONNECTOR_REMOTE_DESKTOP_SYNTHETIC_CAPTURE", value)
        assert remote_desktop_rpc._host_extra_env() == {
            "AA_SCREEN_HOST_SYNTHETIC_CAPTURE": "1"
        }

    monkeypatch.setenv("AGENT_CONNECTOR_REMOTE_DESKTOP_SYNTHETIC_CAPTURE", "0")
    assert remote_desktop_rpc._host_extra_env() == {}

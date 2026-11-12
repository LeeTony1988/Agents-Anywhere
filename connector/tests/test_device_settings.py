from __future__ import annotations

import asyncio
from typing import Any

from connector.control import ConnectorController
from connector.core.device_settings import (
    DEVICE_SETTINGS_APPLY_METHOD,
    normalize_device_settings,
)
from connector.mesh.control import ConnectorState
from connector.server.dispatch import ConnectorRequestSession


class _Unsupported:
    def supports(self, method: str) -> bool:
        return False


DEFAULT_SETTINGS: dict[str, Any] = {
    "confirmQuitOnShutdown": False,
    "remoteDesktopEnabled": False,
    "remoteDesktopAllowControl": False,
    "remoteDesktopRequireConsent": True,
    "remoteDesktopAllowUnattended": False,
    "remoteDesktopClipboard": False,
    "remoteDesktopMaxFps": 30,
    "remoteDesktopMaxBitrateKbps": 4000,
}


def test_normalize_keeps_known_booleans_and_defaults_the_rest() -> None:
    assert normalize_device_settings(None) == DEFAULT_SETTINGS
    assert normalize_device_settings({"confirmQuitOnShutdown": "yes", "future": 1}) == DEFAULT_SETTINGS
    assert normalize_device_settings(
        {"confirmQuitOnShutdown": True, "remoteDesktopMaxFps": 45}
    ) == {**DEFAULT_SETTINGS, "confirmQuitOnShutdown": True, "remoteDesktopMaxFps": 45}
    assert normalize_device_settings({"remoteDesktopMaxFps": 999}) == DEFAULT_SETTINGS


def test_apply_hands_normalized_settings_to_the_host() -> None:
    received: list[dict[str, Any]] = []

    async def sink(settings: dict[str, Any]) -> None:
        received.append(settings)

    session = ConnectorRequestSession(_Unsupported(), _Unsupported(), device_settings_sink=sink)  # type: ignore[arg-type]
    result = asyncio.run(
        session.dispatch(
            DEVICE_SETTINGS_APPLY_METHOD,
            {"settings": {"confirmQuitOnShutdown": True, "future": "x"}},
        )
    )

    assert result == {"applied": True}
    assert received == [{**DEFAULT_SETTINGS, "confirmQuitOnShutdown": True}]


def test_apply_without_a_host_still_acknowledges() -> None:
    session = ConnectorRequestSession(_Unsupported(), _Unsupported())  # type: ignore[arg-type]
    assert asyncio.run(session.dispatch(DEVICE_SETTINGS_APPLY_METHOD, {})) == {"applied": True}


def test_controller_publishes_device_settings_in_its_state(tmp_path) -> None:
    events: list[tuple[str, Any]] = []

    async def notifier(method: str, params: Any) -> None:
        events.append((method, params))

    async def run() -> None:
        controller = ConnectorController(config_path=tmp_path / "connector.json", notifier=notifier)
        assert controller.get_state()["deviceSettings"] is None
        await controller._apply_device_settings({"confirmQuitOnShutdown": True})
        await controller._apply_device_settings({"confirmQuitOnShutdown": True})
        state = controller.get_state()
        assert state["deviceSettings"] == {**DEFAULT_SETTINGS, "confirmQuitOnShutdown": True}
        # The control surface model accepts the published state.
        assert ConnectorState.model_validate(state).deviceSettings.confirmQuitOnShutdown is True

    asyncio.run(run())
    # A repeated delivery of the same settings does not emit another state event.
    assert [method for method, _ in events] == ["connector/state"]

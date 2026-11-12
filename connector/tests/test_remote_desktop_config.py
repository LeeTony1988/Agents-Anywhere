from __future__ import annotations

import json

from connector.core.config import (
    REMOTE_DESKTOP_ENV_ENABLED,
    REMOTE_DESKTOP_ENV_HOST_PATH,
    ConnectorConfig,
)
from connector.core.device_settings import normalize_device_settings


def test_remote_desktop_config_defaults() -> None:
    config = ConnectorConfig.from_mapping(
        {
            "serverUrl": "https://example.test/",
            "connectorId": "connector-1",
            "connectorToken": "token",
        }
    )
    assert config.remote_desktop_enabled is False
    assert config.remote_desktop_host_path is None
    assert config.remote_desktop_max_viewers == 3
    assert config.remote_desktop_preferred_encoder == "vp8"


def test_remote_desktop_config_roundtrip_writes_nested(tmp_path) -> None:
    path = tmp_path / "connector.json"
    config = ConnectorConfig(
        server_url="https://example.test",
        connector_id="connector-1",
        connector_token="token",
        remote_desktop_enabled=True,
        remote_desktop_host_path="C:/tools/aa-screen-host.exe",
        remote_desktop_max_viewers=5,
        remote_desktop_preferred_encoder="VP8",
    )
    saved = config.save(path)
    raw = json.loads(saved.read_text(encoding="utf-8"))
    assert raw["remoteDesktop"] == {
        "enabled": True,
        "hostPath": "C:/tools/aa-screen-host.exe",
        "maxViewers": 5,
        "preferredEncoder": "vp8",
    }
    assert "remoteDesktopEnabled" not in raw
    loaded = ConnectorConfig.load(path)
    assert loaded.remote_desktop_enabled is True
    assert loaded.remote_desktop_host_path == "C:/tools/aa-screen-host.exe"
    assert loaded.remote_desktop_max_viewers == 5
    assert loaded.remote_desktop_preferred_encoder == "vp8"


def test_legacy_nested_remote_desktop_is_read() -> None:
    config = ConnectorConfig.from_mapping(
        {
            "serverUrl": "https://example.test",
            "connectorId": "connector-1",
            "connectorToken": "token",
            "remoteDesktop": {
                "enabled": True,
                "hostPath": "/opt/aa-screen-host",
                "maxViewers": 7,
                "preferredEncoder": "vp8",
            },
        }
    )
    assert config.remote_desktop_enabled is True
    assert config.remote_desktop_host_path == "/opt/aa-screen-host"
    assert config.remote_desktop_max_viewers == 7


def test_flat_fields_override_legacy_nested() -> None:
    config = ConnectorConfig.from_mapping(
        {
            "serverUrl": "https://example.test",
            "connectorId": "connector-1",
            "connectorToken": "token",
            "remoteDesktopEnabled": False,
            "remoteDesktopMaxViewers": 2,
            "remoteDesktop": {
                "enabled": True,
                "maxViewers": 9,
            },
        }
    )
    assert config.remote_desktop_enabled is False
    assert config.remote_desktop_max_viewers == 2


def test_remote_desktop_env_variables(monkeypatch) -> None:
    monkeypatch.setenv("AGENT_SERVER_URL", "https://example.test")
    monkeypatch.setenv("AGENT_CONNECTOR_ID", "connector-1")
    monkeypatch.setenv("AGENT_CONNECTOR_TOKEN", "token")
    monkeypatch.setenv(REMOTE_DESKTOP_ENV_ENABLED, "true")
    monkeypatch.setenv(REMOTE_DESKTOP_ENV_HOST_PATH, "/opt/aa-screen-host")
    config = ConnectorConfig.from_env()
    assert config.remote_desktop_enabled is True
    assert config.remote_desktop_host_path == "/opt/aa-screen-host"


def test_remote_desktop_config_is_bounded() -> None:
    config = ConnectorConfig.from_mapping(
        {
            "serverUrl": "https://example.test",
            "connectorId": "connector-1",
            "connectorToken": "token",
            "remoteDesktopMaxViewers": 99,
        }
    )
    assert config.remote_desktop_max_viewers == 16


def test_device_settings_remote_desktop_defaults() -> None:
    settings = normalize_device_settings(None)
    assert settings == {
        "confirmQuitOnShutdown": False,
        "remoteDesktopEnabled": False,
        "remoteDesktopAllowControl": False,
        "remoteDesktopRequireConsent": True,
        "remoteDesktopAllowUnattended": False,
        "remoteDesktopClipboard": False,
        "remoteDesktopMaxFps": 30,
        "remoteDesktopMaxBitrateKbps": 4000,
    }


def test_device_settings_remote_desktop_ranges() -> None:
    settings = normalize_device_settings(
        {
            "remoteDesktopEnabled": True,
            "remoteDesktopMaxFps": 45,
            "remoteDesktopMaxBitrateKbps": 8000,
            "remoteDesktopAllowControl": "yes",
        }
    )
    assert settings["remoteDesktopEnabled"] is True
    assert settings["remoteDesktopMaxFps"] == 45
    assert settings["remoteDesktopMaxBitrateKbps"] == 8000
    assert settings["remoteDesktopAllowControl"] is False

    out_of_range = normalize_device_settings(
        {
            "remoteDesktopMaxFps": 4,
            "remoteDesktopMaxBitrateKbps": 99_999,
        }
    )
    assert out_of_range["remoteDesktopMaxFps"] == 30
    assert out_of_range["remoteDesktopMaxBitrateKbps"] == 4000

    booleans_are_not_integers = normalize_device_settings(
        {"remoteDesktopMaxFps": True}
    )
    assert booleans_are_not_integers["remoteDesktopMaxFps"] == 30

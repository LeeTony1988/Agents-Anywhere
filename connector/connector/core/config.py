from __future__ import annotations

import json
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from connector.paths import connector_data_dir

REMOTE_DESKTOP_ENV_ENABLED = "AGENT_CONNECTOR_REMOTE_DESKTOP_ENABLED"
REMOTE_DESKTOP_ENV_HOST_PATH = "AGENT_CONNECTOR_REMOTE_DESKTOP_HOST_PATH"
REMOTE_DESKTOP_DEFAULT_MAX_VIEWERS = 3
REMOTE_DESKTOP_DEFAULT_ENCODER = "vp8"

_ACTIVE_CONFIG: ConnectorConfig | None = None


@dataclass(slots=True)
class ConnectorConfig:
    server_url: str
    connector_id: str
    connector_token: str
    heartbeat_seconds: float = 20
    reconnect_seconds: float = 3
    sync_existing_on_connect: bool = True
    sync_interval_seconds: float = 30
    state_path: str | None = None
    # "system" (the default when unset), "direct", or a proxy URL.
    proxy: str | None = None
    # Remote desktop settings stay flat on the Connector; `save` still writes the
    # legacy nested object so older local tools keep reading the same file.
    remote_desktop_enabled: bool = False
    remote_desktop_host_path: str | None = None
    remote_desktop_max_viewers: int = REMOTE_DESKTOP_DEFAULT_MAX_VIEWERS
    remote_desktop_preferred_encoder: str = REMOTE_DESKTOP_DEFAULT_ENCODER

    def __post_init__(self) -> None:
        self.remote_desktop_max_viewers = _clamp_int(
            self.remote_desktop_max_viewers,
            minimum=1,
            maximum=16,
            default=REMOTE_DESKTOP_DEFAULT_MAX_VIEWERS,
        )
        self.remote_desktop_preferred_encoder = (
            self.remote_desktop_preferred_encoder.strip().lower()
            if isinstance(self.remote_desktop_preferred_encoder, str)
            and self.remote_desktop_preferred_encoder.strip()
            else REMOTE_DESKTOP_DEFAULT_ENCODER
        )
        _remember_active_config(self)

    @classmethod
    def default_path(cls) -> Path:
        configured = os.environ.get("AGENT_CONNECTOR_CONFIG")
        return (
            Path(configured).expanduser()
            if configured
            else connector_data_dir() / "connector.json"
        )

    @classmethod
    def from_env(cls) -> ConnectorConfig:
        missing = [
            name
            for name in (
                "AGENT_SERVER_URL",
                "AGENT_CONNECTOR_ID",
                "AGENT_CONNECTOR_TOKEN",
            )
            if not os.environ.get(name)
        ]
        if missing:
            raise RuntimeError(f"missing required environment variables: {', '.join(missing)}")
        return cls(
            server_url=os.environ["AGENT_SERVER_URL"].rstrip("/"),
            connector_id=os.environ["AGENT_CONNECTOR_ID"],
            connector_token=os.environ["AGENT_CONNECTOR_TOKEN"],
            heartbeat_seconds=float(os.environ.get("AGENT_CONNECTOR_HEARTBEAT_SECONDS", "20")),
            reconnect_seconds=float(os.environ.get("AGENT_CONNECTOR_RECONNECT_SECONDS", "3")),
            sync_existing_on_connect=_bool_env(
                "AGENT_CONNECTOR_SYNC_EXISTING",
                True,
            ),
            sync_interval_seconds=float(os.environ.get("AGENT_CONNECTOR_SYNC_INTERVAL_SECONDS", "30")),
            state_path=os.environ.get("AGENT_CONNECTOR_STATE_FILE"),
            remote_desktop_enabled=_bool_env(REMOTE_DESKTOP_ENV_ENABLED, False),
            remote_desktop_host_path=_optional_string(
                os.environ.get(REMOTE_DESKTOP_ENV_HOST_PATH)
            ),
        )

    @classmethod
    def load(cls, path: str | Path | None = None) -> ConnectorConfig:
        config_path = Path(path) if path is not None else cls.default_path()
        data = json.loads(config_path.read_text(encoding="utf-8-sig"))
        return cls.from_mapping(data)

    @classmethod
    def from_mapping(cls, data: dict[str, Any]) -> ConnectorConfig:
        nested = data.get("remoteDesktop")
        legacy = nested if isinstance(nested, dict) else {}
        return cls(
            server_url=str(data["serverUrl"]).rstrip("/"),
            connector_id=str(data["connectorId"]),
            connector_token=str(data["connectorToken"]),
            heartbeat_seconds=float(data.get("heartbeatSeconds", 20)),
            reconnect_seconds=float(data.get("reconnectSeconds", 3)),
            sync_existing_on_connect=bool(data.get("syncExistingOnConnect", True)),
            sync_interval_seconds=float(data.get("syncIntervalSeconds", 30)),
            state_path=(
                data.get("statePath")
                if isinstance(data.get("statePath"), str)
                else None
            ),
            proxy=data.get("proxy") if isinstance(data.get("proxy"), str) else None,
            remote_desktop_enabled=_bool_value(
                data.get("remoteDesktopEnabled")
                if "remoteDesktopEnabled" in data
                else legacy.get("enabled"),
                False,
            ),
            remote_desktop_host_path=_optional_string(
                data.get("remoteDesktopHostPath")
                if "remoteDesktopHostPath" in data
                else legacy.get("hostPath")
            ),
            remote_desktop_max_viewers=_clamp_int(
                data.get("remoteDesktopMaxViewers")
                if "remoteDesktopMaxViewers" in data
                else legacy.get("maxViewers", legacy.get("maxSessions")),
                minimum=1,
                maximum=16,
                default=REMOTE_DESKTOP_DEFAULT_MAX_VIEWERS,
            ),
            remote_desktop_preferred_encoder=_optional_string(
                data.get("remoteDesktopPreferredEncoder")
                if "remoteDesktopPreferredEncoder" in data
                else legacy.get("preferredEncoder")
            )
            or REMOTE_DESKTOP_DEFAULT_ENCODER,
        )

    def to_mapping(self) -> dict[str, Any]:
        return {
            "serverUrl": self.server_url,
            "connectorId": self.connector_id,
            "connectorToken": self.connector_token,
            "heartbeatSeconds": self.heartbeat_seconds,
            "reconnectSeconds": self.reconnect_seconds,
            "syncExistingOnConnect": self.sync_existing_on_connect,
            "syncIntervalSeconds": self.sync_interval_seconds,
            "statePath": self.state_path,
            **({"proxy": self.proxy} if self.proxy else {}),
            "remoteDesktop": {
                "enabled": self.remote_desktop_enabled,
                "hostPath": self.remote_desktop_host_path,
                "maxViewers": self.remote_desktop_max_viewers,
                "preferredEncoder": self.remote_desktop_preferred_encoder,
            },
        }

    def save(self, path: str | Path | None = None) -> Path:
        config_path = Path(path) if path is not None else self.default_path()
        config_path.parent.mkdir(parents=True, exist_ok=True)
        config_path.write_text(
            json.dumps(self.to_mapping(), ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        config_path.chmod(0o600)
        return config_path


def active_connector_config() -> ConnectorConfig | None:
    """Most recently constructed config in this process.

    Collaborators created by the dispatcher cannot receive `ConnectorConfig`
    directly in the current call graph; this lets them read the same values the
    running Connector was started with without loading the file a second time.
    """

    return _ACTIVE_CONFIG


def _remember_active_config(config: ConnectorConfig) -> None:
    global _ACTIVE_CONFIG
    _ACTIVE_CONFIG = config


def _bool_env(name: str, default: bool) -> bool:
    value = os.environ.get(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _bool_value(value: Any, default: bool) -> bool:
    return value if isinstance(value, bool) else default


def _optional_string(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    stripped = value.strip()
    return stripped or None


def _clamp_int(value: Any, *, minimum: int, maximum: int, default: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        return default
    return max(minimum, min(maximum, value))

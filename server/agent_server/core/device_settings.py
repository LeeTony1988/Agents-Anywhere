"""User-chosen device settings (docs/api/device-settings.md).

The server owns these switches and hands them to the connector; the stored
JSON may be missing, partial or written by an older server, so every read goes
through :func:`device_settings_from_stored`. Remote desktop adds integer
settings with ranges on top of the boolean switches, so a stored value is kept
only when its type matches and (for integers) it is inside the declared range.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field, ValidationError

DEVICE_SETTINGS_APPLY_METHOD = "connector.deviceSettings.apply"


class ConnectorDeviceSettings(BaseModel):
    confirmQuitOnShutdown: bool = False
    # Remote desktop (decisions §1 D5): off by default; enabling the first
    # three requires local confirmation on the device.
    remoteDesktopEnabled: bool = False
    remoteDesktopAllowControl: bool = False
    remoteDesktopRequireConsent: bool = True
    remoteDesktopAllowUnattended: bool = False
    remoteDesktopClipboard: bool = False
    remoteDesktopMaxFps: int = Field(default=30, ge=5, le=60)
    remoteDesktopMaxBitrateKbps: int = Field(default=4000, ge=500, le=20000)


class ConnectorDeviceSettingsPatch(BaseModel):
    model_config = ConfigDict(extra="forbid")

    confirmQuitOnShutdown: bool | None = None
    remoteDesktopEnabled: bool | None = None
    remoteDesktopAllowControl: bool | None = None
    remoteDesktopRequireConsent: bool | None = None
    remoteDesktopAllowUnattended: bool | None = None
    remoteDesktopClipboard: bool | None = None
    remoteDesktopMaxFps: int | None = Field(default=None, ge=5, le=60)
    remoteDesktopMaxBitrateKbps: int | None = Field(default=None, ge=500, le=20000)


class ConnectorDeviceSettingsResponse(BaseModel):
    connectorId: str
    settings: ConnectorDeviceSettings
    # Only set by a write: whether an online connector confirmed the change.
    delivered: bool | None = None
    serverTime: str


def _field_accepts(name: str, value: Any) -> bool:
    try:
        ConnectorDeviceSettings.model_validate({name: value})
    except ValidationError:
        return False
    return True


def device_settings_from_stored(value: Any) -> ConnectorDeviceSettings:
    """Read stored settings, keeping known valid fields and defaulting the rest.

    Booleans survive only as real booleans and integers only as real (non-bool)
    integers inside their declared range; anything else falls back to the
    default for that field. Which fields are boolean vs integer comes from the
    model's own defaults, so annotation introspection is not needed.
    """
    if not isinstance(value, dict):
        return ConnectorDeviceSettings()
    defaults = ConnectorDeviceSettings().model_dump()
    known: dict[str, Any] = {}
    for name, default in defaults.items():
        stored = value.get(name)
        if isinstance(default, bool):
            if isinstance(stored, bool):
                known[name] = stored
        elif isinstance(default, int) and (
            isinstance(stored, int)
            and not isinstance(stored, bool)
            and _field_accepts(name, stored)
        ):
            known[name] = stored
    return ConnectorDeviceSettings(**known)


def apply_device_settings_patch(
    current: ConnectorDeviceSettings,
    patch: ConnectorDeviceSettingsPatch,
) -> ConnectorDeviceSettings:
    changes = patch.model_dump(exclude_none=True)
    return current.model_copy(update=changes)

"""User-chosen device settings the server hands to this Connector.

See docs/api/device-settings.md. The server stores them; the Connector only
passes the latest value on to the process that launched it (Desktop) through
the local control state. Unknown or malformed fields are dropped so an older
Connector never forwards something a newer server invented.
"""

from __future__ import annotations

from typing import Any

DEVICE_SETTINGS_APPLY_METHOD = "connector.deviceSettings.apply"

_DEFAULTS: dict[str, bool | int] = {
    "confirmQuitOnShutdown": False,
    # Remote desktop v1 security defaults (decisions D5).
    "remoteDesktopEnabled": False,
    "remoteDesktopAllowControl": False,
    "remoteDesktopRequireConsent": True,
    "remoteDesktopAllowUnattended": False,
    "remoteDesktopClipboard": False,
    "remoteDesktopMaxFps": 30,
    "remoteDesktopMaxBitrateKbps": 4000,
}

_INTEGER_RANGES: dict[str, tuple[int, int]] = {
    "remoteDesktopMaxFps": (5, 60),
    "remoteDesktopMaxBitrateKbps": (500, 20_000),
}


def normalize_device_settings(value: Any) -> dict[str, Any]:
    source = value if isinstance(value, dict) else {}
    normalized: dict[str, Any] = {}
    for name, default in _DEFAULTS.items():
        candidate = source.get(name)
        if isinstance(default, bool):
            normalized[name] = candidate if isinstance(candidate, bool) else default
            continue
        minimum, maximum = _INTEGER_RANGES[name]
        if (
            isinstance(candidate, int)
            and not isinstance(candidate, bool)
            and minimum <= candidate <= maximum
        ):
            normalized[name] = candidate
        else:
            normalized[name] = default
    return normalized

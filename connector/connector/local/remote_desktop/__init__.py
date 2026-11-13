"""Local remote-desktop host supervision."""

from __future__ import annotations

from connector.local.remote_desktop.host import (
    MAX_FRAME_BYTES,
    PROTOCOL_VERSION,
    ScreenHostBusy,
    ScreenHostCrashLoop,
    ScreenHostError,
    ScreenHostIntegrityError,
    ScreenHostNotInstalled,
    ScreenHostNotRunning,
    ScreenHostProtocolError,
    ScreenHostRemoteError,
    ScreenHostSessionNotFound,
    ScreenHostSupervisor,
    ScreenHostTimeout,
    current_arch,
    current_platform,
    default_screen_host_path,
    verify_screen_host,
)
from connector.local.remote_desktop.policy import (
    ConsentPrompt,
    RemoteDesktopPolicy,
    WindowsMessageBoxConsentPrompt,
    request_consent,
)

__all__ = [
    "MAX_FRAME_BYTES",
    "PROTOCOL_VERSION",
    "ConsentPrompt",
    "RemoteDesktopPolicy",
    "ScreenHostBusy",
    "ScreenHostCrashLoop",
    "ScreenHostError",
    "ScreenHostIntegrityError",
    "ScreenHostNotInstalled",
    "ScreenHostNotRunning",
    "ScreenHostProtocolError",
    "ScreenHostRemoteError",
    "ScreenHostSessionNotFound",
    "ScreenHostSupervisor",
    "ScreenHostTimeout",
    "WindowsMessageBoxConsentPrompt",
    "current_arch",
    "current_platform",
    "default_screen_host_path",
    "request_consent",
    "verify_screen_host",
]



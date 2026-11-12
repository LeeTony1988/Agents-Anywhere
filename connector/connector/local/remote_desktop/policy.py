"""Remote-desktop policy derived from the seven device settings (D5)."""

from __future__ import annotations

import asyncio
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any, Protocol

from connector.core.device_settings import normalize_device_settings

QUALITY_MIN_FPS = 5
QUALITY_MAX_FPS = 60
QUALITY_DEFAULT_FPS = 30
QUALITY_MIN_BITRATE_KBPS = 500
QUALITY_MAX_BITRATE_KBPS = 20_000
QUALITY_DEFAULT_BITRATE_KBPS = 4000
QUALITY_MIN_WIDTH = 320
QUALITY_MAX_WIDTH = 3840
QUALITY_DEFAULT_WIDTH = 1600
QUALITY_PREFERRED_CODEC = "vp8"
CONSENT_TIMEOUT_SECONDS = 60.0


class ConsentPrompt(Protocol):
    """Local, interactive confirmation shown before a control session starts."""

    async def request_consent(
        self, *, session_id: str, timeout_seconds: float
    ) -> bool: ...


class WindowsMessageBoxConsentPrompt:
    """Native Windows Yes/No confirmation for a control session.

    This is an explicit opt-in for a Connector running in an interactive
    desktop session (``AGENT_CONNECTOR_REMOTE_DESKTOP_CONSENT_PROMPT=windows``).
    A service/session-0 process must not use it: Win32 would either fail or
    show the dialog on a desktop nobody can see, so the dispatcher only injects
    it on request.
    """

    def __init__(self, *, title: str = "Agents Anywhere") -> None:
        self._title = title

    async def request_consent(
        self, *, session_id: str, timeout_seconds: float
    ) -> bool:
        import sys

        if sys.platform != "win32":
            return False
        message = (
            "Agents Anywhere 请求远程控制这台设备。\n\n"
            f"会话：{session_id}\n"
            f"请在 {max(1, int(timeout_seconds))} 秒内确认。"
        )
        return await asyncio.to_thread(self._show, message)

    def _show(self, message: str) -> bool:
        import ctypes

        mb_yesno = 0x00000004
        mb_icon_question = 0x00000020
        mb_set_foreground = 0x00010000
        mb_topmost = 0x00040000
        result = ctypes.windll.user32.MessageBoxW(
            None, message, self._title, mb_yesno | mb_icon_question | mb_topmost | mb_set_foreground
        )
        return result == 6  # IDYES


async def request_consent(
    prompt: ConsentPrompt | None,
    *,
    session_id: str,
    timeout_seconds: float,
) -> bool:
    """Ask the local prompt, failing closed when nobody can answer.

    A Connector without an interactive desktop (or without an injected prompt)
    must never grant control silently. Exceptions and timeouts are denials.
    """

    if prompt is None or timeout_seconds <= 0:
        return False
    try:
        granted = await asyncio.wait_for(
            prompt.request_consent(
                session_id=session_id,
                timeout_seconds=timeout_seconds,
            ),
            timeout=timeout_seconds,
        )
    except TimeoutError:
        return False
    except Exception:  # noqa: BLE001 - a broken prompt must deny, never allow
        return False
    return granted is True


@dataclass(frozen=True, slots=True)
class RemoteDesktopPolicy:
    enabled: bool = False
    allow_control: bool = False
    require_consent: bool = True
    allow_unattended: bool = False
    clipboard: bool = False
    max_fps: int = QUALITY_DEFAULT_FPS
    max_bitrate_kbps: int = QUALITY_DEFAULT_BITRATE_KBPS

    @classmethod
    def from_device_settings(
        cls,
        settings: Mapping[str, Any] | None,
    ) -> RemoteDesktopPolicy:
        normalized = normalize_device_settings(
            settings if isinstance(settings, Mapping) else None
        )
        return cls(
            enabled=bool(normalized["remoteDesktopEnabled"]),
            allow_control=bool(normalized["remoteDesktopAllowControl"]),
            require_consent=bool(normalized["remoteDesktopRequireConsent"]),
            allow_unattended=bool(normalized["remoteDesktopAllowUnattended"]),
            clipboard=bool(normalized["remoteDesktopClipboard"]),
            max_fps=_int_in_range(
                normalized["remoteDesktopMaxFps"],
                QUALITY_MIN_FPS,
                QUALITY_MAX_FPS,
                QUALITY_DEFAULT_FPS,
            ),
            max_bitrate_kbps=_int_in_range(
                normalized["remoteDesktopMaxBitrateKbps"],
                QUALITY_MIN_BITRATE_KBPS,
                QUALITY_MAX_BITRATE_KBPS,
                QUALITY_DEFAULT_BITRATE_KBPS,
            ),
        )

    def consent_required(self, mode: str) -> bool:
        """Only control sessions need confirmation (decisions §1.1.1)."""

        if mode != "control":
            return False
        return self.require_consent and not self.allow_unattended

    def effective_quality(self, requested: Mapping[str, Any] | None) -> dict[str, Any]:
        wanted = requested if isinstance(requested, Mapping) else {}
        max_fps = min(
            _int_in_range(
                wanted.get("maxFps"),
                QUALITY_MIN_FPS,
                QUALITY_MAX_FPS,
                QUALITY_DEFAULT_FPS,
            ),
            self.max_fps,
        )
        max_bitrate = min(
            _int_in_range(
                wanted.get("maxBitrateKbps"),
                QUALITY_MIN_BITRATE_KBPS,
                QUALITY_MAX_BITRATE_KBPS,
                QUALITY_DEFAULT_BITRATE_KBPS,
            ),
            self.max_bitrate_kbps,
        )
        return {
            "maxFps": max_fps,
            "maxBitrateKbps": max_bitrate,
            "maxWidth": _int_in_range(
                wanted.get("maxWidth"),
                QUALITY_MIN_WIDTH,
                QUALITY_MAX_WIDTH,
                QUALITY_DEFAULT_WIDTH,
            ),
            # Contract 1.0 has exactly one codec; adding one is a minor bump.
            "preferCodec": QUALITY_PREFERRED_CODEC,
        }


def _int_in_range(value: Any, minimum: int, maximum: int, default: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        return default
    return max(minimum, min(maximum, value))

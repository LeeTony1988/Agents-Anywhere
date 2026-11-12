"""Backend RPC surface for remote desktop (Connector side)."""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable, Mapping
from pathlib import Path
from typing import Any, ClassVar

from connector.core.config import ConnectorConfig, active_connector_config
from connector.core.device_settings import normalize_device_settings
from connector.local.remote_desktop.host import (
    ScreenHostBusy,
    ScreenHostCrashLoop,
    ScreenHostError,
    ScreenHostNotInstalled,
    ScreenHostNotRunning,
    ScreenHostProtocolError,
    ScreenHostSessionNotFound,
    ScreenHostSupervisor,
    ScreenHostTimeout,
    current_arch,
    current_platform,
)
from connector.local.remote_desktop.policy import (
    ConsentPrompt,
    RemoteDesktopPolicy,
    request_consent,
)
from connector.logging import logger

Notify = Callable[[str, dict[str, Any]], Awaitable[None]]

HOST_INFO_METHOD = "remoteDesktop.hostInfo"
START_METHOD = "remoteDesktop.start"
STOP_METHOD = "remoteDesktop.stop"
UPDATE_QUALITY_METHOD = "remoteDesktop.updateQuality"
CONTROL_GRANT_METHOD = "remoteDesktop.control.grant"
REQUEST_KEYFRAME_METHOD = "remoteDesktop.requestKeyframe"
RELEASE_ALL_METHOD = "remoteDesktop.releaseAll"
SCREENSHOT_METHOD = "remoteDesktop.screenshot"
INPUT_METHOD = "remoteDesktop.input"

RPC_METHODS = frozenset(
    {
        HOST_INFO_METHOD,
        START_METHOD,
        STOP_METHOD,
        UPDATE_QUALITY_METHOD,
        CONTROL_GRANT_METHOD,
        REQUEST_KEYFRAME_METHOD,
        RELEASE_ALL_METHOD,
        SCREENSHOT_METHOD,
        INPUT_METHOD,
    }
)

_HOST_INFO_FIELDS = frozenset()
_START_FIELDS = frozenset(
    {
        "sessionId",
        "displayId",
        "mode",
        "quality",
        "media",
        "consent",
        "controllerIdentity",
        "participantId",
        "deadlineMs",
        "requireConsent",
    }
)
_CONSENT_FIELDS = frozenset({"required", "timeoutMs"})
_STOP_FIELDS = frozenset({"sessionId", "reason"})
_UPDATE_QUALITY_FIELDS = frozenset(
    {"sessionId", "maxFps", "maxBitrateKbps", "maxWidth", "preferCodec"}
)
_CONTROL_GRANT_FIELDS = frozenset(
    {
        "sessionId",
        "controllerIdentity",
        "epoch",
        "leaseExpiresAt",
        "controllerLeaseExpiresAt",
    }
)
_REQUEST_KEYFRAME_FIELDS = frozenset({"sessionId"})
_RELEASE_ALL_FIELDS = frozenset({"sessionId"})
_SCREENSHOT_FIELDS = frozenset(
    {"sessionId", "displayId", "maxWidth", "format", "quality"}
)
_INPUT_FIELDS = frozenset({"sessionId", "events", "identity"})

_MAX_INPUT_EVENTS = 512
_MAX_SCREENSHOT_WIDTH = 3840
STATS_COALESCE_SECONDS = 2.0

_RPC_ERROR_CODES = frozenset(
    {
        "rd_host_not_running",
        "rd_host_timeout",
        "rd_host_protocol_error",
        "rd_capture_failed",
        "rd_permission_denied",
        "rd_encoder_unavailable",
        "rd_media_connect_failed",
        "rd_display_not_found",
        "rd_session_not_found",
        "rd_busy",
        "rd_unsupported_platform",
        "rd_host_not_installed",
        "rd_invalid_request",
        "rd_unsupported_method",
    }
)

_HOST_ERROR_CODE_MAP = {
    "rd_invalid_params": "rd_invalid_request",
    "rd_not_controller": "rd_permission_denied",
    "rd_unsupported": "rd_unsupported_platform",
    "rd_unsupported_platform": "rd_unsupported_platform",
    "rd_capture_failed": "rd_capture_failed",
    "rd_encoder_unavailable": "rd_encoder_unavailable",
    "rd_media_connect_failed": "rd_media_connect_failed",
    "rd_display_not_found": "rd_display_not_found",
    "rd_session_not_found": "rd_session_not_found",
    "rd_busy": "rd_busy",
    "rd_permission_denied": "rd_permission_denied",
    "rd_host_not_running": "rd_host_not_running",
    "rd_host_timeout": "rd_host_timeout",
    "rd_host_protocol_error": "rd_host_protocol_error",
    "rd_host_not_installed": "rd_host_not_installed",
}


class RemoteDesktopRpcError(RuntimeError):
    """A stable Connector -> Server internal error (`rd_*`)."""

    def __init__(
        self,
        code: str,
        message: str,
        *,
        retryable: bool = False,
        details: Mapping[str, Any] | None = None,
    ) -> None:
        super().__init__(message or code)
        self.code = code
        self.retryable = retryable
        self.details = dict(details) if details else None


def rpc_error_from_host_error(
    exc: ScreenHostError,
    *,
    display_id: str | None = None,
) -> RemoteDesktopRpcError:
    message = str(exc) or exc.__class__.__name__
    retryable = bool(getattr(exc, "retryable", False))
    if isinstance(exc, ScreenHostNotInstalled):
        return RemoteDesktopRpcError("rd_host_not_installed", message)
    if isinstance(exc, ScreenHostTimeout):
        return RemoteDesktopRpcError("rd_host_timeout", message, retryable=True)
    if isinstance(exc, ScreenHostProtocolError):
        return RemoteDesktopRpcError("rd_host_protocol_error", message)
    if isinstance(exc, ScreenHostBusy):
        return RemoteDesktopRpcError("rd_busy", message, retryable=True)
    if isinstance(exc, ScreenHostCrashLoop):
        return RemoteDesktopRpcError("rd_host_not_running", message)
    if isinstance(exc, ScreenHostSessionNotFound):
        return RemoteDesktopRpcError("rd_session_not_found", message)
    if isinstance(exc, ScreenHostNotRunning):
        return RemoteDesktopRpcError("rd_host_not_running", message, retryable=True)
    code = str(getattr(exc, "code", "") or "")
    if display_id and "display" in message.lower():
        return RemoteDesktopRpcError("rd_display_not_found", message)
    if code in _HOST_ERROR_CODE_MAP:
        return RemoteDesktopRpcError(
            _HOST_ERROR_CODE_MAP[code], message, retryable=retryable
        )
    if code in _RPC_ERROR_CODES:
        return RemoteDesktopRpcError(code, message, retryable=retryable)
    return RemoteDesktopRpcError("rd_host_not_running", message, retryable=True)


class RemoteDesktopRpcHandler:
    """Route `remoteDesktop.*` RPC calls and convert host events to notifications."""

    METHODS: ClassVar[frozenset[str]] = RPC_METHODS

    def __init__(
        self,
        supervisor: ScreenHostSupervisor | None = None,
        *,
        policy: RemoteDesktopPolicy | None = None,
        notify: Notify | None = None,
        consent_prompt: ConsentPrompt | None = None,
        settings: Mapping[str, Any] | None = None,
        config: ConnectorConfig | None = None,
        host_path: str | Path | None = None,
        stats_coalesce_seconds: float | None = None,
    ) -> None:
        self.config = config if config is not None else active_connector_config()
        self._settings = normalize_device_settings(
            dict(settings) if settings is not None else None
        )
        if (
            settings is None
            and self.config is not None
            and self.config.remote_desktop_enabled
        ):
            self._settings["remoteDesktopEnabled"] = True
        self._policy = policy or RemoteDesktopPolicy.from_device_settings(self._settings)
        self._notify_sink = notify
        self._consent_prompt = consent_prompt
        self._stats_coalesce_seconds = max(
            0.0,
            STATS_COALESCE_SECONDS
            if stats_coalesce_seconds is None
            else stats_coalesce_seconds,
        )
        self._pending_stats: dict[str, dict[str, Any]] = {}
        self._stats_tasks: dict[str, asyncio.Task[None]] = {}
        if supervisor is None:
            resolved_host_path = host_path
            if resolved_host_path is None and self.config is not None:
                resolved_host_path = self.config.remote_desktop_host_path
            self.supervisor = ScreenHostSupervisor(
                host_path=resolved_host_path,
                event_handler=self.handle_host_event,
            )
        else:
            self.supervisor = supervisor
            set_event_handler = getattr(supervisor, "set_event_handler", None)
            if callable(set_event_handler):
                set_event_handler(self.handle_host_event)

    # ------------------------------------------------------------------ settings
    @property
    def policy(self) -> RemoteDesktopPolicy:
        return self._policy

    @property
    def settings(self) -> dict[str, Any]:
        return dict(self._settings)

    @property
    def max_viewers(self) -> int:
        if self.config is not None:
            return self.config.remote_desktop_max_viewers
        return 3

    def apply_device_settings(self, settings: Mapping[str, Any] | None) -> None:
        """Apply the seven settings the Server pushed to this Connector."""

        self._settings = normalize_device_settings(
            dict(settings) if settings is not None else None
        )
        self._policy = RemoteDesktopPolicy.from_device_settings(self._settings)

    # ------------------------------------------------------------------ dispatch
    def supports(self, method: str) -> bool:
        return method in self.METHODS

    async def dispatch(self, method: str, params: dict[str, Any]) -> Any:
        if method == HOST_INFO_METHOD:
            return await self.host_info(params)
        if method == START_METHOD:
            return await self.start(params)
        if method == STOP_METHOD:
            return await self.stop(params)
        if method == UPDATE_QUALITY_METHOD:
            return await self.update_quality(params)
        if method == CONTROL_GRANT_METHOD:
            return await self.control_grant(params)
        if method == REQUEST_KEYFRAME_METHOD:
            return await self.request_keyframe(params)
        if method == RELEASE_ALL_METHOD:
            return await self.release_all(params)
        if method == SCREENSHOT_METHOD:
            return await self.screenshot(params)
        if method == INPUT_METHOD:
            return await self.input(params)
        raise RemoteDesktopRpcError(
            "rd_unsupported_method", f"method not found: {method}"
        )

    # ------------------------------------------------------------------ methods
    async def host_info(self, params: dict[str, Any]) -> dict[str, Any]:
        _require_only_fields(params, _HOST_INFO_FIELDS)
        if not self._host_installed():
            return _unavailable_host_facts(
                reason="host_not_installed",
                supported=False,
                permission_state="unsupported",
                platform=self._platform(),
                arch=self._arch(),
            )
        platform = self._platform()
        if not self._host_running():
            return _unavailable_host_facts(
                reason="host_not_running",
                supported=True,
                permission_state="not_determined",
                platform=platform,
                arch=self._arch(),
            )
        try:
            facts = await self.supervisor.info()
        except ScreenHostNotRunning:
            return _unavailable_host_facts(
                reason="host_not_running",
                supported=True,
                permission_state="not_determined",
                platform=self._platform(),
                arch=self._arch(),
            )
        except ScreenHostError as exc:
            raise rpc_error_from_host_error(exc) from exc
        return _normalize_host_facts(
            facts,
            platform=self._platform(),
            arch=self._arch(),
            active_sessions=1
            if getattr(self.supervisor, "active_session_id", None)
            else 0,
        )

    async def start(self, params: dict[str, Any]) -> dict[str, Any]:
        _require_only_fields(params, _START_FIELDS)
        session_id = _required_string(params, "sessionId")
        mode = _optional_string(params.get("mode")) or "view"
        if mode not in {"view", "control"}:
            raise RemoteDesktopRpcError(
                "rd_invalid_request", "mode must be view or control"
            )
        policy = self._policy
        if not policy.enabled:
            raise RemoteDesktopRpcError(
                "rd_permission_denied", "remote desktop is disabled on this device"
            )
        if mode == "control" and not policy.allow_control:
            raise RemoteDesktopRpcError(
                "rd_permission_denied", "remote control is disabled on this device"
            )
        display_id = _optional_string(params.get("displayId"))
        controller_identity = _optional_string(params.get("controllerIdentity"))
        if "participantId" in params:
            _optional_string(params.get("participantId"))
        deadline_ms = _optional_int(params, "deadlineMs", 1, 300_000)
        quality_requested = _optional_mapping(params, "quality")
        media = _optional_mapping(params, "media")
        consent = _optional_mapping(params, "consent") or {}
        _require_only_fields(consent, _CONSENT_FIELDS, prefix="consent.")
        requested_consent = _optional_bool(consent.get("required"), False)
        if "requireConsent" in params:
            requested_consent = _optional_bool(params.get("requireConsent"), False)
        consent_timeout_ms = _optional_int(
            consent, "timeoutMs", 0, 300_000, default=60_000
        )
        needs_consent = policy.consent_required(mode) or requested_consent
        if needs_consent:
            granted = await request_consent(
                self._consent_prompt,
                session_id=session_id,
                timeout_seconds=consent_timeout_ms / 1000,
            )
            if not granted:
                await self._notify(
                    "remoteDesktop.consent",
                    {
                        "sessionId": session_id,
                        "decision": "denied",
                        "controllerIdentity": controller_identity,
                    },
                )
                raise RemoteDesktopRpcError(
                    "rd_permission_denied", "remote desktop consent was denied"
                )
        quality = policy.effective_quality(quality_requested)
        try:
            result = await self.supervisor.start(
                session_id,
                display_id=display_id,
                mode=mode,
                quality=quality,
                media=media,
                consent_required=False,
                deadline_ms=deadline_ms,
            )
            if mode == "control" and controller_identity:
                await self.supervisor.control_grant(
                    session_id, controller_identity
                )
        except ScreenHostError as exc:
            await self._stop_quietly(session_id)
            raise rpc_error_from_host_error(exc, display_id=display_id) from exc
        if needs_consent:
            await self._notify(
                "remoteDesktop.consent",
                {
                    "sessionId": session_id,
                    "decision": "granted",
                    "controllerIdentity": controller_identity,
                },
            )
        return {
            "accepted": bool(result.get("accepted", True)),
            "state": "starting",
        }

    async def stop(self, params: dict[str, Any]) -> dict[str, Any]:
        _require_only_fields(params, _STOP_FIELDS)
        session_id = _optional_string(params.get("sessionId"))
        reason = _optional_string(params.get("reason"))
        try:
            if reason is None:
                await self.supervisor.stop(session_id)
            else:
                await self.supervisor.stop(session_id, reason=reason)
        except ScreenHostError as exc:
            raise rpc_error_from_host_error(exc) from exc
        return {"stopped": True}

    async def update_quality(self, params: dict[str, Any]) -> dict[str, Any]:
        _require_only_fields(params, _UPDATE_QUALITY_FIELDS)
        session_id = _required_string(params, "sessionId")
        self._require_active_session(session_id)
        requested = {
            key: params[key]
            for key in ("maxFps", "maxBitrateKbps", "maxWidth")
            if key in params
        }
        quality = self._policy.effective_quality(requested)
        host_quality = {
            key: quality[key] for key in ("maxFps", "maxBitrateKbps", "maxWidth")
        }
        try:
            await self.supervisor.update_quality(session_id, host_quality)
        except ScreenHostError as exc:
            raise rpc_error_from_host_error(exc) from exc
        return {"applied": True}

    async def control_grant(self, params: dict[str, Any]) -> dict[str, Any]:
        _require_only_fields(params, _CONTROL_GRANT_FIELDS)
        session_id = _required_string(params, "sessionId")
        self._require_active_session(session_id)
        controller_identity = params.get("controllerIdentity")
        if controller_identity is not None and not isinstance(
            controller_identity, str
        ):
            raise RemoteDesktopRpcError(
                "rd_invalid_request", "controllerIdentity must be a string or null"
            )
        lease_expires_at = _optional_string(
            params.get("leaseExpiresAt")
            or params.get("controllerLeaseExpiresAt")
        )
        epoch = _optional_int(params, "epoch", 0, 2_147_483_647)
        try:
            await self.supervisor.control_grant(
                session_id,
                controller_identity or None,
                lease_expires_at=lease_expires_at,
                epoch=epoch,
            )
        except ScreenHostError as exc:
            raise rpc_error_from_host_error(exc) from exc
        return {"applied": True}

    async def request_keyframe(self, params: dict[str, Any]) -> dict[str, Any]:
        _require_only_fields(params, _REQUEST_KEYFRAME_FIELDS)
        session_id = _required_string(params, "sessionId")
        self._require_active_session(session_id)
        try:
            result = await self.supervisor.request_keyframe(session_id)
        except ScreenHostError as exc:
            raise rpc_error_from_host_error(exc) from exc
        return {"requested": bool(result.get("requested", False))}

    async def release_all(self, params: dict[str, Any]) -> dict[str, Any]:
        _require_only_fields(params, _RELEASE_ALL_FIELDS)
        session_id = _optional_string(params.get("sessionId"))
        try:
            result = await self.supervisor.release_all(session_id)
        except ScreenHostError as exc:
            raise rpc_error_from_host_error(exc) from exc
        return {
            "releasedKeys": _released_count(result.get("releasedKeys")),
            "releasedButtons": _released_count(result.get("releasedButtons")),
        }

    async def screenshot(self, params: dict[str, Any]) -> dict[str, Any]:
        _require_only_fields(params, _SCREENSHOT_FIELDS)
        session_id = _optional_string(params.get("sessionId"))
        if session_id is not None:
            self._require_active_session(session_id)
        display_id = _optional_string(params.get("displayId"))
        max_width = _optional_int(params, "maxWidth", 1, 100_000)
        if max_width is not None:
            max_width = max(1, min(_MAX_SCREENSHOT_WIDTH, max_width))
        fmt = _optional_string(params.get("format"))
        if fmt is not None and fmt not in {"jpeg", "png"}:
            raise RemoteDesktopRpcError(
                "rd_invalid_request", "format must be jpeg or png"
            )
        quality = _optional_int(params, "quality", 1, 100)
        try:
            result = await self.supervisor.screenshot(
                session_id,
                display_id=display_id,
                max_width=max_width,
                fmt=fmt,
                quality=quality,
            )
        except ScreenHostError as exc:
            raise rpc_error_from_host_error(exc, display_id=display_id) from exc
        if not isinstance(result.get("dataBase64"), str):
            raise RemoteDesktopRpcError(
                "rd_capture_failed", "screen host returned no screenshot data"
            )
        return result

    async def input(self, params: dict[str, Any]) -> dict[str, Any]:
        _require_only_fields(params, _INPUT_FIELDS)
        session_id = _required_string(params, "sessionId")
        self._require_active_session(session_id)
        raw_events = params.get("events")
        if not isinstance(raw_events, list) or len(raw_events) > _MAX_INPUT_EVENTS:
            raise RemoteDesktopRpcError(
                "rd_invalid_request",
                f"events must be an array of at most {_MAX_INPUT_EVENTS} items",
            )
        events: list[dict[str, Any]] = []
        for event in raw_events:
            if (
                not isinstance(event, dict)
                or event.get("v") != 1
                or not isinstance(event.get("t"), str)
            ):
                raise RemoteDesktopRpcError(
                    "rd_invalid_request", "each input event needs v=1 and a type"
                )
            events.append(dict(event))
        identity = _optional_string(params.get("identity"))
        controller_identity = getattr(self.supervisor, "controller_identity", None)
        resolved_identity = identity or controller_identity
        if not resolved_identity:
            raise RemoteDesktopRpcError(
                "rd_permission_denied", "remote desktop control is not granted"
            )
        try:
            result = await self.supervisor.input(
                session_id, events, identity=resolved_identity
            )
        except ScreenHostError as exc:
            raise rpc_error_from_host_error(exc) from exc
        return {"appliedSeq": _safe_int(result.get("appliedSeq"), 0)}

    # ------------------------------------------------------------------ events
    async def handle_host_event(
        self, event: str, params: Mapping[str, Any]
    ) -> None:
        data = dict(params or {})
        session_id = data.get("sessionId")
        if not isinstance(session_id, str) or not session_id:
            active = getattr(self.supervisor, "active_session_id", None)
            if isinstance(active, str) and active:
                data["sessionId"] = active
        if event == "stats":
            self._queue_stats(data)
            return
        if event == "state":
            await self._notify("remoteDesktop.state", data)
            return
        if event == "displays":
            displays = data.get("displays")
            if not isinstance(displays, list):
                displays = data.get("items")
            await self._notify(
                "remoteDesktop.displays",
                {
                    "displays": displays if isinstance(displays, list) else [],
                    **(
                        {"sessionId": data["sessionId"]}
                        if isinstance(data.get("sessionId"), str)
                        else {}
                    ),
                },
            )
            return
        if event == "consent":
            await self._notify("remoteDesktop.consent", data)
            return
        if event == "clipboard":
            await self._notify("remoteDesktop.clipboard", data)
            return
        if event == "error":
            payload = dict(data)
            if not isinstance(payload.get("code"), str) or not payload["code"]:
                payload["code"] = "rd_host_error"
            if not isinstance(payload.get("message"), str):
                payload["message"] = "Remote desktop host error."
            payload["retryable"] = bool(payload.get("retryable", False))
            await self._notify("remoteDesktop.error", payload)
            return
        logger.debug("ignoring unknown screen host event {}", event)

    def _queue_stats(self, params: dict[str, Any]) -> None:
        session_id = params.get("sessionId")
        if not isinstance(session_id, str) or not session_id:
            logger.debug("dropping remote desktop stats without sessionId")
            return
        self._pending_stats[session_id] = params
        task = self._stats_tasks.get(session_id)
        if task is None or task.done():
            self._stats_tasks[session_id] = asyncio.create_task(
                self._flush_stats(session_id)
            )

    async def _flush_stats(self, session_id: str) -> None:
        await asyncio.sleep(self._stats_coalesce_seconds)
        params = self._pending_stats.pop(session_id, None)
        self._stats_tasks.pop(session_id, None)
        if params is not None:
            await self._notify("remoteDesktop.stats", params)

    async def _notify(self, method: str, params: dict[str, Any]) -> None:
        sink = self._notify_sink
        if sink is None:
            logger.debug("remote desktop notification dropped (no sink): {}", method)
            return
        try:
            await sink(method, params)
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001 - notifications must not stop host events
            logger.exception("remote desktop notification failed method={}", method)

    async def aclose(self) -> None:
        for task in list(self._stats_tasks.values()):
            task.cancel()
        await asyncio.gather(*self._stats_tasks.values(), return_exceptions=True)
        self._stats_tasks.clear()
        pending = list(self._pending_stats.values())
        self._pending_stats.clear()
        for params in pending:
            await self._notify("remoteDesktop.stats", params)
        shutdown = getattr(self.supervisor, "shutdown", None)
        if callable(shutdown):
            await shutdown()

    async def shutdown(self) -> None:
        await self.aclose()

    # ------------------------------------------------------------------ helpers
    def _host_installed(self) -> bool:
        installed = getattr(self.supervisor, "installed", None)
        if callable(installed):
            installed = installed()
        if isinstance(installed, bool):
            return installed
        host_path = getattr(self.supervisor, "host_path", True)
        if callable(host_path):
            host_path = host_path()
        return host_path is not None

    def _host_running(self) -> bool:
        running = getattr(self.supervisor, "running", False)
        return bool(running() if callable(running) else running)

    def _platform(self) -> str:
        value = getattr(self.supervisor, "platform", None)
        return _platform_value(value if isinstance(value, str) else current_platform())

    def _arch(self) -> str:
        value = getattr(self.supervisor, "arch", None)
        return _arch_value(value if isinstance(value, str) else current_arch())

    def _require_active_session(self, session_id: str) -> None:
        active = getattr(self.supervisor, "active_session_id", None)
        if active is None or active != session_id:
            raise RemoteDesktopRpcError(
                "rd_session_not_found",
                f"remote desktop session is not active: {session_id}",
            )

    async def _stop_quietly(self, session_id: str) -> None:
        stop = getattr(self.supervisor, "stop", None)
        if not callable(stop):
            return
        try:
            await stop(session_id, reason="start_failed")
        except Exception:  # noqa: BLE001 - cleanup is best effort
            logger.debug("remote desktop start failure cleanup failed")


def _unavailable_host_facts(
    *,
    reason: str,
    supported: bool,
    permission_state: str,
    platform: str,
    arch: str,
) -> dict[str, Any]:
    return {
        "supported": supported,
        "available": False,
        "unavailableReason": reason,
        "platform": _platform_value(platform),
        "arch": _arch_value(arch),
        "hostVersion": None,
        "pid": None,
        "displays": [],
        "codecs": ["vp8"],
        "maxFps": 30,
        "input": {
            "pointer": False,
            "keyboard": False,
            "touch": False,
            "clipboard": False,
        },
        "permissions": {
            "screenRecording": permission_state,
            "accessibility": permission_state,
            "inputMonitoring": permission_state,
        },
        "activeSessions": 0,
    }


def _normalize_host_facts(
    raw: Mapping[str, Any],
    *,
    platform: str,
    arch: str,
    active_sessions: int,
) -> dict[str, Any]:
    facts = dict(raw)
    supported = facts.get("supported")
    if not isinstance(supported, bool):
        supported = True
    available = bool(facts.get("available", True)) and supported
    reason = facts.get("unavailableReason")
    if not available and not isinstance(reason, str):
        reason = "unsupported_platform"
    displays = facts.get("displays")
    if not isinstance(displays, list):
        displays = []
    max_fps = facts.get("maxFps")
    if isinstance(max_fps, bool) or not isinstance(max_fps, int):
        max_fps = 30
    max_fps = max(1, min(120, max_fps))
    host_version = facts.get("hostVersion")
    if host_version is not None and not isinstance(host_version, str):
        host_version = None
    pid = facts.get("pid")
    if isinstance(pid, bool) or not isinstance(pid, int):
        pid = None
    return {
        "supported": supported,
        "available": available,
        "unavailableReason": None if available else reason,
        "platform": _platform_value(
            facts.get("platform") if isinstance(facts.get("platform"), str) else platform
        ),
        "arch": _arch_value(
            facts.get("arch") if isinstance(facts.get("arch"), str) else arch
        ),
        "hostVersion": host_version,
        "pid": pid,
        "displays": [
            dict(display)
            for display in displays[:16]
            if isinstance(display, Mapping)
        ],
        "codecs": ["vp8"],
        "maxFps": max_fps,
        "input": _bool_flags(
            facts.get("input"), ("pointer", "keyboard", "touch", "clipboard")
        ),
        "permissions": _permissions(facts.get("permissions")),
        "activeSessions": 1 if active_sessions else 0,
    }


def _platform_value(value: str) -> str:
    return value if value in {"windows", "macos", "linux", "unknown"} else "unknown"


def _arch_value(value: str) -> str:
    return value if value in {"x86_64", "aarch64", "unknown"} else "unknown"


def _bool_flags(value: Any, keys: tuple[str, ...]) -> dict[str, bool]:
    source = value if isinstance(value, Mapping) else {}
    return {
        key: source[key] if isinstance(source.get(key), bool) else False
        for key in keys
    }


def _permissions(value: Any) -> dict[str, str]:
    source = value if isinstance(value, Mapping) else {}
    allowed = {"granted", "denied", "not_determined", "unsupported"}
    return {
        key: source[key]
        if isinstance(source.get(key), str) and source.get(key) in allowed
        else "not_determined"
        for key in ("screenRecording", "accessibility", "inputMonitoring")
    }


def _require_only_fields(
    params: Mapping[str, Any],
    allowed: frozenset[str],
    *,
    prefix: str = "",
) -> None:
    if not isinstance(params, Mapping):
        raise RemoteDesktopRpcError("rd_invalid_request", "params must be an object")
    unsupported = sorted(set(params) - allowed)
    if unsupported:
        raise RemoteDesktopRpcError(
            "rd_invalid_request",
            f"unsupported request field(s): {prefix}{', '.join(unsupported)}",
        )


def _required_string(params: Mapping[str, Any], key: str) -> str:
    value = params.get(key)
    if not isinstance(value, str) or not value:
        raise RemoteDesktopRpcError("rd_invalid_request", f"{key} is required")
    return value


def _optional_string(value: Any) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise RemoteDesktopRpcError("rd_invalid_request", "expected a string value")
    stripped = value.strip()
    return stripped or None


def _optional_int(
    params: Mapping[str, Any],
    key: str,
    minimum: int,
    maximum: int,
    *,
    default: int | None = None,
) -> int | None:
    if key not in params:
        return default
    value = params.get(key)
    if isinstance(value, bool) or not isinstance(value, int):
        raise RemoteDesktopRpcError("rd_invalid_request", f"{key} must be an integer")
    if not minimum <= value <= maximum:
        raise RemoteDesktopRpcError(
            "rd_invalid_request", f"{key} must be between {minimum} and {maximum}"
        )
    return value


def _optional_bool(value: Any, default: bool) -> bool:
    if value is None:
        return default
    if not isinstance(value, bool):
        raise RemoteDesktopRpcError("rd_invalid_request", "expected a boolean value")
    return value


def _optional_mapping(
    params: Mapping[str, Any], key: str
) -> dict[str, Any] | None:
    if key not in params or params.get(key) is None:
        return None
    value = params[key]
    if not isinstance(value, Mapping):
        raise RemoteDesktopRpcError("rd_invalid_request", f"{key} must be an object")
    return dict(value)


def _safe_int(value: Any, default: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        return default
    return value


def _released_count(value: Any) -> int:
    if isinstance(value, (list, tuple, set)):
        return len(value)
    return _safe_int(value, 0)

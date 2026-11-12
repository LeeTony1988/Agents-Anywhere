"""Connector notification handlers for remote desktop host events.

The service owns persistence and fanout; this module only maps host state names
onto the session state machine and decides which realtime event to emit.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Any, ClassVar

from loguru import logger

from agent_server.core.remote_desktop import (
    DisplayInfo,
    RemoteDesktopErrorInfo,
    RemoteDesktopSession,
    RemoteDesktopStats,
    utc_now_iso,
    viewer_identity,
)
from agent_server.services.ingest_effects import IngestEffect

if TYPE_CHECKING:
    from agent_server.services.remote_desktop import RemoteDesktopService


STATE_MAP: dict[str, str] = {
    "starting": "starting",
    "running": "live",
    "publishing": "live",
    "live": "live",
    "degraded": "degraded",
    "interrupted": "interrupted",
}
STOPPED_STATES = {"stopped", "ended", "stopping"}
TERMINAL_HOST_STATES = {"closed", "finished"}


def _number(value: Any, default: float = 0.0) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return default
    return float(value)


def _integer(value: Any, default: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        return default
    return value


def _stats_from_params(params: dict[str, Any]) -> RemoteDesktopStats:
    return RemoteDesktopStats(
        fps=max(0.0, _number(params.get("fps"))),
        bitrateKbps=max(0.0, _number(params.get("bitrateKbps"))),
        rttMs=max(0.0, _number(params.get("rttMs"))),
        jitterMs=max(0.0, _number(params.get("jitterMs"))),
        packetLossPct=max(0.0, min(100.0, _number(params.get("packetLossPct")))),
        codec="vp8",
        width=max(1, _integer(params.get("width"), 1)),
        height=max(1, _integer(params.get("height"), 1)),
        encoder=str(params.get("encoder") or "unknown"),
        subscribers=max(0, _integer(params.get("subscribers"), 0)),
    )


def _stats_payload(stats: RemoteDesktopStats) -> dict[str, Any]:
    return {
        "fps": stats.fps if stats.fps is not None else 0.0,
        "bitrateKbps": (
            stats.bitrateKbps if stats.bitrateKbps is not None else 0.0
        ),
        "rttMs": stats.rttMs if stats.rttMs is not None else 0.0,
        "jitterMs": stats.jitterMs if stats.jitterMs is not None else 0.0,
        "packetLossPct": (
            stats.packetLossPct if stats.packetLossPct is not None else 0.0
        ),
        "codec": stats.codec or "vp8",
        "width": stats.width if stats.width is not None else 1,
        "height": stats.height if stats.height is not None else 1,
        "encoder": stats.encoder or "unknown",
        "subscribers": (
            stats.subscribers if stats.subscribers is not None else 0
        ),
    }


class RemoteDesktopNotificationHandler:
    METHODS: ClassVar[set[str]] = {
        "remoteDesktop.state",
        "remoteDesktop.stats",
        "remoteDesktop.displays",
        "remoteDesktop.consent",
        "remoteDesktop.error",
    }

    def __init__(self, service: RemoteDesktopService) -> None:
        self._service = service

    async def apply(
        self, connector_id: str, method: str, params: dict[str, Any]
    ) -> IngestEffect | None:
        if method not in self.METHODS:
            return None
        if method == "remoteDesktop.state":
            await self._state(connector_id, params)
        elif method == "remoteDesktop.stats":
            await self._stats(connector_id, params)
        elif method == "remoteDesktop.displays":
            await self._displays(connector_id, params)
        elif method == "remoteDesktop.consent":
            await self._consent(connector_id, params)
        else:
            await self._error(connector_id, params)
        return IngestEffect()

    async def _session(
        self, connector_id: str, params: dict[str, Any]
    ) -> RemoteDesktopSession | None:
        session_id = params.get("sessionId")
        if not isinstance(session_id, str) or not session_id:
            logger.warning(
                "remote desktop notification without sessionId method={}",
                params.get("method"),
            )
            return None
        return await self._service.session_for_connector(session_id, connector_id)

    async def _state(self, connector_id: str, params: dict[str, Any]) -> None:
        session = await self._session(connector_id, params)
        if session is None or session.state == "ended":
            return
        host_state = str(params.get("state") or "").lower()
        if host_state in {"error", "failed"}:
            error_payload = params.get("error")
            error = (
                error_payload if isinstance(error_payload, dict) else {}
            )
            info = RemoteDesktopErrorInfo(
                code=str(error.get("code") or params.get("code") or "media_error"),
                message=str(
                    error.get("message")
                    or params.get("message")
                    or "Remote desktop host reported an error."
                ),
                retryable=bool(
                    error.get("retryable", params.get("retryable", False))
                ),
            )
            await self._service.update_session_fields(session, error=info.model_dump())
            await self._service.publish_error(
                session.id,
                code=info.code,
                message=info.message,
                retryable=info.retryable,
            )
            await self._service.end_from_system(session.id, "media_error")
            return
        if host_state in STOPPED_STATES or host_state in TERMINAL_HOST_STATES:
            await self._service.end_from_system(session.id, "host_ended")
            return
        target = STATE_MAP.get(host_state)
        if target is None:
            logger.info(
                "ignoring unknown remote desktop host state session_id={} state={}",
                session.id,
                host_state,
            )
            return
        if target == session.state:
            return
        fields: dict[str, Any] = {}
        if target == "live":
            fields["startedAt"] = session.startedAt or utc_now_iso()
            fields["consentDeadlineAt"] = None
        elif target == "interrupted":
            fields["interruptedAt"] = utc_now_iso()
        try:
            updated = await self._service.transition_session(
                session, target, **fields
            )
        except Exception as exc:  # noqa: BLE001 - stale host events are expected
            logger.warning(
                "remote desktop state transition rejected session_id={} target={} error={}",
                session.id,
                target,
                str(exc) or exc.__class__.__name__,
            )
            return
        await self._service.publish_session_updated(updated)

    async def _stats(self, connector_id: str, params: dict[str, Any]) -> None:
        session = await self._session(connector_id, params)
        if session is None or session.state == "ended":
            return
        stats = _stats_from_params(params)
        await self._service.add_stats_snapshot(session, stats)
        await self._service.publish_stats_updated(
            session.id, _stats_payload(stats), at=utc_now_iso()
        )

    async def _displays(self, connector_id: str, params: dict[str, Any]) -> None:
        raw = params.get("displays")
        if not isinstance(raw, list):
            logger.warning(
                "ignoring remote desktop displays notification without a list "
                "connector_id={}",
                connector_id,
            )
            return
        displays: list[dict[str, Any]] = []
        for item in raw:
            try:
                display = DisplayInfo.model_validate(item).model_dump(mode="json")
            except Exception as exc:  # noqa: BLE001 - one bad display must not drop the rest
                logger.warning(
                    "ignoring invalid remote desktop display connector_id={} error={}",
                    connector_id,
                    str(exc) or exc.__class__.__name__,
                )
            else:
                displays.append(display)
        await self._service.update_host_displays(connector_id, displays)

    async def _consent(self, connector_id: str, params: dict[str, Any]) -> None:
        session = await self._session(connector_id, params)
        if session is None or session.state == "ended":
            return
        decision = params.get("decision")
        if decision == "denied":
            await self._service.end_from_system(session.id, "consent_denied")
            return
        if decision != "granted":
            logger.warning(
                "ignoring remote desktop consent decision session_id={} decision={}",
                session.id,
                decision,
            )
            return
        controller = params.get("controllerIdentity") or params.get("identity")
        if isinstance(controller, str) and controller.startswith("pt_"):
            controller = viewer_identity(controller)
        if not isinstance(controller, str) or not controller:
            controller = session.controller or next(
                (
                    viewer_identity(participant.participantId)
                    for participant in session.participants
                ),
                None,
            )
        fields: dict[str, Any] = {
            "controller": controller,
            "consentDeadlineAt": None,
            "startedAt": session.startedAt or utc_now_iso(),
        }
        if session.state in {"awaiting_consent", "starting"}:
            updated = await self._service.transition_session(
                session, "live", **fields
            )
            await self._service.publish_session_updated(updated)
        else:
            updated = await self._service.update_session_fields(session, **fields)
            await self._service.publish_session_updated(updated)

    async def _error(self, connector_id: str, params: dict[str, Any]) -> None:
        session = await self._session(connector_id, params)
        if session is None or session.state == "ended":
            return
        retryable = bool(params.get("retryable", False))
        info = RemoteDesktopErrorInfo(
            code=str(params.get("code") or "remote_desktop_error"),
            message=str(params.get("message") or "Remote desktop error."),
            retryable=retryable,
        )
        await self._service.update_session_fields(session, error=info.model_dump())
        await self._service.publish_error(
            session.id,
            code=info.code,
            message=info.message,
            retryable=retryable,
            details={
                key: value
                for key, value in params.items()
                if key
                not in {"sessionId", "code", "message", "retryable"}
            }
            or None,
        )
        if not retryable:
            await self._service.end_from_system(session.id, "media_error")

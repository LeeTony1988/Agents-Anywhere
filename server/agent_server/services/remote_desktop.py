"""Remote desktop control-plane service.

The service owns session lifecycle decisions and delegates ephemeral state to
the broker and media grants to a :class:`MediaProvider`. It never imports the
Store facade or FastAPI so the architecture boundary stays intact.
"""

from __future__ import annotations

import asyncio
import secrets
from collections.abc import Awaitable, Callable, Mapping
from datetime import UTC, datetime, timedelta
from typing import Any, Protocol

from loguru import logger

from agent_server.core.device_settings import (
    ConnectorDeviceSettings,
    device_settings_from_stored,
)
from agent_server.core.remote_desktop import (
    ACTIVE_STATES,
    CONSENT_TIMEOUT_SECONDS,
    CONTROLLER_LEASE_SECONDS,
    RemoteDesktopConflictError,
    RemoteDesktopError,
    RemoteDesktopLimitError,
    RemoteDesktopNotFoundError,
    RemoteDesktopParticipant,
    RemoteDesktopRateLimitedError,
    RemoteDesktopSession,
    RemoteDesktopStateError,
    RemoteDesktopStats,
    RemoteDesktopUnavailableError,
    clamp_quality,
    new_participant_id,
    participants_with_new,
    touch_participant,
    utc_now_iso,
    viewer_identity,
)
from agent_server.services.remote_desktop_media import MediaGrant, MediaProvider
from agent_server.services.remote_desktop_policy import (
    build_capabilities,
    current_settings,
    host_facts,
)

DEFAULT_MAX_VIEWERS_PER_SESSION = 3
REMOTE_DESKTOP_WS_URL = "/api/v2/remote-desktop/sessions/{session_id}/ws"
TICKET_SCOPE_PREFIX = "remote-desktop"


class NotControllerError(RemoteDesktopError):
    default_code = "remote_desktop_not_controller"
    default_status_code = 409


class ConsentPendingError(RemoteDesktopError):
    default_code = "remote_desktop_consent_pending"
    default_status_code = 409


try:  # Newer core revisions export these; local fallbacks keep this module importable.
    from agent_server.core.remote_desktop import (  # type: ignore[attr-defined]
        RemoteDesktopConsentPendingError,
        RemoteDesktopNotControllerError,
    )
except ImportError:  # pragma: no cover - depends on the parallel core change
    RemoteDesktopConsentPendingError = ConsentPendingError  # type: ignore[assignment,misc]
    RemoteDesktopNotControllerError = NotControllerError  # type: ignore[assignment,misc]


class RemoteDesktopStore(Protocol):
    async def get_connector(self, connector_id: str) -> Any: ...

    async def get_connector_device_settings(self, connector_id: str) -> dict[str, Any]: ...

    async def create_remote_desktop_session(self, **kwargs: Any) -> RemoteDesktopSession: ...

    async def get_remote_desktop_session(
        self, session_id: str, *, user_id: str | None = None, for_update: bool = False
    ) -> RemoteDesktopSession: ...

    async def find_active_remote_desktop_session(
        self, connector_id: str
    ) -> RemoteDesktopSession | None: ...

    async def list_remote_desktop_sessions(
        self,
        *,
        user_id: str,
        connector_id: str | None = None,
        state: str | None = None,
        limit: int = 50,
        cursor: str | None = None,
    ) -> tuple[list[RemoteDesktopSession], str | None]: ...

    async def transition_remote_desktop_session(
        self,
        session_id: str,
        *,
        expected_states: set[str],
        target_state: str,
        **fields: Any,
    ) -> bool: ...

    async def end_remote_desktop_session(
        self, session_id: str, *, reason: str, ended_at: str, **fields: Any
    ) -> bool: ...

    async def update_remote_desktop_participants(
        self, session_id: str, participants: list[dict[str, Any]]
    ) -> None: ...

    async def save_remote_desktop_stats_summary(
        self, session_id: str, summary: dict[str, Any]
    ) -> None: ...

    async def list_active_remote_desktop_sessions(
        self, limit: int = 500
    ) -> list[RemoteDesktopSession]: ...


class RemoteDesktopRequester(Protocol):
    async def request(
        self,
        connector_id: str,
        method: str,
        params: dict[str, Any],
        *,
        timeout: float = 30,
    ) -> Any: ...


class RemoteDesktopBrokerPort(Protocol):
    async def publish_event(
        self,
        session_id: str,
        event: dict[str, Any],
        *,
        target_participant: str | None = None,
    ) -> str: ...

    async def get_json(self, key: str) -> Any | None: ...

    async def set_json(self, key: str, value: Any, *, ttl_seconds: int) -> None: ...

    async def increment(self, key: str, *, ttl_seconds: int | None = None) -> int: ...

    async def rate_limit(
        self, key: str, *, limit: int, window_seconds: int
    ) -> int: ...

    async def add_stats(self, session_id: str, stats: dict[str, Any]) -> None: ...

    async def stats_summary(self, session_id: str) -> dict[str, Any] | None: ...

    async def set_presence(
        self,
        session_id: str,
        participant_id: str,
        *,
        ttl_seconds: int = 90,
    ) -> None: ...

    async def clear_presence(self, session_id: str, participant_id: str) -> None: ...

    async def participants_seen(self, session_id: str) -> dict[str, float]: ...


class WsTicketManager(Protocol):
    async def issue(
        self,
        *,
        user_id: str,
        client_id: str,
        session_id: str | None = None,
        scope: str | None = None,
    ) -> tuple[str, str]: ...


def _session_seconds(session: RemoteDesktopSession) -> float | None:
    started = _parse_iso(session.startedAt or session.createdAt)
    if started is None:
        return None
    ended = _parse_iso(session.endedAt) if session.endedAt else datetime.now(UTC)
    return max(0.0, (ended - started).total_seconds())


def _is_active(session: RemoteDesktopSession | None) -> bool:
    return session is not None and session.state in ACTIVE_STATES


def _parse_iso(value: str | None) -> datetime:
    if not value:
        return datetime.now(UTC)
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError:
        return datetime.now(UTC)
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)


def _lease_after(seconds: int, now: str) -> str:
    return (
        (_parse_iso(now) + timedelta(seconds=seconds))
        .astimezone(UTC)
        .isoformat()
        .replace("+00:00", "Z")
    )


def _session_api(session: RemoteDesktopSession) -> dict[str, Any]:
    payload = session.to_api()
    if session.state == "awaiting_consent":
        payload["media"] = None
        return payload
    media = session.media_ref
    if session.state != "awaiting_consent" and not payload.get("media") and media is not None:
        payload["media"] = media.model_dump(mode="json")
    elif "media" not in payload:
        payload["media"] = None
    return payload


def _participant_dump(
    participant: RemoteDesktopParticipant,
) -> dict[str, Any]:
    return participant.model_dump(mode="json")


def _find_participant(
    session: RemoteDesktopSession, participant_id: str, user_id: str
) -> RemoteDesktopParticipant:
    for participant in session.participants:
        if (
            participant.participantId == participant_id
            and participant.userId == user_id
        ):
            return participant
    raise RemoteDesktopNotFoundError(
        message="Remote desktop participant not found.",
        details={"participantId": participant_id},
    )


def _find_participant_optional(
    session: RemoteDesktopSession, participant_id: str
) -> RemoteDesktopParticipant | None:
    return next(
        (
            participant
            for participant in session.participants
            if participant.participantId == participant_id
        ),
        None,
    )


def _remove_participant(
    participants: list[RemoteDesktopParticipant], participant_id: str
) -> list[RemoteDesktopParticipant]:
    return [
        participant
        for participant in participants
        if participant.participantId != participant_id
    ]


def _participant_from_identity(identity: str | None) -> str | None:
    if isinstance(identity, str) and identity.startswith("viewer:"):
        return identity[len("viewer:") :]
    return None


class RemoteDesktopService:
    def __init__(
        self,
        store: RemoteDesktopStore,
        requester: RemoteDesktopRequester,
        media: MediaProvider,
        broker: RemoteDesktopBrokerPort,
        ws_tickets: WsTicketManager,
        settings_provider: Callable[[str], Awaitable[ConnectorDeviceSettings]] | None = None,
        *,
        clock: Callable[[], str] = utc_now_iso,
        max_viewers_per_session: int = DEFAULT_MAX_VIEWERS_PER_SESSION,
        metrics: Any | None = None,
    ) -> None:
        self._store = store
        self._requester = requester
        self._media = media
        self._broker = broker
        self._ws_tickets = ws_tickets
        self._settings_provider = settings_provider or (
            lambda connector_id: current_settings(store, connector_id)
        )
        self._clock = clock
        self._max_viewers = max(1, max_viewers_per_session)
        self._metrics = metrics
        self._notification_handler = None

    # ------------------------------------------------------------------ read
    async def get_host_info(self, connector_id: str, user_id: str) -> dict[str, Any]:
        connector = await self._owned_connector(connector_id, user_id)
        settings = await self._settings(connector_id)
        facts = await host_facts(connector_id, self._requester, self._broker)
        active = await self._store.find_active_remote_desktop_session(connector_id)
        viewer_count = 0
        if active is not None:
            seen = await self._broker.participants_seen(active.id)
            viewer_count = len(seen) if seen else len(active.participants)
        capabilities = await build_capabilities(
            connector_id,
            settings,
            facts,
            media_available=self._media.available,
            active_session=active,
            viewer_count=viewer_count,
            max_viewers=self._max_viewers + 1,
            revision=await self._next_revision(connector_id),
        )
        host_payload: dict[str, Any] | None = facts.model_dump(mode="json")
        if getattr(facts, "unavailableReason", None) == "connector_offline":
            host_payload = None
        return {
            "connectorId": connector.id,
            "capabilities": [item.model_dump(mode="json") for item in capabilities],
            "host": host_payload,
            "serverTime": self._clock(),
        }

    async def get_session(self, session_id: str, user_id: str) -> dict[str, Any]:
        session = await self._get_session(session_id, user_id)
        return self._session_response(session)

    def _session_response(self, session: RemoteDesktopSession) -> dict[str, Any]:
        return {"session": self._session_api(session)}

    def _session_api(self, session: RemoteDesktopSession) -> dict[str, Any]:
        payload = _session_api(session)
        if (
            payload.get("media") is None
            and session.mediaRoom
            and session.state != "awaiting_consent"
        ):
            # The repository stores the room but not the provider URL (it may
            # change between restarts); use the live provider configuration.
            url = getattr(self._media, "join_url", None)
            if isinstance(url, str) and url:
                payload["media"] = {
                    "provider": session.mediaProvider or "livekit",
                    "room": session.mediaRoom,
                    "url": url,
                }
        return payload

    def session_snapshot(self, session: RemoteDesktopSession) -> dict[str, Any]:
        """Public serializer shared with the WebSocket snapshot path."""

        return self._session_api(session)

    async def list_sessions(
        self,
        *,
        user_id: str,
        connector_id: str | None = None,
        state: str | None = None,
        limit: int = 50,
        cursor: str | None = None,
    ) -> dict[str, Any]:
        sessions, next_cursor = await self._store.list_remote_desktop_sessions(
            user_id=user_id,
            connector_id=connector_id,
            state=state,
            limit=limit,
            cursor=cursor,
        )
        return {
            "sessions": [self._session_api(session) for session in sessions],
            "nextCursor": next_cursor,
        }

    # ------------------------------------------------------------------ create / join
    async def create_or_join(
        self,
        connector_id: str,
        user_id: str,
        request: dict[str, Any],
        *,
        client_platform: str,
        app_version: str | None,
    ) -> dict[str, Any]:
        connector = await self._owned_connector(connector_id, user_id)
        mode = request.get("mode")
        if mode not in {"view", "control"}:
            raise RemoteDesktopStateError(
                message="mode must be view or control", details={"mode": mode}
            )
        settings = await self._settings(connector_id)
        facts = await host_facts(connector_id, self._requester, self._broker)
        active = await self._store.find_active_remote_desktop_session(connector_id)
        capabilities = await build_capabilities(
            connector_id,
            settings,
            facts,
            media_available=self._media.available,
            active_session=active,
            viewer_count=len(active.participants) if active is not None else 0,
            max_viewers=self._max_viewers + 1,
            revision=await self._next_revision(connector_id),
        )
        self._require_capability(capabilities, mode, request)
        if active is not None:
            return await self._join(
                active,
                user_id,
                request,
                client_platform=client_platform,
                app_version=app_version,
                settings=settings,
            )
        try:
            return await self._create(
                connector,
                user_id,
                request,
                client_platform=client_platform,
                app_version=app_version,
                settings=settings,
            )
        except RemoteDesktopConflictError:
            # Another worker won the partial-unique-index race; join its session.
            active = await self._store.find_active_remote_desktop_session(
                connector_id
            )
            if active is None:
                raise
            return await self._join(
                active,
                user_id,
                request,
                client_platform=client_platform,
                app_version=app_version,
                settings=settings,
            )

    def _require_capability(
        self,
        capabilities: list[Any],
        mode: str,
        request: Mapping[str, Any],
    ) -> None:
        by_id = {item.capabilityId: item for item in capabilities}
        view = by_id.get("connector.remote_desktop.view")
        if view is None or not view.allowed:
            reason = (view.unavailableReason if view else None) or "device_policy_disabled"
            if reason == "session_limit_reached":
                raise RemoteDesktopLimitError(
                    status_code=429,
                    message="Remote desktop viewer limit reached.",
                    details={"limit": self._max_viewers},
                )
            raise RemoteDesktopUnavailableError(
                message="Remote desktop is not available.",
                status_code=409,
                details={"reason": reason},
            )
        if (
            (not view.supported or not view.available)
            and view.unavailableReason
            not in {"media_not_configured", "host_not_running"}
        ):
            # `media_not_configured` is reported by the media grant path itself
            # (503 media_unavailable) so a control session that only needs the
            # host after consent is not rejected before the user can act.
            raise RemoteDesktopUnavailableError(
                message="Remote desktop is not available.",
                status_code=409,
                details={"reason": view.unavailableReason or "connector_offline"},
            )
        if mode == "control":
            control = by_id.get("connector.remote_desktop.control")
            if control is None or not control.allowed:
                raise RemoteDesktopUnavailableError(
                    message="Remote control is not available.",
                    status_code=409,
                    details={"reason": (control.unavailableReason if control else None) or "device_policy_disabled"},
                )

    async def _create(
        self,
        connector: Any,
        user_id: str,
        request: Mapping[str, Any],
        *,
        client_platform: str,
        app_version: str | None,
        settings: ConnectorDeviceSettings,
    ) -> dict[str, Any]:
        session_id = f"rds_{secrets.token_hex(16)}"
        participant_id = new_participant_id()
        mode = request["mode"]
        quality = clamp_quality(request.get("quality"), settings)
        consent_required = bool(
            mode == "control"
            and settings.remoteDesktopRequireConsent
            and not settings.remoteDesktopAllowUnattended
        )
        now = self._clock()
        state = "awaiting_consent" if consent_required else "starting"
        if not self._media.available and not consent_required:
            from agent_server.services.remote_desktop_media import (
                RemoteDesktopMediaUnavailableError,
            )

            raise RemoteDesktopMediaUnavailableError(
                message="Remote desktop media is not configured.",
                details={"reason": "media_not_configured"},
            )
        room = f"rd_{session_id}" if self._media.available else None
        participants = participants_with_new(
            [],
            participant_id,
            user_id,
            platform="unknown",
            app_version=app_version,
            at=now,
        )
        session = await self._store.create_remote_desktop_session(
            session_id=session_id,
            connector_id=connector.id,
            connector_name=connector.name,
            user_id=user_id,
            created_by_participant_id=participant_id,
            mode=mode,
            display_id=request.get("displayId"),
            state=state,
            quality=quality,
            media_provider=getattr(self._media, "name", "livekit"),
            media_room=room,
            participants=[_participant_dump(item) for item in participants],
            consent_deadline_at=(
                _lease_after(CONSENT_TIMEOUT_SECONDS, now)
                if consent_required
                else None
            ),
            started_at=None if consent_required else now,
            linked_session_id=(
                request.get("link", {}).get("sessionId")
                if isinstance(request.get("link"), Mapping)
                else None
            ),
        )
        if self._metrics is not None:
            try:
                self._metrics.record_created(mode)
            except Exception:  # noqa: BLE001 - metrics are advisory
                logger.debug("remote desktop metrics record_created failed")
        grant: MediaGrant | None = None
        host_grant: MediaGrant | None = None
        try:
            if mode == "control":
                await self._update_session(
                    session,
                    controller=viewer_identity(participant_id),
                    controllerLeaseExpiresAt=_lease_after(
                        CONTROLLER_LEASE_SECONDS, now
                    ),
                )
                session = await self._get_session(session.id, user_id)
            if self._media.available:
                # The host publishes, so it needs its own publish token even in
                # the consent-first flow where the client gets media=null.
                host_grant = await self._media.create_host_grant(
                    session, connector.id
                )
            if not consent_required:
                grant = await self._grant(
                    session,
                    participant_id,
                    role="controller" if mode == "control" else "viewer",
                )
            await self._start_host(
                session,
                participant_id=participant_id,
                mode=mode,
                quality=quality,
                grant=host_grant,
                consent_required=consent_required,
            )
        except asyncio.CancelledError:
            raise
        except RemoteDesktopError as exc:
            await self.end_from_system(session.id, "media_error")
            reason = (
                exc.details.get("reason")
                if isinstance(exc.details, Mapping)
                else None
            )
            if exc.code == "media_unavailable":
                raise
            raise RemoteDesktopUnavailableError(
                message="Remote desktop host is not available.",
                status_code=409,
                details={"reason": reason or "host_not_running"},
            ) from exc
        except Exception as exc:
            logger.warning(
                "remote desktop start failed connector_id={} error={}",
                connector.id,
                str(exc) or exc.__class__.__name__,
            )
            await self.end_from_system(session.id, "media_error")
            raise RemoteDesktopUnavailableError(
                message="Remote desktop host is not available.",
                status_code=409,
                details={"reason": self._rpc_reason(exc)},
            ) from exc

        session = await self._get_session(session.id, user_id)
        ws = await self._issue_ws(session, participant_id)
        return {
            "session": self._session_api(session),
            "participantId": participant_id,
            "media": grant.to_dict() if grant is not None else None,
            "ws": ws,
            "created": True,
        }

    async def _join(
        self,
        session: RemoteDesktopSession,
        user_id: str,
        request: Mapping[str, Any],
        *,
        client_platform: str,
        app_version: str | None,
        settings: ConnectorDeviceSettings,
    ) -> dict[str, Any]:
        if len(session.participants) >= self._max_viewers + 1:
            raise RemoteDesktopLimitError(
                status_code=429,
                message="Remote desktop viewer limit reached.",
                details={"limit": self._max_viewers},
            )
        participant_id = new_participant_id()
        now = self._clock()
        participants = participants_with_new(
            session.participants,
            participant_id,
            user_id,
            platform="unknown",
            app_version=app_version,
            at=now,
        )
        mode = request["mode"]
        grant: MediaGrant | None = None
        fields: dict[str, Any] = {}
        previous_controller = session.controller
        if (
            mode == "control"
            and session.state in {"starting", "live", "degraded", "interrupted"}
        ):
            identity = viewer_identity(participant_id)
            fields["controller"] = identity
            fields["controllerLeaseExpiresAt"] = _lease_after(
                CONTROLLER_LEASE_SECONDS, now
            )
        await self._store.update_remote_desktop_participants(
            session.id, [_participant_dump(item) for item in participants]
        )
        if fields:
            await self._update_session(session, **fields)
        session = await self._get_session(session.id, user_id)
        try:
            if mode == "view" and session.state != "awaiting_consent":
                if session.mediaRoom:
                    grant = await self._grant(session, participant_id, role="viewer")
            elif fields.get("controller"):
                grant = await self._grant(
                    session, participant_id, role="controller"
                )
                await self._apply_takeover(
                    session,
                    participant_id,
                    previous_controller=previous_controller,
                )
        except asyncio.CancelledError:
            raise
        except RemoteDesktopError:
            raise
        except Exception as exc:
            logger.warning(
                "remote desktop join grant failed session_id={} error={}",
                session.id,
                str(exc) or exc.__class__.__name__,
            )
            raise RemoteDesktopUnavailableError(
                message="Remote desktop media is not available.",
                status_code=503,
                details={"reason": "media_not_configured"},
            ) from exc
        session = await self._get_session(session.id, user_id)
        await self.publish_session_updated(session)
        ws = await self._issue_ws(session, participant_id)
        return {
            "session": self._session_api(session),
            "participantId": participant_id,
            "media": grant.to_dict() if grant is not None else None,
            "ws": ws,
            "created": False,
        }

    async def _apply_takeover(
        self,
        session: RemoteDesktopSession,
        participant_id: str,
        *,
        previous_controller: str | None,
    ) -> None:
        identity = viewer_identity(participant_id)
        lease = session.controllerLeaseExpiresAt
        if previous_controller and previous_controller != identity:
            room = session.mediaRoom or f"rd_{session.id}"
            await self._media.update_can_publish_data(
                session, previous_controller, False
            )
            old_participant = _participant_from_identity(previous_controller)
            if old_participant:
                await self._broker.publish_event(
                    session.id,
                    {
                        "type": "remote_desktop.control.revoked",
                        "payload": {
                            "sessionId": session.id,
                            "reason": "taken_over",
                        },
                    },
                    target_participant=old_participant,
                )
            logger.info(
                "remote desktop controller taken over session_id={} old={} room={}",
                session.id,
                previous_controller,
                room,
            )
        await self._grant_host_control(session, identity, lease)

    async def _start_host(
        self,
        session: RemoteDesktopSession,
        *,
        participant_id: str,
        mode: str,
        quality: Mapping[str, Any],
        grant: MediaGrant | None,
        consent_required: bool,
    ) -> None:
        media_payload = grant.to_dict() if grant is not None else None
        await self._requester.request(
            session.connectorId,
            "remoteDesktop.start",
            {
                "sessionId": session.id,
                "displayId": session.displayId,
                "mode": mode,
                "quality": dict(quality),
                "media": media_payload,
                "consent": {
                    "required": consent_required,
                    "timeoutMs": CONSENT_TIMEOUT_SECONDS * 1000
                    if consent_required
                    else 0,
                },
                "controllerIdentity": viewer_identity(participant_id)
                if mode == "control"
                else None,
                "participantId": participant_id,
            },
            timeout=10,
        )

    async def _grant_host_control(
        self,
        session: RemoteDesktopSession,
        controller_identity: str | None,
        lease_expires_at: str | None,
    ) -> None:
        try:
            await self._requester.request(
                session.connectorId,
                "remoteDesktop.control.grant",
                {
                    "sessionId": session.id,
                    "controllerIdentity": controller_identity,
                    "controllerLeaseExpiresAt": lease_expires_at,
                },
                timeout=5,
            )
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - host input allowlist is best effort
            logger.warning(
                "remote desktop control grant failed session_id={} error={}",
                session.id,
                str(exc) or exc.__class__.__name__,
            )

    # ------------------------------------------------------------------ token / control
    async def issue_token(
        self,
        session_id: str,
        user_id: str,
        participant_id: str,
        role: str,
    ) -> dict[str, Any]:
        session = await self._get_session(session_id, user_id)
        _find_participant(session, participant_id, user_id)
        retry_after_ms = await self._broker.rate_limit(
            f"token:{session_id}:{participant_id}", limit=10, window_seconds=60
        )
        if retry_after_ms:
            raise RemoteDesktopRateLimitedError(
                message="Remote desktop token refresh is rate limited.",
                details={"retryAfterMs": retry_after_ms},
            )
        if role not in {"viewer", "controller"}:
            raise RemoteDesktopStateError(
                message="role must be viewer or controller", details={"role": role}
            )
        if session.state == "ended":
            raise RemoteDesktopStateError(
                message="remote desktop session has ended",
                details={"state": session.state},
            )
        if session.state == "awaiting_consent":
            # D3: no media token is issued until the device confirms consent.
            raise RemoteDesktopConsentPendingError(
                message="Remote desktop consent is still pending.",
                details={"sessionId": session.id},
            )
        if role == "controller" and session.controller != viewer_identity(participant_id):
            raise RemoteDesktopNotControllerError(
                message="Participant does not hold remote desktop control.",
                details={"participantId": participant_id},
            )
        grant = await self._grant(session, participant_id, role=role)
        return grant.to_dict()

    async def take_control(
        self, session_id: str, user_id: str, participant_id: str
    ) -> dict[str, Any]:
        session = await self._get_session(session_id, user_id)
        _find_participant(session, participant_id, user_id)
        retry_after_ms = await self._broker.rate_limit(
            f"take-control:{session_id}", limit=5, window_seconds=10
        )
        if retry_after_ms:
            raise RemoteDesktopRateLimitedError(
                message="Remote desktop control takeover is rate limited.",
                details={"retryAfterMs": retry_after_ms},
            )
        if session.state == "awaiting_consent":
            return {"session": self._session_api(session), "pending": True}
        if session.state not in {"live", "degraded"}:
            raise RemoteDesktopUnavailableError(
                message="Remote desktop session is not live.",
                status_code=409,
                details={"state": session.state},
            )
        identity = viewer_identity(participant_id)
        now = self._clock()
        lease = _lease_after(CONTROLLER_LEASE_SECONDS, now)
        previous_controller = session.controller
        if previous_controller == identity:
            await self._update_session(session, controllerLeaseExpiresAt=lease)
            session = await self._get_session(session_id, user_id)
            await self._grant_host_control(session, identity, lease)
            await self.publish_session_updated(session)
            return {"session": self._session_api(session), "pending": False}

        await self._update_session(
            session, controller=identity, controllerLeaseExpiresAt=lease
        )
        session = await self._get_session(session_id, user_id)
        await self._apply_takeover(
            session, participant_id, previous_controller=previous_controller
        )
        participants = touch_participant(
            session.participants, participant_id, self._clock()
        )
        await self._store.update_remote_desktop_participants(
            session.id, [_participant_dump(item) for item in participants]
        )
        session = await self._get_session(session_id, user_id)
        await self.publish_session_updated(session)
        return {"session": self._session_api(session), "pending": False}

    async def release_control(
        self, session_id: str, user_id: str, participant_id: str | None = None
    ) -> dict[str, Any]:
        session = await self._get_session(session_id, user_id)
        if participant_id is None:
            participant_id = _participant_from_identity(session.controller)
        if participant_id is None:
            raise RemoteDesktopNotControllerError(
                message="Participant does not hold remote desktop control.",
                details={"participantId": None},
            )
        _find_participant(session, participant_id, user_id)
        if session.controller != viewer_identity(participant_id):
            raise RemoteDesktopNotControllerError(
                message="Participant does not hold remote desktop control.",
                details={"participantId": participant_id},
            )
        await self._update_session(
            session, controller=None, controllerLeaseExpiresAt=None
        )
        session = await self._get_session(session_id, user_id)
        await self._media.update_can_publish_data(
            session, viewer_identity(participant_id), False
        )
        await self._grant_host_control(session, None, None)
        await self.publish_session_updated(session)
        return self._session_response(session)

    async def heartbeat(
        self, session_id: str, user_id: str, participant_id: str
    ) -> dict[str, Any]:
        session = await self._get_session(session_id, user_id)
        _find_participant(session, participant_id, user_id)
        if session.controller != viewer_identity(participant_id):
            raise RemoteDesktopNotControllerError(
                message="Participant does not hold remote desktop control.",
                details={"participantId": participant_id},
            )
        lease = _lease_after(CONTROLLER_LEASE_SECONDS, self._clock())
        await self._update_session(session, controllerLeaseExpiresAt=lease)
        participants = touch_participant(
            session.participants, participant_id, self._clock()
        )
        await self._store.update_remote_desktop_participants(
            session.id, [_participant_dump(item) for item in participants]
        )
        session = await self._get_session(session_id, user_id)
        # Deliberately publish on every heartbeat: the update is small, and a
        # deterministic stream is easier to reason about than a time-based
        # throttle. Lease expiry is driven by the DB timestamp, not this event.
        await self.publish_session_updated(session)
        return self._session_response(session)

    # ------------------------------------------------------------------ end
    async def end(
        self,
        session_id: str,
        user_id: str,
        reason: str,
        actor_participant_id: str | None = None,
    ) -> dict[str, Any]:
        session = await self._get_session(session_id, user_id)
        if actor_participant_id is not None:
            _find_participant(session, actor_participant_id, user_id)
        if session.state == "ended":
            return self._session_response(session)
        session = await self._end_session(session, reason)
        return self._session_response(session)

    async def end_from_system(
        self, session_id: str, reason: str
    ) -> RemoteDesktopSession | None:
        try:
            session = await self._store.get_remote_desktop_session(session_id)
        except KeyError:
            return None
        if session.state == "ended":
            return session
        return await self._end_session(session, reason)

    async def end_for_connector(
        self, connector_id: str, reason: str = "connector_deleted"
    ) -> int:
        """End every active session owned by a connector (device/user deletion)."""
        sessions = await self._store.list_active_remote_desktop_sessions(limit=500)
        ended = 0
        for session in sessions:
            if session.connectorId != connector_id:
                continue
            try:
                await self.end_from_system(session.id, reason)
                ended += 1
            except Exception:  # noqa: BLE001 - deletion must continue best effort
                logger.exception(
                    "failed to end remote desktop session during deletion session_id={}",
                    session.id,
                )
        return ended

    async def _end_session(
        self, session: RemoteDesktopSession, reason: str
    ) -> RemoteDesktopSession:
        ended_at = self._clock()
        ended = await self._store.end_remote_desktop_session(
            session.id,
            reason=reason,
            ended_at=ended_at,
        )
        if not ended:
            return await self._store.get_remote_desktop_session(session.id)
        if self._metrics is not None:
            try:
                self._metrics.record_ended(reason, duration_seconds=_session_seconds(session))
            except Exception:  # noqa: BLE001 - metrics are advisory
                logger.debug("remote desktop metrics record_ended failed")
        try:
            await self._requester.request(
                session.connectorId,
                "remoteDesktop.stop",
                {"sessionId": session.id, "reason": reason},
                timeout=3,
            )
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - ending must always finish locally
            logger.info(
                "remote desktop stop rpc failed session_id={} error={}",
                session.id,
                str(exc) or exc.__class__.__name__,
            )
        if session.mediaRoom:
            try:
                await self._media.delete_room(session.mediaRoom)
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001 - provider already logs; be extra safe
                logger.warning(
                    "failed to delete remote desktop room {}", session.mediaRoom
                )
        summary = None
        try:
            summary = await self._broker.stats_summary(session.id)
        except Exception:  # noqa: BLE001 - stats are advisory
            logger.warning(
                "failed to read remote desktop stats session_id={}", session.id
            )
        if summary is not None:
            try:
                await self._store.save_remote_desktop_stats_summary(
                    session.id, summary
                )
            except Exception:  # noqa: BLE001 - stats are advisory
                logger.warning(
                    "failed to persist remote desktop stats summary session_id={}",
                    session.id,
                )
        updated = await self._store.get_remote_desktop_session(session.id)
        await self.publish_ended(updated)
        for participant in updated.participants:
            await self._broker.clear_presence(updated.id, participant.participantId)
        return updated

    async def screenshot(
        self,
        session_id: str,
        user_id: str,
        participant_id: str | None,
        request: Mapping[str, Any],
    ) -> dict[str, Any]:
        session = await self._get_session(session_id, user_id)
        if participant_id:
            _find_participant(session, participant_id, user_id)
        if session.state == "ended":
            raise RemoteDesktopStateError(
                message="remote desktop session has ended",
                details={"state": session.state},
            )
        retry_after_ms = await self._broker.rate_limit(
            f"screenshot:{session.connectorId}", limit=1, window_seconds=2
        )
        if retry_after_ms:
            raise RemoteDesktopLimitError(
                code="remote_desktop_rate_limited",
                status_code=429,
                message="Remote desktop screenshot rate limit reached.",
                details={"retryAfterMs": retry_after_ms},
            )
        params: dict[str, Any] = {
            "sessionId": session.id,
            "format": request.get("format"),
        }
        for key in ("displayId", "maxWidth", "quality"):
            if request.get(key) is not None:
                params[key] = request[key]
        result = await self._requester.request(
            session.connectorId,
            "remoteDesktop.screenshot",
            params,
            timeout=10,
        )
        if not isinstance(result, Mapping):
            raise RemoteDesktopUnavailableError(
                message="Remote desktop screenshot failed.",
                details={"reason": "host_not_running"},
            )
        data_base64 = result.get("dataBase64") or result.get("jpegBase64")
        return {
            "width": int(result.get("width") or 1),
            "height": int(result.get("height") or 1),
            "capturedAt": result.get("capturedAt") or self._clock(),
            "dataBase64": data_base64 or "",
        }

    async def issue_ticket(
        self, session_id: str, user_id: str, participant_id: str
    ) -> dict[str, Any]:
        session = await self._get_session(session_id, user_id)
        _find_participant(session, participant_id, user_id)
        return await self._issue_ws(session, participant_id)

    async def _issue_ws(
        self, session: RemoteDesktopSession, participant_id: str
    ) -> dict[str, Any]:
        ticket, expires_at = await self._ws_tickets.issue(
            user_id=session.userId,
            client_id=participant_id,
            scope=f"{TICKET_SCOPE_PREFIX}:{session.id}",
        )
        return {
            "url": REMOTE_DESKTOP_WS_URL.format(session_id=session.id),
            "ticket": ticket,
            "expiresAt": expires_at,
        }

    # ------------------------------------------------------------------ WS helpers
    async def touch_participant(
        self, session_id: str, user_id: str, participant_id: str
    ) -> None:
        session = await self._get_session(session_id, user_id)
        _find_participant(session, participant_id, user_id)
        participants = touch_participant(
            session.participants, participant_id, self._clock()
        )
        await self._store.update_remote_desktop_participants(
            session.id, [_participant_dump(item) for item in participants]
        )
        await self._broker.set_presence(session.id, participant_id)

    async def request_keyframe(self, session_id: str, user_id: str) -> None:
        session = await self._get_session(session_id, user_id)
        await self._requester.request(
            session.connectorId,
            "remoteDesktop.requestKeyframe",
            {"sessionId": session.id},
            timeout=5,
        )

    async def update_quality(
        self,
        session_id: str,
        user_id: str,
        participant_id: str,
        requested: Mapping[str, Any],
    ) -> dict[str, Any]:
        session = await self._get_session(session_id, user_id)
        _find_participant(session, participant_id, user_id)
        if session.controller != viewer_identity(participant_id):
            raise RemoteDesktopNotControllerError(
                message="Only the controller can change quality.",
                details={"participantId": participant_id},
            )
        settings = await self._settings(session.connectorId)
        quality = clamp_quality(requested, settings)
        await self._requester.request(
            session.connectorId,
            "remoteDesktop.updateQuality",
            {"sessionId": session.id, **quality},
            timeout=5,
        )
        await self._update_session(session, quality=quality)
        session = await self._get_session(session_id, user_id)
        await self.publish_session_updated(session)
        return self._session_response(session)

    # ------------------------------------------------------------------ maintenance
    async def expire_controller(self, session_id: str) -> bool:
        """Clear an expired control lease without ending the session."""
        try:
            session = await self._store.get_remote_desktop_session(session_id)
        except KeyError:
            return False
        if session.state not in {"live", "degraded"} or not session.controller:
            return False
        lease = _parse_iso(session.controllerLeaseExpiresAt)
        if lease is not None and lease > datetime.now(UTC):
            return False
        previous_identity = session.controller
        participant_id = _participant_from_identity(previous_identity)
        await self._update_session(
            session, controller=None, controllerLeaseExpiresAt=None
        )
        session = await self._store.get_remote_desktop_session(session_id)
        try:
            await self._media.update_can_publish_data(
                session, previous_identity, False
            )
        except Exception:  # noqa: BLE001 - provider is best effort here
            logger.warning("failed to downgrade expired controller {}", session_id)
        try:
            await self._grant_host_control(session, None, None)
        except Exception:  # noqa: BLE001 - the host also rejects by epoch
            logger.info("failed to clear host controller for {}", session_id)
        if participant_id:
            await self.publish_control_revoked(
                session_id, "lease_expired", participant_id
            )
        await self.publish_session_updated(session)
        return True

    # ------------------------------------------------------------------ notifications
    async def handle_notification(
        self, connector_id: str, method: str, params: dict[str, Any]
    ) -> Any:
        if self._notification_handler is None:
            from agent_server.services.remote_desktop_notifications import (
                RemoteDesktopNotificationHandler,
            )

            self._notification_handler = RemoteDesktopNotificationHandler(self)
        return await self._notification_handler.apply(connector_id, method, params)

    async def publish_session_updated(self, session: RemoteDesktopSession) -> None:
        await self._broker.publish_event(
            session.id,
            {
                "type": "remote_desktop.session.updated",
                "payload": {
                    "sessionId": session.id,
                    "session": self._session_api(session),
                },
            },
        )

    async def publish_ended(self, session: RemoteDesktopSession) -> None:
        await self._broker.publish_event(
            session.id,
            {
                "type": "remote_desktop.ended",
                "payload": {
                    "sessionId": session.id,
                    "endReason": session.endReason,
                    "endedAt": session.endedAt,
                },
            },
        )

    async def publish_error(
        self,
        session_id: str,
        *,
        code: str,
        message: str,
        retryable: bool,
        details: Mapping[str, Any] | None = None,
    ) -> None:
        payload: dict[str, Any] = {
            "sessionId": session_id,
            "code": code,
            "message": message,
            "retryable": retryable,
        }
        if details:
            payload["details"] = dict(details)
        if self._metrics is not None:
            try:
                self._metrics.record_error(code)
            except Exception:  # noqa: BLE001 - metrics are advisory
                logger.debug("remote desktop metrics record_error failed")
        await self._broker.publish_event(
            session_id, {"type": "remote_desktop.error", "payload": payload}
        )

    async def publish_stats_updated(
        self, session_id: str, stats: Mapping[str, Any], *, at: str
    ) -> None:
        # Hosts emit roughly every 2 s; keep at most one fanout per window.
        marker_key = f"stats-published:{session_id}"
        if await self._broker.get_json(marker_key):
            return
        await self._broker.set_json(marker_key, True, ttl_seconds=2)
        await self._broker.publish_event(
            session_id,
            {
                "type": "remote_desktop.stats.updated",
                "payload": {
                    "sessionId": session_id,
                    "stats": dict(stats),
                    "at": at,
                },
            },
        )

    async def publish_control_revoked(
        self, session_id: str, reason: str, participant_id: str | None = None
    ) -> None:
        await self._broker.publish_event(
            session_id,
            {
                "type": "remote_desktop.control.revoked",
                "payload": {"sessionId": session_id, "reason": reason},
            },
            target_participant=participant_id,
        )

    async def session_for_connector(
        self, session_id: str, connector_id: str
    ) -> RemoteDesktopSession | None:
        try:
            session = await self._store.get_remote_desktop_session(session_id)
        except KeyError:
            logger.warning(
                "remote desktop notification for unknown session session_id={}",
                session_id,
            )
            return None
        if session.connectorId != connector_id:
            logger.warning(
                "ignoring remote desktop notification from another connector "
                "session_id={} expected={} got={}",
                session_id,
                session.connectorId,
                connector_id,
            )
            return None
        return session

    async def transition_session(
        self, session: RemoteDesktopSession, target_state: str, **fields: Any
    ) -> RemoteDesktopSession:
        changed = await self._store.transition_remote_desktop_session(
            session.id,
            expected_states={session.state},
            target_state=target_state,
            **fields,
        )
        if not changed:
            logger.info(
                "remote desktop state transition skipped session_id={} from={} to={}",
                session.id,
                session.state,
                target_state,
            )
        return await self._store.get_remote_desktop_session(session.id)

    async def update_session_fields(
        self, session: RemoteDesktopSession, **fields: Any
    ) -> RemoteDesktopSession:
        return await self._update_session(session, **fields)

    async def add_stats_snapshot(
        self, session: RemoteDesktopSession, stats: RemoteDesktopStats
    ) -> None:
        payload = stats.model_dump(mode="json", exclude_none=True)
        if self._metrics is not None:
            try:
                self._metrics.observe_stats(payload)
            except Exception:  # noqa: BLE001 - metrics are advisory
                logger.debug("remote desktop metrics observe_stats failed")
        await self._broker.add_stats(session.id, payload)
        await self._update_session(session, stats=payload)

    async def update_host_displays(
        self, connector_id: str, displays: list[Mapping[str, Any]]
    ) -> None:
        cache_key = f"hostinfo:{connector_id}"
        cached = await self._broker.get_json(cache_key)
        facts = dict(cached) if isinstance(cached, Mapping) else {}
        facts["displays"] = [dict(display) for display in displays]
        await self._broker.set_json(cache_key, facts, ttl_seconds=5)
        await self._next_revision(connector_id)

    async def mark_live_from_webhook(self, session_id: str) -> None:
        try:
            session = await self._store.get_remote_desktop_session(session_id)
        except KeyError:
            return
        if session.state not in {"starting", "awaiting_consent", "interrupted"}:
            return
        updated = await self.transition_session(
            session,
            "live",
            startedAt=session.startedAt or self._clock(),
            consentDeadlineAt=None,
        )
        await self.publish_session_updated(updated)

    async def mark_interrupted_from_webhook(self, session_id: str) -> None:
        try:
            session = await self._store.get_remote_desktop_session(session_id)
        except KeyError:
            return
        if session.state not in {"live", "degraded"}:
            return
        updated = await self.transition_session(
            session, "interrupted", interruptedAt=self._clock()
        )
        await self.publish_session_updated(updated)

    async def mark_connector_interrupted(self, connector_id: str) -> int:
        """Mark every live session of an offline connector interrupted."""
        sessions = await self._store.list_active_remote_desktop_sessions(limit=500)
        changed = 0
        for session in sessions:
            if session.connectorId != connector_id:
                continue
            if session.state not in {"starting", "live", "degraded"}:
                continue
            await self.mark_interrupted_from_webhook(session.id)
            changed += 1
        return changed

    # ------------------------------------------------------------------ internals
    async def _get_session(
        self, session_id: str, user_id: str
    ) -> RemoteDesktopSession:
        try:
            return await self._store.get_remote_desktop_session(
                session_id, user_id=user_id
            )
        except KeyError:
            raise RemoteDesktopNotFoundError(
                message="Remote desktop session not found.",
                details={"sessionId": session_id},
            ) from None

    async def _owned_connector(self, connector_id: str, user_id: str) -> Any:
        try:
            connector = await self._store.get_connector(connector_id)
        except KeyError:
            raise RemoteDesktopNotFoundError(
                code="connector_not_found",
                message="Connector not found.",
                details={"connectorId": connector_id},
            ) from None
        if connector.userId != user_id:
            raise RemoteDesktopNotFoundError(
                code="connector_not_found",
                message="Connector not found.",
                details={"connectorId": connector_id},
            )
        return connector

    async def _settings(self, connector_id: str) -> ConnectorDeviceSettings:
        try:
            settings = await self._settings_provider(connector_id)
        except KeyError:
            raise RemoteDesktopNotFoundError(
                code="connector_not_found",
                message="Connector not found.",
                details={"connectorId": connector_id},
            ) from None
        return (
            settings
            if isinstance(settings, ConnectorDeviceSettings)
            else device_settings_from_stored(settings)
        )

    async def _grant(
        self,
        session: RemoteDesktopSession,
        participant_id: str,
        *,
        role: str,
    ) -> MediaGrant:
        ttl = getattr(
            self._media,
            "controller_token_ttl_seconds"
            if role == "controller"
            else "viewer_token_ttl_seconds",
            120 if role == "controller" else 600,
        )
        return await self._media.create_grant(
            session, participant_id, role=role, ttl_seconds=ttl
        )

    async def _update_session(
        self, session: RemoteDesktopSession, **fields: Any
    ) -> RemoteDesktopSession:
        updater = getattr(self._store, "update_remote_desktop_session", None)
        changed = False
        if callable(updater):
            await updater(session.id, **fields)
            changed = True
        else:
            changed = await self._store.transition_remote_desktop_session(
                session.id,
                expected_states={session.state},
                target_state=session.state,
                **fields,
            )
        if not changed:
            raise RemoteDesktopStateError(
                message="Remote desktop session changed concurrently.",
                details={"sessionId": session.id, "state": session.state},
            )
        return await self._store.get_remote_desktop_session(session.id)

    async def _next_revision(self, connector_id: str) -> int:
        return await self._broker.increment(
            f"capability-revision:{connector_id}", ttl_seconds=86_400
        )

    @staticmethod
    def _rpc_reason(exc: BaseException) -> str:
        if isinstance(exc, (TimeoutError, asyncio.TimeoutError)):
            return "connector_offline"
        code = str(getattr(exc, "code", "") or "")
        if code in {"connector_offline", "connector_timeout"}:
            return "connector_offline"
        if exc.__class__.__name__ == "ConnectorOfflineError":
            return "connector_offline"
        return "host_not_running"


__all__ = [
    "ConsentPendingError",
    "NotControllerError",
    "RemoteDesktopService",
]



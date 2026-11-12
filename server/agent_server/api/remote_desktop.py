"""HTTP and WebSocket API for remote desktop (contract 1.0)."""

from __future__ import annotations

import json
import os
from typing import Annotated, Any, Literal

from fastapi import (
    APIRouter,
    Depends,
    HTTPException,
    Request,
    WebSocket,
    WebSocketDisconnect,
)
from fastapi.responses import JSONResponse
from loguru import logger
from pydantic import BaseModel, ConfigDict, Field

from agent_server.api.connector_common import require_owned_connector
from agent_server.core.remote_desktop import (
    RemoteDesktopError,
)
from agent_server.deps import (
    current_user_id,
    get_remote_desktop_broker,
    get_remote_desktop_service,
    get_store,
)
from agent_server.infra.livekit_webhook import (
    LiveKitWebhookError,
    claim_webhook_event,
    parse_webhook_event,
    verify_livekit_webhook,
)
from agent_server.infra.repositories.facade import Store
from agent_server.services.remote_desktop import RemoteDesktopService

router = APIRouter(tags=["remote-desktop"])

MAX_WEBHOOK_BODY_BYTES = 1024 * 1024
WS_TICKET_SCOPE_PREFIX = "remote-desktop"


class QualityRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    maxFps: int | None = Field(default=None, ge=5, le=60)
    maxBitrateKbps: int | None = Field(default=None, ge=500, le=20000)
    maxWidth: int | None = Field(default=None, ge=320, le=3840)
    preferCodec: Literal["vp8"] | None = None


class ClientRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    platform: Literal["web", "desktop", "android", "ios"]
    appVersion: str | None = None
    viewport: dict[str, Any] | None = None


class LinkRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionId: str


class CreateSessionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    connectorId: str
    displayId: str | None = None
    mode: Literal["view", "control"]
    quality: QualityRequest | None = None
    client: ClientRequest | None = None
    link: LinkRequest | None = None


class TokenRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    participantId: str
    role: Literal["viewer", "controller"]


class ParticipantRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    participantId: str


class ReleaseControlRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")


class EndRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    reason: Literal["client_ended"] = "client_ended"


class ScreenshotRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    displayId: str | None = None
    maxWidth: int | None = Field(default=1280, ge=320, le=3840)
    format: Literal["jpeg", "png"]
    quality: int | None = Field(default=None, ge=1, le=100)


def _raise_http(exc: RemoteDesktopError) -> None:
    detail: dict[str, Any] = {"code": exc.code, "message": exc.message}
    if exc.details:
        detail["details"] = exc.details
    raise HTTPException(status_code=exc.status_code, detail=detail) from exc


@router.get("/connectors/{connector_id}/remote-desktop")
async def get_remote_desktop_host_info(
    connector_id: str,
    user_id: Annotated[str, Depends(current_user_id)],
    store: Annotated[Store, Depends(get_store)],
    service: Annotated[RemoteDesktopService, Depends(get_remote_desktop_service)],
) -> dict[str, Any]:
    await require_owned_connector(connector_id, user_id, store)
    try:
        return await service.get_host_info(connector_id, user_id)
    except RemoteDesktopError as exc:
        _raise_http(exc)
        raise


@router.post("/remote-desktop/sessions")
async def create_remote_desktop_session(
    payload: CreateSessionRequest,
    user_id: Annotated[str, Depends(current_user_id)],
    store: Annotated[Store, Depends(get_store)],
    service: Annotated[RemoteDesktopService, Depends(get_remote_desktop_service)],
) -> JSONResponse:
    await require_owned_connector(payload.connectorId, user_id, store)
    client = payload.client
    try:
        result = await service.create_or_join(
            payload.connectorId,
            user_id,
            payload.model_dump(exclude_none=True),
            client_platform=client.platform if client else "unknown",
            app_version=client.appVersion if client else None,
        )
    except RemoteDesktopError as exc:
        _raise_http(exc)
        raise
    created = bool(result.pop("created", False))
    return JSONResponse(status_code=201 if created else 200, content=result)


@router.get("/remote-desktop/sessions")
async def list_remote_desktop_sessions(
    user_id: Annotated[str, Depends(current_user_id)],
    service: Annotated[RemoteDesktopService, Depends(get_remote_desktop_service)],
    connectorId: str | None = None,
    state: str | None = None,
    limit: int = 50,
    cursor: str | None = None,
) -> dict[str, Any]:
    try:
        return await service.list_sessions(
            user_id=user_id,
            connector_id=connectorId,
            state=state,
            limit=limit,
            cursor=cursor,
        )
    except RemoteDesktopError as exc:
        _raise_http(exc)
        raise


@router.get("/remote-desktop/sessions/{session_id}")
async def get_remote_desktop_session(
    session_id: str,
    user_id: Annotated[str, Depends(current_user_id)],
    service: Annotated[RemoteDesktopService, Depends(get_remote_desktop_service)],
) -> dict[str, Any]:
    try:
        return await service.get_session(session_id, user_id)
    except RemoteDesktopError as exc:
        _raise_http(exc)
        raise


@router.post("/remote-desktop/sessions/{session_id}/token")
async def refresh_remote_desktop_token(
    session_id: str,
    payload: TokenRequest,
    user_id: Annotated[str, Depends(current_user_id)],
    service: Annotated[RemoteDesktopService, Depends(get_remote_desktop_service)],
) -> dict[str, Any]:
    try:
        return await service.issue_token(
            session_id, user_id, payload.participantId, payload.role
        )
    except RemoteDesktopError as exc:
        _raise_http(exc)
        raise


@router.post("/remote-desktop/sessions/{session_id}/take-control")
async def take_remote_desktop_control(
    session_id: str,
    payload: ParticipantRequest,
    user_id: Annotated[str, Depends(current_user_id)],
    service: Annotated[RemoteDesktopService, Depends(get_remote_desktop_service)],
) -> JSONResponse:
    try:
        result = await service.take_control(
            session_id, user_id, payload.participantId
        )
    except RemoteDesktopError as exc:
        _raise_http(exc)
        raise
    pending = bool(result.pop("pending", False))
    return JSONResponse(status_code=202 if pending else 200, content=result)


@router.post("/remote-desktop/sessions/{session_id}/release-control")
async def release_remote_desktop_control(
    session_id: str,
    payload: ReleaseControlRequest,
    user_id: Annotated[str, Depends(current_user_id)],
    service: Annotated[RemoteDesktopService, Depends(get_remote_desktop_service)],
) -> dict[str, Any]:
    try:
        return await service.release_control(session_id, user_id)
    except RemoteDesktopError as exc:
        _raise_http(exc)
        raise


@router.post("/remote-desktop/sessions/{session_id}/heartbeat")
async def heartbeat_remote_desktop(
    session_id: str,
    payload: ParticipantRequest,
    user_id: Annotated[str, Depends(current_user_id)],
    service: Annotated[RemoteDesktopService, Depends(get_remote_desktop_service)],
) -> dict[str, Any]:
    try:
        return await service.heartbeat(
            session_id, user_id, payload.participantId
        )
    except RemoteDesktopError as exc:
        _raise_http(exc)
        raise


@router.post("/remote-desktop/sessions/{session_id}/end")
async def end_remote_desktop_session(
    session_id: str,
    payload: EndRequest,
    user_id: Annotated[str, Depends(current_user_id)],
    service: Annotated[RemoteDesktopService, Depends(get_remote_desktop_service)],
) -> dict[str, Any]:
    try:
        return await service.end(
            session_id, user_id, payload.reason
        )
    except RemoteDesktopError as exc:
        _raise_http(exc)
        raise


@router.post("/remote-desktop/sessions/{session_id}/ticket")
async def issue_remote_desktop_ticket(
    session_id: str,
    payload: ParticipantRequest,
    user_id: Annotated[str, Depends(current_user_id)],
    service: Annotated[RemoteDesktopService, Depends(get_remote_desktop_service)],
) -> dict[str, Any]:
    try:
        return await service.issue_ticket(
            session_id, user_id, payload.participantId
        )
    except RemoteDesktopError as exc:
        _raise_http(exc)
        raise


@router.post("/remote-desktop/sessions/{session_id}/screenshot")
async def screenshot_remote_desktop(
    session_id: str,
    payload: ScreenshotRequest,
    user_id: Annotated[str, Depends(current_user_id)],
    service: Annotated[RemoteDesktopService, Depends(get_remote_desktop_service)],
    participantId: str | None = None,
) -> dict[str, Any]:
    try:
        return await service.screenshot(
            session_id,
            user_id,
            participantId,
            payload.model_dump(exclude_none=True),
        )
    except RemoteDesktopError as exc:
        _raise_http(exc)
        raise


@router.post("/internal/livekit/webhook")
async def livekit_webhook(
    request: Request,
    broker: Annotated[Any, Depends(get_remote_desktop_broker)],
    service: Annotated[RemoteDesktopService, Depends(get_remote_desktop_service)],
) -> dict[str, Any]:
    raw_body = await request.body()
    if len(raw_body) > MAX_WEBHOOK_BODY_BYTES:
        raise HTTPException(status_code=413, detail="webhook body too large")
    api_key = os.environ.get("AGENT_SERVER_LIVEKIT_API_KEY", "").strip()
    api_secret = os.environ.get("AGENT_SERVER_LIVEKIT_API_SECRET", "").strip()
    if not api_key or not api_secret:
        raise HTTPException(status_code=503, detail="livekit webhook is not configured")
    try:
        verify_livekit_webhook(
            raw_body,
            request.headers.get("authorization"),
            api_key,
            api_secret,
        )
    except LiveKitWebhookError as exc:
        raise HTTPException(status_code=exc.status_code, detail=exc.message) from exc
    try:
        body = json.loads(raw_body)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail="invalid webhook body") from exc
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="invalid webhook body")
    if not await claim_webhook_event(broker, body):
        return {"ok": True, "duplicate": True}
    event = parse_webhook_event(body)
    room_name = event.room_name or ""
    if room_name.startswith("rd_"):
        session_id = room_name[len("rd_") :]
        if event.event == "room_finished":
            await service.end_from_system(session_id, "media_error")
        elif event.event == "track_published":
            await service.mark_live_from_webhook(session_id)
        elif event.event == "participant_left":
            identity = event.participant_identity or ""
            if identity.startswith("host:"):
                await service.mark_interrupted_from_webhook(session_id)
    return {"ok": True}


@router.websocket("/remote-desktop/sessions/{session_id}/ws")
async def remote_desktop_ws(websocket: WebSocket, session_id: str) -> None:
    ticket = websocket.query_params.get("ticket")
    requested_cursor = websocket.query_params.get("cursor")
    if not ticket:
        await websocket.close(code=1008, reason="missing ticket")
        return
    tickets = websocket.app.state.ws_tickets
    ticket_info = await tickets.consume_scope(
        ticket, scope=f"{WS_TICKET_SCOPE_PREFIX}:{session_id}"
    )
    if ticket_info is None:
        await websocket.close(code=1008, reason="invalid ticket")
        return
    user_id = ticket_info.user_id
    participant_id = ticket_info.client_id
    store: Store = websocket.app.state.store
    service: RemoteDesktopService = websocket.app.state.remote_desktop_service
    broker = websocket.app.state.remote_desktop_broker
    try:
        session = await store.get_remote_desktop_session(
            session_id, user_id=user_id
        )
    except KeyError:
        await websocket.close(code=1008, reason="session not found")
        return
    await websocket.accept()
    await broker.attach(session_id, participant_id, websocket)
    await broker.set_presence(session_id, participant_id)
    cursor = await broker.current_cursor(session_id)
    await websocket.send_json(
        {
            "type": "remote_desktop.subscribed",
            "payload": {"sessionId": session_id, "cursor": cursor},
        }
    )
    replay = (
        await broker.replay_after(session_id, requested_cursor)
        if requested_cursor
        else None
    )
    if not replay:
        await websocket.send_json(
            {
                "type": "remote_desktop.session.updated",
                "payload": {
                    "sessionId": session_id,
                    "session": service.session_snapshot(session),
                },
            }
        )
    else:
        for event in replay:
            await websocket.send_json(event)
    try:
        while True:
            message = await websocket.receive_json()
            if not isinstance(message, dict):
                continue
            message_type = message.get("type")
            if message_type == "ping":
                try:
                    await service.touch_participant(
                        session_id, user_id, participant_id
                    )
                except RemoteDesktopError:
                    pass
            elif message_type == "request_keyframe":
                try:
                    await service.request_keyframe(session_id, user_id)
                except Exception as exc:  # noqa: BLE001 - best effort by contract
                    await _send_ws_error(
                        websocket,
                        session_id,
                        code="remote_desktop_keyframe_unavailable",
                        message=str(exc) or "Keyframe request failed.",
                        retryable=True,
                    )
            elif message_type == "update_quality":
                requested = {
                    key: message[key]
                    for key in ("maxFps", "maxBitrateKbps", "maxWidth")
                    if key in message
                }
                try:
                    await service.update_quality(
                        session_id, user_id, participant_id, requested
                    )
                except RemoteDesktopError as exc:
                    await _send_ws_error(
                        websocket,
                        session_id,
                        code=exc.code,
                        message=exc.message,
                        retryable=False,
                    )
                except Exception as exc:  # noqa: BLE001 - keep the socket alive
                    logger.warning(
                        "remote desktop quality update failed session_id={} error={}",
                        session_id,
                        str(exc) or exc.__class__.__name__,
                    )
                    await _send_ws_error(
                        websocket,
                        session_id,
                        code="remote_desktop_update_failed",
                        message="Quality update failed.",
                        retryable=True,
                    )
    except WebSocketDisconnect:
        pass
    except RuntimeError:
        pass
    finally:
        await broker.detach(session_id, participant_id)
        await broker.clear_presence(session_id, participant_id)


async def _send_ws_error(
    websocket: WebSocket,
    session_id: str,
    *,
    code: str,
    message: str,
    retryable: bool,
) -> None:
    try:
        await websocket.send_json(
            {
                "type": "remote_desktop.error",
                "payload": {
                    "sessionId": session_id,
                    "code": code,
                    "message": message,
                    "retryable": retryable,
                },
            }
        )
    except (RuntimeError, OSError):
        pass

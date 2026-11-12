"""Remote desktop session domain model (docs/remote-desktop-decisions.md).

The wire contract lives in ``contracts/remote-desktop/1.0/schemas``; this
module is the server-side shape behind it: the session state machine, the
participant and quality helpers, and the stable error codes the API layer maps
onto HTTP. Media tokens, input events and clipboard payloads never touch these
models — they are never persisted.
"""

from __future__ import annotations

import json
import secrets
from collections.abc import Mapping, Sequence
from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from agent_server.core.utc import utc_now

RemoteDesktopState = Literal[
    "starting",
    "awaiting_consent",
    "live",
    "degraded",
    "interrupted",
    "ended",
]
RemoteDesktopMode = Literal["view", "control"]
RemoteDesktopEndReason = Literal[
    "client_ended",
    "host_ended",
    "consent_denied",
    "consent_timeout",
    "idle_timeout",
    "duration_limit",
    "connector_deleted",
    "connector_offline",
    "media_error",
    "policy_revoked",
]
RemoteDesktopUnavailableReason = Literal[
    "connector_offline",
    "host_not_running",
    "host_not_installed",
    "connector_outdated",
    "unsupported_platform",
    "screen_permission_denied",
    "accessibility_permission_denied",
    "device_policy_disabled",
    "session_limit_reached",
    "media_not_configured",
]
RemoteDesktopPlatform = Literal["windows", "macos", "linux", "unknown"]
HostPermissionState = Literal["granted", "denied", "not_determined", "unsupported"]

REMOTE_DESKTOP_CAPABILITY_VIEW = "connector.remote_desktop.view"
REMOTE_DESKTOP_CAPABILITY_CONTROL = "connector.remote_desktop.control"
REMOTE_DESKTOP_CAPABILITY_CLIPBOARD = "connector.remote_desktop.clipboard"

STATE_STARTING: RemoteDesktopState = "starting"
STATE_AWAITING_CONSENT: RemoteDesktopState = "awaiting_consent"
STATE_LIVE: RemoteDesktopState = "live"
STATE_DEGRADED: RemoteDesktopState = "degraded"
STATE_INTERRUPTED: RemoteDesktopState = "interrupted"
STATE_ENDED: RemoteDesktopState = "ended"

# Session state machine (decisions §3). `ended` is terminal: its empty set of
# successors makes every later transition invalid.
STATE_TRANSITIONS: dict[str, frozenset[str]] = {
    "starting": frozenset({"awaiting_consent", "live", "interrupted", "ended"}),
    "awaiting_consent": frozenset({"live", "ended"}),
    "live": frozenset({"degraded", "interrupted", "ended"}),
    "degraded": frozenset({"live", "interrupted", "ended"}),
    "interrupted": frozenset({"live", "ended"}),
    "ended": frozenset(),
}

MAX_PARTICIPANTS = 16

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


class RemoteDesktopError(Exception):
    """Base error carrying the stable wire code the API layer reuses."""

    default_code = "remote_desktop_error"
    default_status_code = 400

    def __init__(
        self,
        code: str | None = None,
        message: str = "",
        *,
        status_code: int | None = None,
        details: dict[str, Any] | None = None,
    ) -> None:
        resolved = code or self.default_code
        super().__init__(message or resolved)
        self.code = resolved
        self.message = message or resolved
        self.status_code = (
            status_code if status_code is not None else self.default_status_code
        )
        self.details = details


class RemoteDesktopStateError(RemoteDesktopError):
    default_code = "remote_desktop_invalid_state"
    default_status_code = 409


class RemoteDesktopNotFoundError(RemoteDesktopError):
    default_code = "remote_desktop_not_found"
    default_status_code = 404


class RemoteDesktopConflictError(RemoteDesktopError):
    default_code = "remote_desktop_busy"
    default_status_code = 409


class RemoteDesktopLimitError(RemoteDesktopError):
    default_code = "remote_desktop_limit_reached"
    default_status_code = 409


class RemoteDesktopUnavailableError(RemoteDesktopError):
    default_code = "remote_desktop_unavailable"
    default_status_code = 503


class RemoteDesktopNotControllerError(RemoteDesktopError):
    default_code = "remote_desktop_not_controller"
    default_status_code = 409


class RemoteDesktopConsentPendingError(RemoteDesktopError):
    default_code = "remote_desktop_consent_pending"
    default_status_code = 409


class RemoteDesktopMediaUnavailableError(RemoteDesktopError):
    default_code = "media_unavailable"
    default_status_code = 503


class RemoteDesktopRateLimitedError(RemoteDesktopError):
    default_code = "remote_desktop_rate_limited"
    default_status_code = 429


def validate_state_transition(current: str, target: str) -> None:
    """Raise :class:`RemoteDesktopStateError` unless ``current → target`` is legal.

    Terminal states (``ended``) have no successors, so they can never be left.
    """

    allowed = STATE_TRANSITIONS.get(current)
    if allowed is None or target not in allowed:
        raise RemoteDesktopStateError(
            message=f"cannot transition remote desktop session from {current} to {target}",
            details={"current": current, "target": target},
        )


class DisplayInfo(BaseModel):
    """One host display, mirroring ``host-info.schema.json``."""

    id: str
    name: str
    width: int
    height: int
    scale: float
    primary: bool
    refreshHz: int | None = None
    x: int = 0
    y: int = 0


class HostInputSupport(BaseModel):
    pointer: bool = False
    keyboard: bool = False
    touch: bool = False
    clipboard: bool = False


class HostPermissions(BaseModel):
    screenRecording: HostPermissionState = "not_determined"
    accessibility: HostPermissionState = "not_determined"
    inputMonitoring: HostPermissionState = "not_determined"


class HostFacts(BaseModel):
    """What the connector's screen host reports (server adds the capability)."""

    supported: bool = True
    available: bool = True
    unavailableReason: RemoteDesktopUnavailableReason | None = None
    platform: RemoteDesktopPlatform = "unknown"
    arch: Literal["x86_64", "aarch64", "unknown"] = "unknown"
    hostVersion: str | None = None
    displays: list[DisplayInfo] = Field(default_factory=list)
    codecs: list[str] = Field(default_factory=lambda: ["vp8"])
    maxFps: int | None = 60
    input: HostInputSupport = Field(default_factory=HostInputSupport)
    permissions: HostPermissions = Field(default_factory=HostPermissions)
    activeSessions: int = 0

    @classmethod
    def unavailable(
        cls,
        reason: RemoteDesktopUnavailableReason,
        *,
        supported: bool = True,
        platform: RemoteDesktopPlatform = "unknown",
    ) -> HostFacts:
        return cls(
            supported=supported,
            available=False,
            unavailableReason=reason,
            platform=platform,
            arch="unknown",
            hostVersion=None,
            displays=[],
            codecs=["vp8"],
            maxFps=60,
            input=HostInputSupport(),
            permissions=HostPermissions(
                screenRecording="unsupported",
                accessibility="unsupported",
                inputMonitoring="unsupported",
            ),
            activeSessions=0,
        )


class RemoteDesktopCapability(BaseModel):
    """Server-composed capability projection with a cache-busting revision."""

    capabilityId: Literal[
        "connector.remote_desktop.view",
        "connector.remote_desktop.control",
        "connector.remote_desktop.clipboard",
    ]
    scope: Literal["connector"] = "connector"
    connectorId: str = ""
    supported: bool = False
    available: bool = False
    allowed: bool = False
    unavailableReason: RemoteDesktopUnavailableReason | None = None
    revision: int = 0
    metadata: dict[str, Any] = Field(default_factory=dict)


class RemoteDesktopQuality(BaseModel):
    """Effective encode quality; v1 only supports VP8 (decisions §2.5)."""

    maxFps: int = QUALITY_DEFAULT_FPS
    maxBitrateKbps: int = QUALITY_DEFAULT_BITRATE_KBPS
    maxWidth: int = QUALITY_DEFAULT_WIDTH
    preferCodec: Literal["vp8"] = QUALITY_PREFERRED_CODEC


class RemoteDesktopStats(BaseModel):
    """Latest media statistics snapshot (``stats.updated`` payload shape)."""

    fps: float | None = None
    bitrateKbps: float | None = None
    rttMs: float | None = None
    jitterMs: float | None = None
    packetLossPct: float | None = None
    codec: str | None = None
    width: int | None = None
    height: int | None = None
    encoder: str | None = None
    subscribers: int | None = None


class RemoteDesktopMediaRef(BaseModel):
    """Non-secret media reference stored on a session (never a token)."""

    provider: Literal["livekit"] = "livekit"
    room: str
    url: str


class RemoteDesktopParticipant(BaseModel):
    participantId: str
    userId: str
    platform: RemoteDesktopPlatform = "unknown"
    appVersion: str | None = None
    createdAt: str
    lastSeenAt: str | None = None


class RemoteDesktopErrorInfo(BaseModel):
    code: str
    message: str
    retryable: bool = False


class RemoteDesktopSession(BaseModel):
    """One remote desktop session row (camelCase names mirror the contract)."""

    model_config = ConfigDict(extra="allow")

    id: str
    connectorId: str
    connectorName: str | None = None
    userId: str
    createdByParticipantId: str | None = None
    mode: RemoteDesktopMode
    displayId: str | None = None
    state: RemoteDesktopState
    endReason: RemoteDesktopEndReason | None = None
    controller: str | None = None
    controllerLeaseExpiresAt: str | None = None
    linkedSessionId: str | None = None
    mediaProvider: str
    mediaRoom: str | None = None
    mediaUrl: str | None = None
    quality: RemoteDesktopQuality = Field(default_factory=RemoteDesktopQuality)
    stats: RemoteDesktopStats | None = None
    error: RemoteDesktopErrorInfo | None = None
    participants: list[RemoteDesktopParticipant] = Field(default_factory=list)
    viewerCount: int = 0
    consentDeadlineAt: str | None = None
    interruptedAt: str | None = None
    lastInputAt: str | None = None
    lastViewerLeftAt: str | None = None
    startedAt: str | None = None
    endedAt: str | None = None
    workerId: str | None = None
    createdAt: str
    updatedAt: str

    @property
    def media_ref(self) -> RemoteDesktopMediaRef | None:
        if not self.mediaRoom or not self.mediaUrl:
            return None
        return RemoteDesktopMediaRef(
            provider=self.mediaProvider or "livekit",
            room=self.mediaRoom,
            url=self.mediaUrl,
        )

    def to_api(self) -> dict[str, Any]:
        payload = self.model_dump(mode="json", exclude_none=False)
        payload["viewerCount"] = self.viewerCount
        return payload

    @classmethod
    def from_row(cls, mapping: Mapping[str, Any]) -> RemoteDesktopSession:
        """Build a session from a ``remote_desktop_sessions`` row mapping.

        The JSON columns (``quality_json``/``stats_summary_json``/``error_json``/
        ``participants_json``) are parsed here; malformed historical payloads
        fall back to safe defaults so audit reads never fail.
        """

        def get(name: str) -> Any:
            return mapping.get(name, None)

        participants_raw = _json_loads(get("participants_json"))
        return cls(
            id=get("id"),
            connectorId=get("connector_id"),
            connectorName=get("connector_name"),
            userId=get("user_id"),
            createdByParticipantId=get("created_by_participant_id"),
            mode=get("mode"),
            displayId=get("display_id"),
            state=get("state"),
            endReason=get("end_reason"),
            controller=get("controller_identity"),
            controllerLeaseExpiresAt=get("controller_lease_expires_at"),
            linkedSessionId=get("linked_session_id"),
            mediaProvider=get("media_provider"),
            mediaRoom=get("media_room"),
            mediaUrl=get("media_url"),
            quality=_parse_model(RemoteDesktopQuality, get("quality_json"))
            or RemoteDesktopQuality(),
            stats=_parse_model(RemoteDesktopStats, get("stats_json"))
            or _parse_model(RemoteDesktopStats, get("stats_summary_json")),
            error=_parse_model(RemoteDesktopErrorInfo, get("error_json")),
            participants=[
                participant
                for participant in (
                    _parse_model(RemoteDesktopParticipant, item)
                    for item in (
                        participants_raw if isinstance(participants_raw, list) else []
                    )
                )
                if participant is not None
            ],
            viewerCount=len(participants_raw)
            if isinstance(participants_raw, list)
            else 0,
            consentDeadlineAt=get("consent_deadline_at"),
            interruptedAt=get("interrupted_at"),
            lastInputAt=get("last_input_at"),
            lastViewerLeftAt=get("last_viewer_left_at"),
            startedAt=get("started_at"),
            endedAt=get("ended_at"),
            workerId=get("worker_id"),
            createdAt=get("created_at"),
            updatedAt=get("updated_at"),
        )


def _json_loads(value: Any) -> Any:
    if not isinstance(value, str) or not value:
        return value
    try:
        return json.loads(value)
    except ValueError:
        return None


def _parse_model(model: type[BaseModel], value: Any) -> Any:
    """Parse one stored JSON payload; ``None``/invalid payloads map to ``None``."""

    parsed = _json_loads(value)
    if not isinstance(parsed, dict):
        return None
    try:
        return model.model_validate(parsed)
    except ValidationError:
        return None


ACTIVE_STATES: frozenset[str] = frozenset(
    {"starting", "awaiting_consent", "live", "degraded", "interrupted"}
)
TERMINAL_STATES: frozenset[str] = frozenset({"ended"})

CONSENT_TIMEOUT_SECONDS = 60
CONTROLLER_LEASE_SECONDS = 60
INTERRUPTED_GRACE_SECONDS = 90
IDLE_TIMEOUT_SECONDS = 15 * 60
MAX_SESSION_SECONDS = 8 * 60 * 60
PARTICIPANT_PRESENCE_TTL_SECONDS = 90


def utc_now_iso() -> str:
    return utc_now()


def parse_iso(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def viewer_identity(participant_id: str) -> str:
    return f"viewer:{participant_id}"


def host_identity(connector_id: str) -> str:
    return f"host:{connector_id}"


def new_participant_id() -> str:
    """An unguessable participant id: ``pt_`` plus URL-safe random characters."""

    return f"pt_{secrets.token_urlsafe(24)}"


def _clamp_int(value: Any, low: int, high: int, default: int) -> int:
    if isinstance(value, bool):
        return default
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return default
    return max(low, min(high, parsed))


def clamp_quality(
    requested: Mapping[str, Any] | None,
    settings: Any,
) -> dict[str, Any]:
    """Clamp a requested quality against the device settings caps.

    ``maxFps``/``maxBitrateKbps`` are clamped to their protocol ranges and then
    capped by the device settings (``remoteDesktopMaxFps``/``…BitrateKbps``);
    ``maxWidth`` clamps to 320–3840 (default 1600); ``preferCodec`` is always
    ``vp8`` in contract 1.0.
    """

    wanted: Mapping[str, Any] = requested if isinstance(requested, dict) else {}
    max_fps = min(
        _clamp_int(
            wanted.get("maxFps"),
            QUALITY_MIN_FPS,
            QUALITY_MAX_FPS,
            QUALITY_DEFAULT_FPS,
        ),
        _clamp_int(
            getattr(settings, "remoteDesktopMaxFps", QUALITY_DEFAULT_FPS),
            QUALITY_MIN_FPS,
            QUALITY_MAX_FPS,
            QUALITY_DEFAULT_FPS,
        ),
    )
    max_bitrate = min(
        _clamp_int(
            wanted.get("maxBitrateKbps"),
            QUALITY_MIN_BITRATE_KBPS,
            QUALITY_MAX_BITRATE_KBPS,
            QUALITY_DEFAULT_BITRATE_KBPS,
        ),
        _clamp_int(
            getattr(settings, "remoteDesktopMaxBitrateKbps", QUALITY_DEFAULT_BITRATE_KBPS),
            QUALITY_MIN_BITRATE_KBPS,
            QUALITY_MAX_BITRATE_KBPS,
            QUALITY_DEFAULT_BITRATE_KBPS,
        ),
    )
    return {
        "maxFps": max_fps,
        "maxBitrateKbps": max_bitrate,
        "maxWidth": _clamp_int(
            wanted.get("maxWidth"),
            QUALITY_MIN_WIDTH,
            QUALITY_MAX_WIDTH,
            QUALITY_DEFAULT_WIDTH,
        ),
        "preferCodec": QUALITY_PREFERRED_CODEC,
    }


def participants_with_new(
    participants: Sequence[RemoteDesktopParticipant] | None,
    participant_id: str,
    user_id: str,
    *,
    platform: RemoteDesktopPlatform = "unknown",
    app_version: str | None = None,
    at: str | None = None,
) -> list[RemoteDesktopParticipant]:
    """Return the participant list with ``participant_id`` added (max 16).

    Re-adding an existing participant only refreshes its ``lastSeenAt``. A new
    participant beyond :data:`MAX_PARTICIPANTS` raises
    :class:`RemoteDesktopLimitError`.
    """

    current = list(participants or [])
    known = {participant.participantId for participant in current}
    if participant_id in known:
        return touch_participant(current, participant_id, at or utc_now())
    if len(current) >= MAX_PARTICIPANTS:
        raise RemoteDesktopLimitError(
            message=f"remote desktop session already has {MAX_PARTICIPANTS} participants",
            details={"limit": MAX_PARTICIPANTS},
        )
    created_at = at or utc_now()
    return [
        *current,
        RemoteDesktopParticipant(
            participantId=participant_id,
            userId=user_id,
            platform=platform,
            appVersion=app_version,
            createdAt=created_at,
            lastSeenAt=created_at,
        ),
    ]


def touch_participant(
    participants: Sequence[RemoteDesktopParticipant] | None,
    participant_id: str,
    at: str | None = None,
) -> list[RemoteDesktopParticipant]:
    """Return the participant list with ``participant_id``'s ``lastSeenAt`` set.

    Raises ``KeyError`` for a participant that is not part of the session.
    """

    seen_at = at or utc_now()
    touched: list[RemoteDesktopParticipant] = []
    found = False
    for participant in participants or []:
        if participant.participantId == participant_id:
            touched.append(participant.model_copy(update={"lastSeenAt": seen_at}))
            found = True
        else:
            touched.append(participant)
    if not found:
        raise KeyError(participant_id)
    return touched


# ---------------------------------------------------------------------------
# Dict-shaped participant helpers.
#
# The repository persists participants as JSON objects and the service layer
# reasons about them as plain dicts while a session is being built. These
# helpers keep that boundary explicit; the Pydantic variants above are used
# once a row has been parsed into a session.
# ---------------------------------------------------------------------------


def parse_participants(raw: Any) -> list[dict[str, Any]]:
    if isinstance(raw, str):
        try:
            raw = json.loads(raw)
        except ValueError:
            return []
    if not isinstance(raw, list):
        return []
    participants: list[dict[str, Any]] = []
    for item in raw:
        if not isinstance(item, dict):
            continue
        participant_id = item.get("participantId")
        user_id = item.get("userId")
        if not isinstance(participant_id, str) or not isinstance(user_id, str):
            continue
        participants.append(dict(item))
    return participants


def add_participant(
    participants: list[dict[str, Any]],
    *,
    user_id: str,
    platform: str,
    app_version: str | None,
    now: str,
    participant_id: str | None = None,
) -> tuple[list[dict[str, Any]], str]:
    if len(participants) >= MAX_PARTICIPANTS:
        raise RemoteDesktopLimitError(
            message=f"remote desktop session already has {MAX_PARTICIPANTS} participants",
            details={"limit": MAX_PARTICIPANTS},
        )
    resolved = participant_id or new_participant_id()
    record = {
        "participantId": resolved,
        "userId": user_id,
        "platform": platform,
        "appVersion": app_version,
        "createdAt": now,
        "lastSeenAt": now,
    }
    return [*participants, record], resolved


def find_participant(
    participants: list[dict[str, Any]],
    participant_id: str,
    *,
    user_id: str | None = None,
) -> dict[str, Any] | None:
    for item in participants:
        if item.get("participantId") != participant_id:
            continue
        if user_id is not None and item.get("userId") != user_id:
            return None
        return item
    return None


def remove_participant(
    participants: list[dict[str, Any]],
    participant_id: str,
) -> list[dict[str, Any]]:
    return [
        item for item in participants if item.get("participantId") != participant_id
    ]


def active_viewer_count(
    participants: list[dict[str, Any]],
    *,
    now: datetime,
    ttl_seconds: int = PARTICIPANT_PRESENCE_TTL_SECONDS,
) -> int:
    count = 0
    for item in participants:
        last_seen = item.get("lastSeenAt")
        if not isinstance(last_seen, str):
            continue
        try:
            parsed = datetime.fromisoformat(last_seen)
        except ValueError:
            continue
        if (now - parsed).total_seconds() <= ttl_seconds:
            count += 1
    return count


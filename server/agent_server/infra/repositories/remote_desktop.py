"""Remote desktop session repository (``remote_desktop_sessions``).

Raw SQLAlchemy mixin in the style of ``protocol_catalogs``. The table is the
audit and state-machine source of truth: media tokens, input events and
clipboard payloads are never stored here, so no read method can leak them.

``**fields`` on the write helpers are ``remote_desktop_sessions`` column names
(``started_at=…``), with the camelCase wire aliases from
``docs/remote-desktop-decisions.md`` §2.1 (``startedAt``, ``endReason``,
``controller``, …) accepted for convenience; ``quality``/``stats``/``error``/
``participants`` aliases are serialized into their ``*_json`` columns.
"""

from __future__ import annotations

import secrets
from collections.abc import Mapping, Sequence
from typing import Any

from sqlalchemy import insert, select, tuple_, update
from sqlalchemy.exc import IntegrityError

from agent_server.core.remote_desktop import (
    RemoteDesktopConflictError,
    RemoteDesktopParticipant,
    RemoteDesktopQuality,
    RemoteDesktopSession,
    RemoteDesktopState,
    RemoteDesktopStats,
    validate_state_transition,
)
from agent_server.infra.db import remote_desktop_sessions as remote_desktop_sessions_t
from agent_server.infra.db.engine import read_connection
from agent_server.infra.repositories.store_support import *

# camelCase wire aliases → stored columns; the JSON-bearing aliases are
# serialized on write and parsed by ``RemoteDesktopSession.from_row``.
_FIELD_ALIASES: dict[str, tuple[str, bool]] = {
    "controller": ("controller_identity", False),
    "controllerLeaseExpiresAt": ("controller_lease_expires_at", False),
    "consentDeadlineAt": ("consent_deadline_at", False),
    "interruptedAt": ("interrupted_at", False),
    "lastInputAt": ("last_input_at", False),
    "lastViewerLeftAt": ("last_viewer_left_at", False),
    "startedAt": ("started_at", False),
    "endedAt": ("ended_at", False),
    "endReason": ("end_reason", False),
    "workerId": ("worker_id", False),
    "mediaRoom": ("media_room", False),
    "linkedSessionId": ("linked_session_id", False),
    "displayId": ("display_id", False),
    "controllerLease": ("controller_lease_expires_at", False),
    "quality": ("quality_json", True),
    "stats": ("stats_summary_json", True),
    "error": ("error_json", True),
    "participants": ("participants_json", True),
}

_JSON_COLUMNS = {"quality_json", "stats_summary_json", "error_json", "participants_json"}


def _new_session_id() -> str:
    return f"rds_{secrets.token_hex(16)}"


def _quality_payload(quality: Mapping[str, Any] | RemoteDesktopQuality | None) -> dict[str, Any]:
    if quality is None:
        return RemoteDesktopQuality().model_dump()
    if isinstance(quality, RemoteDesktopQuality):
        return quality.model_dump()
    return RemoteDesktopQuality.model_validate(dict(quality)).model_dump()


def _participant_payloads(
    participants: Sequence[RemoteDesktopParticipant | Mapping[str, Any]] | None,
) -> list[dict[str, Any]]:
    payloads: list[dict[str, Any]] = []
    for participant in participants or []:
        if isinstance(participant, RemoteDesktopParticipant):
            payloads.append(participant.model_dump())
        else:
            payloads.append(RemoteDesktopParticipant.model_validate(dict(participant)).model_dump())
    return payloads


def _column_values(fields: Mapping[str, Any]) -> dict[str, Any]:
    """Translate ``**fields`` into validated column values."""
    columns = set(remote_desktop_sessions_t.c.keys())
    values: dict[str, Any] = {}
    for key, value in fields.items():
        column, is_json = _FIELD_ALIASES.get(key, (key, key in _JSON_COLUMNS))
        if column not in columns or column == "id":
            raise ValueError(f"unknown remote desktop session field: {key}")
        if is_json:
            values[column] = _json_dumps(value) if value is not None else None
        else:
            values[column] = value
    return values


class RemoteDesktopRepositoryMixin:
    async def create_remote_desktop_session(
        self,
        *,
        connector_id: str,
        connector_name: str | None = None,
        user_id: str,
        created_by_participant_id: str | None = None,
        mode: str,
        display_id: str | None = None,
        quality: Mapping[str, Any] | RemoteDesktopQuality | None = None,
        media_provider: str,
        participants: Sequence[RemoteDesktopParticipant | Mapping[str, Any]] | None = None,
        media_room: str | None = None,
        linked_session_id: str | None = None,
        state: RemoteDesktopState = "starting",
        consent_deadline_at: str | None = None,
        started_at: str | None = None,
        session_id: str | None = None,
    ) -> RemoteDesktopSession:
        """Insert a new session; a live-session collision raises ConflictError.

        The partial unique index ``uq_rd_sessions_connector_active`` enforces
        one live session per connector, so a second insert is mapped to
        ``RemoteDesktopConflictError("remote_desktop_busy")``.
        """
        now = utc_now()
        values = {
            "id": session_id or _new_session_id(),
            "connector_id": connector_id,
            "connector_name": connector_name,
            "user_id": user_id,
            "created_by_participant_id": created_by_participant_id,
            "mode": mode,
            "display_id": display_id,
            "state": state,
            "media_provider": media_provider,
            "media_room": media_room,
            "quality_json": _json_dumps(_quality_payload(quality)),
            "participants_json": _json_dumps(_participant_payloads(participants)),
            "consent_deadline_at": consent_deadline_at,
            "started_at": started_at,
            "linked_session_id": linked_session_id,
            "created_at": now,
            "updated_at": now,
        }
        try:
            async with self._engine.begin() as conn:
                await conn.execute(insert(remote_desktop_sessions_t).values(**values))
        except IntegrityError as exc:
            raise RemoteDesktopConflictError("remote_desktop_busy") from exc
        return RemoteDesktopSession(
            id=values["id"],
            connectorId=connector_id,
            connectorName=connector_name,
            userId=user_id,
            createdByParticipantId=created_by_participant_id,
            mode=mode,  # type: ignore[arg-type]
            displayId=display_id,
            state=state,
            controller=None,
            linkedSessionId=linked_session_id,
            mediaProvider=media_provider,
            mediaRoom=media_room,
            quality=RemoteDesktopQuality.model_validate(_json_loads(values["quality_json"])),
            participants=[
                RemoteDesktopParticipant.model_validate(item)
                for item in _json_loads(values["participants_json"])
            ],
            consentDeadlineAt=consent_deadline_at,
            startedAt=started_at,
            createdAt=now,
            updatedAt=now,
        )

    async def get_remote_desktop_session(
        self,
        session_id: str,
        *,
        user_id: str | None = None,
        for_update: bool = False,
    ) -> RemoteDesktopSession:
        """Return one session; unknown ids and foreign users raise ``KeyError``."""
        statement = select(remote_desktop_sessions_t).where(
            remote_desktop_sessions_t.c.id == session_id
        )
        if user_id is not None:
            statement = statement.where(remote_desktop_sessions_t.c.user_id == user_id)
        if for_update:
            statement = statement.with_for_update()
            async with self._engine.begin() as conn:
                row = (await conn.execute(statement)).first()
        else:
            async with read_connection(self._engine) as conn:
                row = (await conn.execute(statement)).first()
        if row is None:
            raise KeyError(session_id)
        return RemoteDesktopSession.from_row(row._mapping)

    async def find_active_remote_desktop_session(
        self,
        connector_id: str,
    ) -> RemoteDesktopSession | None:
        """Return the connector's live session (at most one exists), if any."""
        statement = (
            select(remote_desktop_sessions_t)
            .where(
                remote_desktop_sessions_t.c.connector_id == connector_id,
                remote_desktop_sessions_t.c.ended_at.is_(None),
            )
            .order_by(remote_desktop_sessions_t.c.created_at.desc())
            .limit(1)
        )
        async with read_connection(self._engine) as conn:
            row = (await conn.execute(statement)).first()
        return RemoteDesktopSession.from_row(row._mapping) if row is not None else None

    async def list_remote_desktop_sessions(
        self,
        *,
        user_id: str,
        connector_id: str | None = None,
        state: str | None = None,
        limit: int = 50,
        cursor: str | None = None,
    ) -> tuple[list[RemoteDesktopSession], str | None]:
        """Page one user's sessions by ``created_at`` DESC, ``id`` DESC.

        ``cursor`` is the last ``session.id`` of the previous page; the
        continuation compares the ``(created_at, id)`` tuple so sessions that
        share a timestamp still page exactly once.
        """
        limit = max(1, min(200, limit))
        statement = select(remote_desktop_sessions_t).where(
            remote_desktop_sessions_t.c.user_id == user_id
        )
        if connector_id is not None:
            statement = statement.where(
                remote_desktop_sessions_t.c.connector_id == connector_id
            )
        if state is not None:
            statement = statement.where(remote_desktop_sessions_t.c.state == state)
        if cursor is not None:
            async with read_connection(self._engine) as conn:
                cursor_row = (
                    await conn.execute(
                        select(
                            remote_desktop_sessions_t.c.created_at,
                            remote_desktop_sessions_t.c.id,
                        ).where(
                            remote_desktop_sessions_t.c.id == cursor,
                            remote_desktop_sessions_t.c.user_id == user_id,
                        )
                    )
                ).first()
            if cursor_row is None:
                raise KeyError(cursor)
            statement = statement.where(
                tuple_(
                    remote_desktop_sessions_t.c.created_at,
                    remote_desktop_sessions_t.c.id,
                )
                < tuple_(cursor_row[0], cursor_row[1])
            )
        statement = statement.order_by(
            remote_desktop_sessions_t.c.created_at.desc(),
            remote_desktop_sessions_t.c.id.desc(),
        ).limit(limit + 1)
        async with read_connection(self._engine) as conn:
            rows = (await conn.execute(statement)).all()
        sessions = [RemoteDesktopSession.from_row(row._mapping) for row in rows[:limit]]
        next_cursor = sessions[-1].id if len(rows) > limit else None
        return sessions, next_cursor

    async def transition_remote_desktop_session(
        self,
        session_id: str,
        *,
        expected_states: set[str],
        target_state: str,
        **fields: Any,
    ) -> bool:
        """Conditionally advance the state machine; False when raced away.

        Raises :class:`RemoteDesktopStateError` when the transition is illegal
        from the row's current state, and ``KeyError`` for an unknown session.
        The UPDATE only matches ``state IN expected_states`` and unfinished
        rows, so exactly one worker can win a transition.
        """
        current = await self.get_remote_desktop_session(session_id)
        if target_state != current.state:
            validate_state_transition(current.state, target_state)
        values = {"state": target_state, "updated_at": utc_now()}
        values.update(_column_values(fields))
        result = None
        async with self._engine.begin() as conn:
            result = await conn.execute(
                update(remote_desktop_sessions_t)
                .where(
                    remote_desktop_sessions_t.c.id == session_id,
                    remote_desktop_sessions_t.c.state.in_(expected_states),
                    remote_desktop_sessions_t.c.ended_at.is_(None),
                )
                .values(**values)
            )
        return bool(result is not None and result.rowcount == 1)

    async def end_remote_desktop_session(
        self,
        session_id: str,
        *,
        reason: str,
        ended_at: str,
        **fields: Any,
    ) -> bool:
        """End a session exactly once; the second call returns False."""
        async with self._engine.begin() as conn:
            result = await conn.execute(
                update(remote_desktop_sessions_t)
                .where(
                    remote_desktop_sessions_t.c.id == session_id,
                    remote_desktop_sessions_t.c.ended_at.is_(None),
                )
                .values(
                    state="ended",
                    end_reason=reason,
                    ended_at=ended_at,
                    updated_at=utc_now(),
                    **_column_values(fields),
                )
            )
        return bool(result.rowcount == 1)

    async def update_remote_desktop_participants(
        self,
        session_id: str,
        participants: Sequence[RemoteDesktopParticipant | Mapping[str, Any]] | None,
    ) -> bool:
        async with self._engine.begin() as conn:
            result = await conn.execute(
                update(remote_desktop_sessions_t)
                .where(remote_desktop_sessions_t.c.id == session_id)
                .values(
                    participants_json=_json_dumps(_participant_payloads(participants)),
                    updated_at=utc_now(),
                )
            )
        return bool(result.rowcount == 1)

    async def list_active_remote_desktop_sessions(
        self,
        limit: int = 500,
    ) -> list[RemoteDesktopSession]:
        """Every unfinished session, oldest first (for the maintenance loop)."""
        statement = (
            select(remote_desktop_sessions_t)
            .where(remote_desktop_sessions_t.c.ended_at.is_(None))
            .order_by(
                remote_desktop_sessions_t.c.created_at.asc(),
                remote_desktop_sessions_t.c.id.asc(),
            )
            .limit(max(1, limit))
        )
        async with read_connection(self._engine) as conn:
            rows = (await conn.execute(statement)).all()
        return [RemoteDesktopSession.from_row(row._mapping) for row in rows]

    async def save_remote_desktop_stats_summary(
        self,
        session_id: str,
        stats: Mapping[str, Any] | RemoteDesktopStats | None,
    ) -> bool:
        """Persist the end-of-session stats summary (written after ``ended`` too)."""
        if stats is None:
            payload = None
        elif isinstance(stats, RemoteDesktopStats):
            payload = stats.model_dump()
        else:
            payload = dict(stats)
        async with self._engine.begin() as conn:
            result = await conn.execute(
                update(remote_desktop_sessions_t)
                .where(remote_desktop_sessions_t.c.id == session_id)
                .values(
                    stats_summary_json=_json_dumps(payload) if payload is not None else None,
                    updated_at=utc_now(),
                )
            )
        return bool(result.rowcount == 1)


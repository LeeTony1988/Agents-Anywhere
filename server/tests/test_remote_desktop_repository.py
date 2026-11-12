"""Repository tests for remote desktop sessions (needs the test database).

Not runnable without ``AGENT_SERVER_TEST_DB_URL`` (tests/conftest.py refuses to
start otherwise); verified locally with the inline core assertions instead.
"""

from __future__ import annotations

import asyncio

import pytest

from agent_server.core.remote_desktop import (
    RemoteDesktopConflictError,
    RemoteDesktopParticipant,
    RemoteDesktopSession,
    RemoteDesktopStateError,
)
from agent_server.infra.repositories.facade import Store


def _participant(participant_id: str, user_id: str = "user") -> RemoteDesktopParticipant:
    return RemoteDesktopParticipant(
        participantId=participant_id,
        userId=user_id,
        platform="windows",
        appVersion="2.3.0",
        createdAt="2026-11-13T00:00:00Z",
        lastSeenAt="2026-11-13T00:00:00Z",
    )


def _create(store: Store, connector_id: str, *, user_id: str = "user", **kwargs):
    return store.create_remote_desktop_session(
        connector_id=connector_id,
        connector_name="Workstation",
        user_id=user_id,
        created_by_participant_id="pt_a",
        mode="control",
        display_id="0",
        quality={"maxFps": 25, "maxBitrateKbps": 3000},
        media_provider="livekit",
        participants=[_participant("pt_a", user_id)],
        **kwargs,
    )


def test_create_get_round_trip() -> None:
    store = Store()
    try:

        async def run() -> None:
            created = await _create(store, "conn_a", media_room="rd_x")
            assert created.state == "starting"
            assert created.id.startswith("rds_")

            loaded = await store.get_remote_desktop_session(created.id, user_id="user")
            assert loaded.id == created.id
            assert loaded.connectorId == "conn_a"
            assert loaded.connectorName == "Workstation"
            assert loaded.userId == "user"
            assert loaded.createdByParticipantId == "pt_a"
            assert loaded.mode == "control"
            assert loaded.displayId == "0"
            assert loaded.mediaProvider == "livekit"
            assert loaded.mediaRoom == "rd_x"
            assert loaded.quality.maxFps == 25
            assert loaded.quality.maxBitrateKbps == 3000
            assert [p.participantId for p in loaded.participants] == ["pt_a"]
            assert loaded.startedAt is None
            assert loaded.endedAt is None

        asyncio.run(run())
    finally:
        asyncio.run(store.close())


def test_second_active_session_on_one_connector_conflicts() -> None:
    store = Store()
    try:

        async def run() -> None:
            first = await _create(store, "conn_a")
            with pytest.raises(RemoteDesktopConflictError) as raised:
                await _create(store, "conn_a")
            assert raised.value.code == "remote_desktop_busy"

            # Another connector is unaffected.
            other = await _create(store, "conn_b")
            assert other.id != first.id

            # Ending the first session frees the slot.
            assert await store.end_remote_desktop_session(
                first.id, reason="client_ended", ended_at="2026-11-13T01:00:00Z"
            )
            replacement = await _create(store, "conn_a")
            assert replacement.state == "starting"

        asyncio.run(run())
    finally:
        asyncio.run(store.close())


def test_cross_user_reads_raise_key_error() -> None:
    store = Store()
    try:

        async def run() -> None:
            created = await _create(store, "conn_a", user_id="user")
            assert await store.get_remote_desktop_session(created.id, user_id="user")
            with pytest.raises(KeyError):
                await store.get_remote_desktop_session(created.id, user_id="other")
            with pytest.raises(KeyError):
                await store.get_remote_desktop_session("rds_missing")

        asyncio.run(run())
    finally:
        asyncio.run(store.close())


def test_list_paginates_by_created_at_desc() -> None:
    store = Store()
    try:

        async def run() -> None:
            created = [
                await _create(store, f"conn_{index}", session_id=f"rds_{index:02d}")
                for index in range(5)
            ]
            expected = [
                session.id
                for session in sorted(
                    created, key=lambda session: (session.createdAt, session.id), reverse=True
                )
            ]

            first_page, next_cursor = await store.list_remote_desktop_sessions(
                user_id="user", limit=2
            )
            assert [session.id for session in first_page] == expected[:2]
            assert next_cursor == expected[1]

            second_page, next_cursor = await store.list_remote_desktop_sessions(
                user_id="user", limit=2, cursor=next_cursor
            )
            assert [session.id for session in second_page] == expected[2:4]
            assert next_cursor == expected[3]

            third_page, next_cursor = await store.list_remote_desktop_sessions(
                user_id="user", limit=2, cursor=next_cursor
            )
            assert [session.id for session in third_page] == expected[4:]
            assert next_cursor is None

            # Filters: another user sees nothing; connector/state narrow the list.
            assert await store.list_remote_desktop_sessions(user_id="other") == ([], None)
            filtered, cursor = await store.list_remote_desktop_sessions(
                user_id="user", connector_id="conn_3"
            )
            assert [session.id for session in filtered] == ["rds_03"]
            assert cursor is None
            by_state, _ = await store.list_remote_desktop_sessions(
                user_id="user", state="ended"
            )
            assert by_state == []
            with pytest.raises(KeyError):
                await store.list_remote_desktop_sessions(user_id="user", cursor="rds_missing")

        asyncio.run(run())
    finally:
        asyncio.run(store.close())


def test_conditional_transition_wins_exactly_once() -> None:
    store = Store()
    try:

        async def run() -> None:
            created = await _create(store, "conn_a")
            assert (
                await store.transition_remote_desktop_session(
                    created.id,
                    expected_states={"starting"},
                    target_state="live",
                    started_at="2026-11-13T00:01:00Z",
                )
                is True
            )
            # The same conditional update cannot fire a second time.
            assert (
                await store.transition_remote_desktop_session(
                    created.id,
                    expected_states={"starting"},
                    target_state="live",
                    started_at="2026-11-13T00:01:00Z",
                )
                is False
            )
            session = await store.get_remote_desktop_session(created.id)
            assert session.state == "live"
            assert session.startedAt == "2026-11-13T00:01:00Z"

            # An illegal target raises even when expected_states matches.
            with pytest.raises(RemoteDesktopStateError):
                await store.transition_remote_desktop_session(
                    created.id,
                    expected_states={"live"},
                    target_state="awaiting_consent",
                )
            with pytest.raises(KeyError):
                await store.transition_remote_desktop_session(
                    "rds_missing",
                    expected_states={"starting"},
                    target_state="live",
                )

        asyncio.run(run())
    finally:
        asyncio.run(store.close())


def test_end_is_idempotent_and_frees_the_active_slot() -> None:
    store = Store()
    try:

        async def run() -> None:
            created = await _create(store, "conn_a")
            assert await store.find_active_remote_desktop_session("conn_a") is not None
            assert await store.list_active_remote_desktop_sessions() != []

            assert (
                await store.end_remote_desktop_session(
                    created.id, reason="client_ended", ended_at="2026-11-13T02:00:00Z"
                )
                is True
            )
            assert (
                await store.end_remote_desktop_session(
                    created.id, reason="host_ended", ended_at="2026-11-13T03:00:00Z"
                )
                is False
            )
            session = await store.get_remote_desktop_session(created.id)
            assert session.state == "ended"
            assert session.endReason == "client_ended"
            assert session.endedAt == "2026-11-13T02:00:00Z"

            assert await store.find_active_remote_desktop_session("conn_a") is None
            assert await store.list_active_remote_desktop_sessions() == []

        asyncio.run(run())
    finally:
        asyncio.run(store.close())


def test_participants_and_stats_summary_round_trip() -> None:
    store = Store()
    try:

        async def run() -> None:
            created = await _create(store, "conn_a")
            participants = [
                _participant("pt_a"),
                _participant("pt_b"),
            ]
            assert (
                await store.update_remote_desktop_participants(created.id, participants) is True
            )
            loaded = await store.get_remote_desktop_session(created.id)
            assert [p.participantId for p in loaded.participants] == ["pt_a", "pt_b"]

            summary = {"fps": 29.5, "bitrateKbps": 2800, "packetLossPct": 0.4}
            assert await store.save_remote_desktop_stats_summary(created.id, summary) is True
            loaded = await store.get_remote_desktop_session(created.id)
            assert loaded.stats is not None
            assert loaded.stats.fps == 29.5
            assert loaded.stats.bitrateKbps == 2800

            assert (
                await store.update_remote_desktop_participants("rds_missing", []) is False
            )
            assert await store.save_remote_desktop_stats_summary("rds_missing", {}) is False

        asyncio.run(run())
    finally:
        asyncio.run(store.close())


def test_list_active_orders_oldest_first() -> None:
    store = Store()
    try:

        async def run() -> None:
            first = await _create(store, "conn_a", session_id="rds_01")
            second = await _create(store, "conn_b", session_id="rds_02")
            active = await store.list_active_remote_desktop_sessions()
            assert [session.id for session in active] == [first.id, second.id]
            assert all(isinstance(session, RemoteDesktopSession) for session in active)

        asyncio.run(run())
    finally:
        asyncio.run(store.close())

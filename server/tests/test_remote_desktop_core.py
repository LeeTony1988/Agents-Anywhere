"""Unit tests for the remote desktop domain model (no database)."""

from __future__ import annotations

import re

import pytest

from agent_server.core.device_settings import (
    ConnectorDeviceSettings,
    ConnectorDeviceSettingsPatch,
    apply_device_settings_patch,
    device_settings_from_stored,
)
from agent_server.core.remote_desktop import (
    MAX_PARTICIPANTS,
    RemoteDesktopCapability,
    RemoteDesktopConflictError,
    RemoteDesktopError,
    RemoteDesktopErrorInfo,
    RemoteDesktopLimitError,
    RemoteDesktopNotFoundError,
    RemoteDesktopParticipant,
    RemoteDesktopQuality,
    RemoteDesktopSession,
    RemoteDesktopStateError,
    RemoteDesktopStats,
    RemoteDesktopUnavailableError,
    clamp_quality,
    new_participant_id,
    participants_with_new,
    touch_participant,
    validate_state_transition,
)

LEGAL_TRANSITIONS = [
    ("starting", "awaiting_consent"),
    ("starting", "live"),
    ("starting", "interrupted"),
    ("starting", "ended"),
    ("awaiting_consent", "live"),
    ("awaiting_consent", "ended"),
    ("live", "degraded"),
    ("live", "interrupted"),
    ("live", "ended"),
    ("degraded", "live"),
    ("degraded", "interrupted"),
    ("degraded", "ended"),
    ("interrupted", "live"),
    ("interrupted", "ended"),
]

ILLEGAL_TRANSITIONS = [
    ("ended", "starting"),
    ("ended", "awaiting_consent"),
    ("ended", "live"),
    ("ended", "degraded"),
    ("ended", "interrupted"),
    ("ended", "ended"),
    ("starting", "starting"),
    ("starting", "degraded"),
    ("awaiting_consent", "starting"),
    ("awaiting_consent", "degraded"),
    ("awaiting_consent", "interrupted"),
    ("live", "starting"),
    ("live", "awaiting_consent"),
    ("live", "live"),
    ("degraded", "starting"),
    ("degraded", "awaiting_consent"),
    ("degraded", "degraded"),
    ("interrupted", "starting"),
    ("interrupted", "awaiting_consent"),
    ("interrupted", "degraded"),
    ("interrupted", "interrupted"),
    ("running", "live"),
    ("live", "unknown"),
    ("LIVE", "ended"),
]


@pytest.mark.parametrize(("current", "target"), LEGAL_TRANSITIONS)
def test_legal_state_transitions_are_accepted(current: str, target: str) -> None:
    assert validate_state_transition(current, target) is None


@pytest.mark.parametrize(("current", "target"), ILLEGAL_TRANSITIONS)
def test_illegal_state_transitions_are_rejected(current: str, target: str) -> None:
    with pytest.raises(RemoteDesktopStateError) as raised:
        validate_state_transition(current, target)
    assert raised.value.code == "remote_desktop_invalid_state"
    assert raised.value.status_code == 409


def test_ended_is_a_terminal_state() -> None:
    for target in ("starting", "awaiting_consent", "live", "degraded", "interrupted", "ended"):
        with pytest.raises(RemoteDesktopStateError):
            validate_state_transition("ended", target)


def test_new_participant_id_shape() -> None:
    participant_id = new_participant_id()
    assert participant_id.startswith("pt_")
    assert re.fullmatch(r"pt_[A-Za-z0-9_-]+", participant_id)
    assert len(participant_id) >= 16
    assert new_participant_id() != participant_id


def test_clamp_quality_defaults() -> None:
    assert clamp_quality(None, ConnectorDeviceSettings()) == {
        "maxFps": 30,
        "maxBitrateKbps": 4000,
        "maxWidth": 1600,
        "preferCodec": "vp8",
    }
    assert clamp_quality({}, ConnectorDeviceSettings())["maxWidth"] == 1600
    # Non-dict requests are ignored entirely.
    assert clamp_quality("bad", ConnectorDeviceSettings())["maxFps"] == 30


def test_clamp_quality_enforces_protocol_ranges() -> None:
    settings = ConnectorDeviceSettings(
        remoteDesktopMaxFps=60, remoteDesktopMaxBitrateKbps=20000
    )
    high = clamp_quality(
        {"maxFps": 500, "maxBitrateKbps": 99_999, "maxWidth": 99_999}, settings
    )
    assert high == {
        "maxFps": 60,
        "maxBitrateKbps": 20_000,
        "maxWidth": 3840,
        "preferCodec": "vp8",
    }
    low = clamp_quality({"maxFps": 1, "maxBitrateKbps": 1, "maxWidth": 1}, settings)
    assert low == {
        "maxFps": 5,
        "maxBitrateKbps": 500,
        "maxWidth": 320,
        "preferCodec": "vp8",
    }


def test_clamp_quality_caps_by_device_settings() -> None:
    settings = ConnectorDeviceSettings(
        remoteDesktopMaxFps=15, remoteDesktopMaxBitrateKbps=1000
    )
    clamped = clamp_quality(
        {"maxFps": 45, "maxBitrateKbps": 5000, "maxWidth": 2560}, settings
    )
    assert clamped == {
        "maxFps": 15,
        "maxBitrateKbps": 1000,
        "maxWidth": 2560,
        "preferCodec": "vp8",
    }
    # A request below the cap is honoured as-is.
    assert clamp_quality({"maxFps": 10}, settings)["maxFps"] == 10


def test_clamp_quality_pins_codec_and_ignores_junk_values() -> None:
    settings = ConnectorDeviceSettings()
    clamped = clamp_quality(
        {"preferCodec": "h264", "maxFps": True, "maxBitrateKbps": "fast"}, settings
    )
    assert clamped["preferCodec"] == "vp8"
    assert clamped["maxFps"] == 30
    assert clamped["maxBitrateKbps"] == 4000


def test_participants_with_new_and_touch() -> None:
    created = participants_with_new(
        [], "pt_a", "user", platform="windows", app_version="2.3.0", at="2026-11-13T00:00:00Z"
    )
    assert [participant.participantId for participant in created] == ["pt_a"]
    assert created[0].userId == "user"
    assert created[0].platform == "windows"
    assert created[0].appVersion == "2.3.0"
    assert created[0].createdAt == "2026-11-13T00:00:00Z"
    assert created[0].lastSeenAt == "2026-11-13T00:00:00Z"

    grown = participants_with_new(created, "pt_b", "user", at="2026-11-13T01:00:00Z")
    assert [participant.participantId for participant in grown] == ["pt_a", "pt_b"]

    touched = touch_participant(grown, "pt_a", "2026-11-13T02:00:00Z")
    assert touched[0].lastSeenAt == "2026-11-13T02:00:00Z"
    assert touched[0].createdAt == "2026-11-13T00:00:00Z"
    assert touched[1].lastSeenAt == "2026-11-13T01:00:00Z"

    # Re-adding a known participant only refreshes it; the list does not grow.
    refreshed = participants_with_new(touched, "pt_a", "user", at="2026-11-13T03:00:00Z")
    assert len(refreshed) == 2
    assert refreshed[0].lastSeenAt == "2026-11-13T03:00:00Z"


def test_touch_unknown_participant_raises_key_error() -> None:
    with pytest.raises(KeyError):
        touch_participant([], "pt_missing", "2026-11-13T00:00:00Z")


def test_participants_are_capped_at_sixteen() -> None:
    full = [
        RemoteDesktopParticipant(
            participantId=f"pt_{index}", userId="user", createdAt="2026-11-13T00:00:00Z"
        )
        for index in range(MAX_PARTICIPANTS)
    ]
    assert len(full) == 16
    with pytest.raises(RemoteDesktopLimitError) as raised:
        participants_with_new(full, "pt_overflow", "user", at="2026-11-13T00:00:00Z")
    assert raised.value.code == "remote_desktop_limit_reached"


def test_device_settings_round_trip_keeps_bools_and_in_range_ints() -> None:
    settings = ConnectorDeviceSettings(
        confirmQuitOnShutdown=True,
        remoteDesktopEnabled=True,
        remoteDesktopRequireConsent=False,
        remoteDesktopMaxFps=45,
        remoteDesktopMaxBitrateKbps=7500,
    )
    assert device_settings_from_stored(settings.model_dump()) == settings


def test_device_settings_drop_out_of_range_ints_to_defaults() -> None:
    for stored, expected in (
        ({"remoteDesktopMaxFps": 61}, 30),
        ({"remoteDesktopMaxFps": 4}, 30),
        ({"remoteDesktopMaxFps": 5}, 5),
        ({"remoteDesktopMaxFps": 60}, 60),
        ({"remoteDesktopMaxBitrateKbps": 20_001}, 4000),
        ({"remoteDesktopMaxBitrateKbps": 499}, 4000),
        ({"remoteDesktopMaxBitrateKbps": 500}, 500),
        ({"remoteDesktopMaxBitrateKbps": 20_000}, 20_000),
    ):
        settings = device_settings_from_stored(stored)
        name = next(iter(stored))
        assert getattr(settings, name) == expected, stored


def test_device_settings_never_confuse_bool_and_int() -> None:
    # A bool is not a valid integer setting: it falls back to the default.
    assert device_settings_from_stored({"remoteDesktopMaxFps": True}).remoteDesktopMaxFps == 30
    assert (
        device_settings_from_stored({"remoteDesktopMaxBitrateKbps": False}).remoteDesktopMaxBitrateKbps
        == 4000
    )
    # An int is not a valid boolean setting: it falls back to the default.
    assert device_settings_from_stored({"confirmQuitOnShutdown": 1}).confirmQuitOnShutdown is False
    assert device_settings_from_stored({"remoteDesktopEnabled": 0}).remoteDesktopEnabled is False


def test_device_settings_patch_round_trip() -> None:
    patch = ConnectorDeviceSettingsPatch(
        remoteDesktopEnabled=True,
        remoteDesktopMaxFps=60,
        remoteDesktopMaxBitrateKbps=500,
    )
    updated = apply_device_settings_patch(ConnectorDeviceSettings(), patch)
    assert updated.remoteDesktopEnabled is True
    assert updated.remoteDesktopMaxFps == 60
    assert updated.remoteDesktopMaxBitrateKbps == 500
    assert device_settings_from_stored(updated.model_dump()) == updated
    # An empty patch keeps everything.
    assert apply_device_settings_patch(updated, ConnectorDeviceSettingsPatch()) == updated


def test_device_settings_patch_rejects_out_of_range_ints() -> None:
    with pytest.raises(ValueError):
        ConnectorDeviceSettingsPatch.model_validate({"remoteDesktopMaxFps": 4})
    with pytest.raises(ValueError):
        ConnectorDeviceSettingsPatch.model_validate({"remoteDesktopMaxFps": 61})
    with pytest.raises(ValueError):
        ConnectorDeviceSettingsPatch.model_validate({"remoteDesktopMaxBitrateKbps": 499})
    with pytest.raises(ValueError):
        ConnectorDeviceSettingsPatch.model_validate({"remoteDesktopMaxBitrateKbps": 20_001})


def test_session_from_row_parses_json_columns() -> None:
    row = {
        "id": "rds_1",
        "connector_id": "conn_1",
        "connector_name": "Workstation",
        "user_id": "user_1",
        "created_by_participant_id": "pt_a",
        "mode": "control",
        "display_id": "0",
        "state": "live",
        "end_reason": None,
        "controller_identity": "viewer:pt_a",
        "controller_lease_expires_at": "2026-11-13T00:01:00Z",
        "linked_session_id": "sess_1",
        "media_provider": "livekit",
        "media_room": "rd_rds_1",
        "quality_json": '{"maxFps": 25, "maxBitrateKbps": 3000, "maxWidth": 1280}',
        "stats_summary_json": '{"fps": 29.5, "rttMs": 42}',
        "error_json": '{"code": "rd_media_error", "message": "boom", "retryable": true}',
        "participants_json": (
            '[{"participantId": "pt_a", "userId": "user_1", "platform": "windows",'
            ' "createdAt": "2026-11-13T00:00:00Z"}]'
        ),
        "consent_deadline_at": "2026-11-13T00:00:30Z",
        "interrupted_at": None,
        "last_input_at": "2026-11-13T00:00:40Z",
        "last_viewer_left_at": None,
        "started_at": "2026-11-13T00:00:10Z",
        "ended_at": None,
        "worker_id": "worker-1",
        "created_at": "2026-11-13T00:00:00Z",
        "updated_at": "2026-11-13T00:00:45Z",
    }

    session = RemoteDesktopSession.from_row(row)

    assert session.id == "rds_1"
    assert session.connectorName == "Workstation"
    assert session.controller == "viewer:pt_a"
    assert session.quality == RemoteDesktopQuality(
        maxFps=25, maxBitrateKbps=3000, maxWidth=1280
    )
    assert session.stats == RemoteDesktopStats(fps=29.5, rttMs=42)
    assert session.error == RemoteDesktopErrorInfo(
        code="rd_media_error", message="boom", retryable=True
    )
    assert [participant.participantId for participant in session.participants] == ["pt_a"]
    assert session.endedAt is None
    assert session.updatedAt == "2026-11-13T00:00:45Z"


def test_session_from_row_tolerates_missing_and_malformed_json() -> None:
    minimal = {
        "id": "rds_2",
        "connector_id": "conn_1",
        "user_id": "user_1",
        "mode": "view",
        "state": "starting",
        "media_provider": "noop",
        "quality_json": "{}",
        "participants_json": "[]",
        "created_at": "2026-11-13T00:00:00Z",
        "updated_at": "2026-11-13T00:00:00Z",
    }
    session = RemoteDesktopSession.from_row(minimal)
    assert session.quality == RemoteDesktopQuality()
    assert session.stats is None
    assert session.error is None
    assert session.participants == []

    broken = dict(
        minimal,
        quality_json="{not json",
        stats_summary_json='"nope"',
        error_json='{"unknown": true}',
        participants_json="[[1, 2]]",
    )
    session = RemoteDesktopSession.from_row(broken)
    assert session.quality == RemoteDesktopQuality()
    assert session.stats is None
    assert session.error is None
    assert session.participants == []


def test_error_classes_carry_stable_codes() -> None:
    assert RemoteDesktopError().code == "remote_desktop_error"
    assert RemoteDesktopError("custom_code", "boom").message == "boom"
    assert RemoteDesktopStateError().code == "remote_desktop_invalid_state"
    assert RemoteDesktopNotFoundError().status_code == 404
    conflict = RemoteDesktopConflictError("remote_desktop_busy")
    assert conflict.code == "remote_desktop_busy"
    assert conflict.status_code == 409
    limit = RemoteDesktopLimitError(code="remote_desktop_limit_reached")
    assert limit.code == "remote_desktop_limit_reached"
    unavailable = RemoteDesktopUnavailableError(details={"reason": "connector_offline"})
    assert unavailable.code == "remote_desktop_unavailable"
    assert unavailable.status_code == 503
    assert unavailable.details == {"reason": "connector_offline"}


def test_capability_projection_shape() -> None:
    capability = RemoteDesktopCapability(
        capabilityId="connector.remote_desktop.view",
        supported=True,
        available=True,
        allowed=True,
        revision=3,
    )
    assert capability.unavailableReason is None
    assert capability.revision == 3

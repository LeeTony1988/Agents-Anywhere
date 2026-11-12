from __future__ import annotations

from agent_server.core.device_settings import ConnectorDeviceSettings
from agent_server.core.remote_desktop import HostFacts
from agent_server.services.remote_desktop_policy import build_capabilities_sync


def _capabilities(facts: HostFacts, settings: ConnectorDeviceSettings | None = None):
    return build_capabilities_sync(
        "conn_x",
        settings or ConnectorDeviceSettings(remoteDesktopEnabled=True, remoteDesktopAllowControl=True),
        facts,
        media_available=True,
        active_session=None,
        viewer_count=0,
        max_viewers=3,
        revision=1,
    )


def test_stopped_host_is_available_because_it_starts_on_demand() -> None:
    facts = HostFacts.unavailable("host_not_running")
    by_id = {cap.capabilityId: cap for cap in _capabilities(facts)}
    view = by_id["connector.remote_desktop.view"]
    assert view.supported is True
    assert view.available is True
    assert view.unavailableReason is None
    assert view.allowed is True


def test_offline_connector_is_not_available() -> None:
    by_id = {cap.capabilityId: cap for cap in _capabilities(HostFacts.unavailable("connector_offline"))}
    view = by_id["connector.remote_desktop.view"]
    assert view.available is False
    assert view.unavailableReason == "connector_offline"


def test_missing_screen_permission_blocks_availability() -> None:
    facts = HostFacts.unavailable("screen_permission_denied")
    by_id = {cap.capabilityId: cap for cap in _capabilities(facts)}
    assert by_id["connector.remote_desktop.view"].available is False
    assert (
        by_id["connector.remote_desktop.view"].unavailableReason
        == "screen_permission_denied"
    )


def test_device_policy_disables_control_but_not_view() -> None:
    facts = HostFacts()
    settings = ConnectorDeviceSettings(
        remoteDesktopEnabled=True,
        remoteDesktopAllowControl=False,
    )
    by_id = {cap.capabilityId: cap for cap in _capabilities(facts, settings)}
    assert by_id["connector.remote_desktop.view"].allowed is True
    control = by_id["connector.remote_desktop.control"]
    assert control.allowed is False
    assert control.unavailableReason == "device_policy_disabled"

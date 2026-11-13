from __future__ import annotations

from agent_server.core.device_settings import ConnectorDeviceSettings
from agent_server.core.remote_desktop import HostFacts
from agent_server.services.remote_desktop_policy import (
    _error_reason,
    build_capabilities_sync,
)


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


class _RpcError(Exception):
    def __init__(self, code: str, message: str = "") -> None:
        super().__init__(message or code)
        self.code = code


def test_unknown_rpc_method_maps_to_a_connector_that_is_too_old() -> None:
    """An old Connector answers `remoteDesktop.*` with "method not found".

    Older Connector builds predate these RPCs; the device page must say the
    Connector is outdated instead of showing "offline" or "host not running".
    """

    for code in ("method_not_found", "unknown_method", "rd_unsupported_method"):
        assert _error_reason(_RpcError(code)) == "connector_outdated", code

    # Some transports only carry it in the message.
    assert (
        _error_reason(_RpcError("invalid_route", "Method not found: remoteDesktop.start"))
        == "connector_outdated"
    )


def test_error_reason_keeps_the_other_buckets() -> None:
    assert _error_reason(TimeoutError()) == "connector_offline"
    assert _error_reason(_RpcError("connector_offline")) == "connector_offline"
    assert _error_reason(_RpcError("rd_host_not_running")) == "host_not_running"
    assert _error_reason(_RpcError("rd_capture_failed")) == "host_not_running"

    class ConnectorOfflineError(Exception):
        pass

    assert _error_reason(ConnectorOfflineError()) == "connector_offline"


def test_capabilities_report_connector_outdated_for_an_old_connector() -> None:
    capabilities = _capabilities(
        HostFacts(
            supported=False,
            available=False,
            unavailableReason="connector_outdated",
        )
    )
    view = next(item for item in capabilities if item.capabilityId.endswith(".view"))
    assert view.supported is False
    assert view.available is False
    assert view.unavailableReason == "connector_outdated"

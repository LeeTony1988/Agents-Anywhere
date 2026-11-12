from __future__ import annotations

import asyncio
from pathlib import Path

import pytest

from connector.local.remote_desktop.host import (
    ScreenHostError,
    ScreenHostNotInstalled,
    ScreenHostProtocolError,
    ScreenHostSupervisor,
    ScreenHostTimeout,
    current_arch,
    current_platform,
    default_screen_host_path,
)

FIXTURE = Path(__file__).parent / "fixtures" / "fake_screen_host.py"


def _supervisor(
    tmp_path: Path,
    events: list[tuple[str, dict]],
    **overrides,
) -> ScreenHostSupervisor:
    async def on_event(event: str, params: dict) -> None:
        events.append((event, dict(params)))

    options = {
        "hello_timeout": 2.0,
        "ping_interval": 60.0,
        "ping_timeout": 0.3,
        "restart_backoff": (0.05, 0.05, 0.05),
        "release_all_timeout": 0.3,
        "stop_timeout": 0.3,
        "shutdown_grace": 0.4,
        "terminate_grace": 0.3,
    }
    options.update(overrides)
    return ScreenHostSupervisor(
        host_path=FIXTURE,
        event_handler=on_event,
        **options,
    )


async def _wait_until(predicate, timeout: float = 5.0) -> None:
    deadline = asyncio.get_running_loop().time() + timeout
    while asyncio.get_running_loop().time() < deadline:
        if predicate():
            return
        await asyncio.sleep(0.02)
    raise AssertionError("condition was not met before timeout")


def test_default_host_path_uses_the_packaged_layout(tmp_path) -> None:
    root = tmp_path / "screen-host"
    executable = (
        "aa-screen-host.exe"
        if current_platform() == "windows"
        else "aa-screen-host"
    )
    target = root / f"{current_platform()}-{current_arch()}" / executable
    target.parent.mkdir(parents=True)
    target.write_bytes(b"host")
    assert default_screen_host_path(roots=[root]) == target

    with pytest.raises(ScreenHostNotInstalled):
        default_screen_host_path(roots=[tmp_path / "missing"])


def test_handshake_info_and_state_stats_events(tmp_path) -> None:
    events: list[tuple[str, dict]] = []

    async def scenario() -> None:
        supervisor = _supervisor(tmp_path, events)
        try:
            result = await supervisor.start(
                "rds_handshake", mode="view", quality={"maxFps": 30}
            )
            assert result == {"accepted": True}
            assert supervisor.running
            assert supervisor.pid is not None
            info = await supervisor.info()
            assert info["platform"] in {"windows", "macos", "linux"}
            assert info["arch"] in {"x86_64", "aarch64", "unknown"}
            assert info["hostVersion"] == "0.0.0-fake"
            assert info["pid"] == supervisor.pid
            assert info["codecs"] == ["vp8"]
            assert info["activeSessions"] == 1
            assert info["displays"][0]["id"] == "1"
            await _wait_until(
                lambda: any(event == "stats" for event, _ in events)
            )
            assert any(
                event == "state" and params.get("state") == "running"
                for event, params in events
            )
        finally:
            await supervisor.shutdown()

    asyncio.run(scenario())


def test_start_is_idempotent_for_the_same_session(tmp_path) -> None:
    events: list[tuple[str, dict]] = []

    async def scenario() -> None:
        supervisor = _supervisor(tmp_path, events)
        try:
            first = await supervisor.start("rds_once")
            pid = supervisor.pid
            second = await supervisor.start("rds_once")
            assert first == {"accepted": True}
            assert second == {"accepted": True}
            assert supervisor.pid == pid
        finally:
            await supervisor.shutdown()

    asyncio.run(scenario())


def test_stop_is_idempotent(tmp_path) -> None:
    events: list[tuple[str, dict]] = []

    async def scenario() -> None:
        supervisor = _supervisor(tmp_path, events)
        try:
            await supervisor.start("rds_stop")
            assert await supervisor.stop("rds_stop") == {"stopped": True}
            assert supervisor.active_session_id is None
            assert await supervisor.stop("rds_stop") == {"stopped": True}
        finally:
            await supervisor.shutdown()

    asyncio.run(scenario())


def test_shutdown_releases_all_then_stops_and_reaps(tmp_path) -> None:
    events: list[tuple[str, dict]] = []
    log_path = tmp_path / "host-methods.log"

    async def scenario() -> None:
        supervisor = _supervisor(
            tmp_path,
            events,
            extra_env={"AA_FAKE_HOST_EVENT_LOG": str(log_path)},
        )
        await supervisor.start("rds_shutdown")
        process = supervisor.process
        assert process is not None
        await supervisor.shutdown()
        assert process.returncode is not None
        assert not supervisor.running
        methods = log_path.read_text(encoding="utf-8").splitlines()
        assert "host.releaseAll" in methods
        assert "host.stop" in methods
        assert methods.index("host.releaseAll") < methods.index("host.stop")

    asyncio.run(scenario())


def test_handshake_timeout_is_reported(tmp_path) -> None:
    events: list[tuple[str, dict]] = []

    async def scenario() -> None:
        supervisor = _supervisor(
            tmp_path,
            events,
            hello_timeout=0.3,
            extra_env={"AA_FAKE_HOST_SILENT": "1"},
        )
        try:
            with pytest.raises(ScreenHostTimeout):
                await supervisor.start("rds_silent")
            assert not supervisor.running
        finally:
            await supervisor.shutdown()

    asyncio.run(scenario())


def test_protocol_version_mismatch_is_rejected(tmp_path) -> None:
    events: list[tuple[str, dict]] = []

    async def scenario() -> None:
        supervisor = _supervisor(
            tmp_path,
            events,
            extra_env={"AA_FAKE_HOST_PROTOCOL_VERSION": "2"},
        )
        try:
            with pytest.raises(ScreenHostProtocolError):
                await supervisor.start("rds_version")
            assert not supervisor.running
        finally:
            await supervisor.shutdown()

    asyncio.run(scenario())


def test_oversized_frame_is_a_protocol_error(tmp_path) -> None:
    events: list[tuple[str, dict]] = []

    async def scenario() -> None:
        supervisor = _supervisor(
            tmp_path,
            events,
            extra_env={"AA_FAKE_HOST_BAD_FRAME": "1"},
        )
        try:
            with pytest.raises(ScreenHostProtocolError):
                await supervisor.start("rds_bad_frame")
            assert not supervisor.running
        finally:
            await supervisor.shutdown()

    asyncio.run(scenario())


def test_missing_host_binary_is_not_installed(tmp_path) -> None:
    async def scenario() -> None:
        supervisor = ScreenHostSupervisor(
            host_path=tmp_path / "missing" / "aa-screen-host.exe",
            event_handler=None,
        )
        with pytest.raises(ScreenHostNotInstalled):
            await supervisor.start("rds_missing")
        await supervisor.shutdown()

    asyncio.run(scenario())


def test_crash_restarts_and_resumes_the_session(tmp_path) -> None:
    events: list[tuple[str, dict]] = []

    async def scenario() -> None:
        supervisor = _supervisor(tmp_path, events)
        try:
            await supervisor.start("rds_crash")
            first_pid = supervisor.pid
            assert first_pid is not None
            with pytest.raises(ScreenHostError):
                await supervisor.input(
                    "rds_crash", [{"v": 1, "t": "crash"}]
                )
            await _wait_until(
                lambda: supervisor.running
                and supervisor.pid is not None
                and supervisor.pid != first_pid,
                timeout=6.0,
            )
            assert supervisor.active_session_id == "rds_crash"
            info = await supervisor.info()
            assert info["activeSessions"] == 1
        finally:
            await supervisor.shutdown()

    asyncio.run(scenario())


def test_cancelled_start_leaves_no_child(tmp_path) -> None:
    events: list[tuple[str, dict]] = []

    async def scenario() -> None:
        supervisor = _supervisor(
            tmp_path,
            events,
            extra_env={"AA_FAKE_HOST_START_DELAY_MS": "2000"},
        )
        task = asyncio.create_task(supervisor.start("rds_cancel"))
        await _wait_until(
            lambda: supervisor.process is not None and supervisor.hello is not None
        )
        process = supervisor.process
        assert process is not None
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        await _wait_until(lambda: process.returncode is not None, timeout=6.0)
        await supervisor.shutdown()

    asyncio.run(scenario())

"""Supervise ``aa-screen-host`` over the HostControl NDJSON stdio protocol.

The host is a single-writer child process: stdout carries protocol frames only,
stderr carries logs, and one frame may not exceed 4 MiB
(``contracts/remote-desktop/1.0/schemas/host-control-message.schema.json``).
This module owns process location, spawning, request/response correlation,
timeouts, keepalive, crash restart and shutdown.
"""

from __future__ import annotations

import asyncio
import contextlib
import inspect
import json
import os
import platform as platform_module
import signal
import sys
import time
from collections import deque
from collections.abc import Awaitable, Callable, Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from connector.logging import logger

PROTOCOL_VERSION = 1
MAX_FRAME_BYTES = 4 * 1024 * 1024
DEFAULT_REQUEST_TIMEOUT_SECONDS = 30.0
HELLO_TIMEOUT_SECONDS = 5.0
PING_INTERVAL_SECONDS = 5.0
PING_TIMEOUT_SECONDS = 2.0
MAX_PING_FAILURES = 3
RESTART_BACKOFF_SECONDS = (1.0, 2.0, 4.0, 8.0)
MAX_RESTARTS_PER_WINDOW = 3
RESTART_WINDOW_SECONDS = 60.0
HEALTHY_RESET_SECONDS = 60.0
SHUTDOWN_RELEASE_TIMEOUT_SECONDS = 2.0
SHUTDOWN_STOP_TIMEOUT_SECONDS = 2.0
SHUTDOWN_GRACE_SECONDS = 3.0
TERMINATE_GRACE_SECONDS = 2.0

EVENT_KINDS = frozenset({"state", "stats", "displays", "consent", "clipboard", "error"})

EventCallback = Callable[[str, dict[str, Any]], Awaitable[None] | None]


class ScreenHostError(RuntimeError):
    """Base class for every host-side failure with a stable internal code."""

    code = "rd_host_error"
    retryable = False

    def __init__(self, message: str | None = None, *, code: str | None = None) -> None:
        self.code = code or type(self).code
        super().__init__(message or self.code)


class ScreenHostNotInstalled(ScreenHostError):
    code = "rd_host_not_installed"


class ScreenHostNotRunning(ScreenHostError):
    code = "rd_host_not_running"
    retryable = True


class ScreenHostTimeout(ScreenHostError):
    code = "rd_host_timeout"
    retryable = True


class ScreenHostProtocolError(ScreenHostError):
    code = "rd_host_protocol_error"


class ScreenHostBusy(ScreenHostError):
    code = "rd_busy"
    retryable = True


class ScreenHostSessionNotFound(ScreenHostError):
    code = "rd_session_not_found"


class ScreenHostCrashLoop(ScreenHostError):
    code = "rd_host_crash_loop"
    retryable = False


class ScreenHostRemoteError(ScreenHostError):
    code = "rd_host_error"

    def __init__(
        self,
        message: str,
        *,
        code: str | None = None,
        retryable: bool | None = None,
    ) -> None:
        super().__init__(message, code=code)
        if retryable is not None:
            self.retryable = retryable


def current_platform() -> str:
    if sys.platform == "win32":
        return "windows"
    if sys.platform == "darwin":
        return "macos"
    return "linux"


def current_arch() -> str:
    machine = platform_module.machine().strip().lower()
    if machine in {"x86_64", "amd64", "x64"}:
        return "x86_64"
    if machine in {"aarch64", "arm64"}:
        return "aarch64"
    return "unknown"


def default_screen_host_path(
    *,
    platform: str | None = None,
    arch: str | None = None,
    roots: list[Path] | None = None,
) -> Path:
    """Resolve ``screen-host/<platform>-<arch>/aa-screen-host[.exe]``.

    Only packaged resource roots are considered. The developer checkout root is
    included last so a locally built bundle can be exercised without copying it.
    """

    resolved_platform = platform or current_platform()
    resolved_arch = arch or current_arch()
    executable = (
        "aa-screen-host.exe" if resolved_platform == "windows" else "aa-screen-host"
    )
    candidates = roots
    dev_candidates: list[Path] = []
    if isinstance(candidates, (str, Path)):
        candidates = [Path(candidates)]
    if candidates is None:
        executable_dir = Path(sys.executable).resolve().parent
        candidates = [
            executable_dir / "screen-host",
            executable_dir.parent / "screen-host",
            Path(__file__).resolve().parents[4] / "screen-host",
        ]
        dev_root = Path(__file__).resolve().parents[4] / "screen-host"
        dev_candidates = [
            dev_root / "target" / "debug",
            dev_root / "target" / "release",
        ]
    searched: list[str] = []
    for root in [*candidates]:
        candidate = Path(root) / f"{resolved_platform}-{resolved_arch}" / executable
        searched.append(str(candidate))
        if candidate.is_file():
            return candidate
    # A developer checkout can run the locally built binary directly. Packaged
    # installs never reach this branch because their resources are found above.
    for directory in dev_candidates:
        candidate = directory / executable
        searched.append(str(candidate))
        if candidate.is_file():
            return candidate
    raise ScreenHostNotInstalled(
        "screen host is not installed for "
        f"{resolved_platform}-{resolved_arch}; searched: {', '.join(searched)}"
    )


@dataclass(slots=True)
class _ProcessState:
    process: asyncio.subprocess.Process
    generation: int
    reader_task: asyncio.Task[None]
    stderr_task: asyncio.Task[None]
    wait_task: asyncio.Task[None]
    ping_task: asyncio.Task[None]


class ScreenHostSupervisor:
    """Own one long-lived ``aa-screen-host`` process and its protocol channel."""

    def __init__(
        self,
        host_path: str | Path | None = None,
        *,
        event_handler: EventCallback | None = None,
        on_event: EventCallback | None = None,
        platform: str | None = None,
        arch: str | None = None,
        extra_env: Mapping[str, str] | None = None,
        request_timeout: float = DEFAULT_REQUEST_TIMEOUT_SECONDS,
        hello_timeout: float = HELLO_TIMEOUT_SECONDS,
        ping_interval: float = PING_INTERVAL_SECONDS,
        ping_timeout: float = PING_TIMEOUT_SECONDS,
        max_ping_failures: int = MAX_PING_FAILURES,
        restart_backoff: tuple[float, ...] = RESTART_BACKOFF_SECONDS,
        max_restarts: int = MAX_RESTARTS_PER_WINDOW,
        restart_window_seconds: float = RESTART_WINDOW_SECONDS,
        healthy_reset_seconds: float = HEALTHY_RESET_SECONDS,
        release_all_timeout: float = SHUTDOWN_RELEASE_TIMEOUT_SECONDS,
        stop_timeout: float = SHUTDOWN_STOP_TIMEOUT_SECONDS,
        shutdown_grace: float = SHUTDOWN_GRACE_SECONDS,
        terminate_grace: float = TERMINATE_GRACE_SECONDS,
    ) -> None:
        self._explicit_host_path = Path(host_path).expanduser() if host_path else None
        self._platform = platform or current_platform()
        self._arch = arch or current_arch()
        self._event_handler = event_handler or on_event
        self._extra_env = dict(extra_env or {})
        self._request_timeout = request_timeout
        self._hello_timeout = hello_timeout
        self._ping_interval = ping_interval
        self._ping_timeout = ping_timeout
        self._max_ping_failures = max_ping_failures
        self._restart_backoff = restart_backoff or (0.0,)
        self._max_restarts = max_restarts
        self._restart_window_seconds = restart_window_seconds
        self._healthy_reset_seconds = healthy_reset_seconds
        self._release_all_timeout = release_all_timeout
        self._stop_timeout = stop_timeout
        self._shutdown_grace = shutdown_grace
        self._terminate_grace = terminate_grace

        self._process_state: _ProcessState | None = None
        self._job: Any = None
        self._generation = 0
        self._next_request_id = 1
        self._pending: dict[int, asyncio.Future[dict[str, Any]]] = {}
        self._write_lock = asyncio.Lock()
        self._lifecycle_lock = asyncio.Lock()
        self._restart_lock = asyncio.Lock()
        self._stop_lock = asyncio.Lock()
        self._restart_task: asyncio.Task[None] | None = None
        self._cleanup_task: asyncio.Task[None] | None = None
        self._event_task: asyncio.Task[None] | None = None
        self._event_queue: asyncio.Queue[tuple[str, dict[str, Any]]] = asyncio.Queue()

        self._hello: dict[str, Any] | None = None
        self._hello_error: ScreenHostError | None = None
        self._hello_event = asyncio.Event()
        self._state = "stopped"
        self._ever_ready = False
        self._shutting_down = False
        self._restart_suppressed = False
        self._intentional_exit = False
        self._crash_loop = False
        self._last_error: ScreenHostError | None = None
        self._stderr_lines: deque[str] = deque(maxlen=200)

        self._active_session_id: str | None = None
        self._active_start_params: dict[str, Any] | None = None
        self._controller_identity: str | None = None

        self._restart_times: deque[float] = deque()
        self._restart_attempt = 0
        self._healthy_since: float | None = None

    # ------------------------------------------------------------------ public state
    @property
    def process(self) -> asyncio.subprocess.Process | None:
        return self._process_state.process if self._process_state else None

    @property
    def running(self) -> bool:
        process = self.process
        return bool(
            self._hello is not None
            and process is not None
            and process.returncode is None
        )

    @property
    def installed(self) -> bool:
        try:
            self._resolve_host_path()
        except ScreenHostNotInstalled:
            return False
        return True

    @property
    def host_path(self) -> Path | None:
        try:
            return self._resolve_host_path()
        except ScreenHostNotInstalled:
            return None

    @property
    def available(self) -> bool:
        return self.installed

    @property
    def platform(self) -> str:
        if self._hello:
            value = self._hello.get("platform")
            if isinstance(value, str):
                return value
        return self._platform

    @property
    def arch(self) -> str:
        if self._hello:
            value = self._hello.get("arch")
            if isinstance(value, str):
                return value
        return self._arch

    @property
    def pid(self) -> int | None:
        if not self._hello:
            return None
        value = self._hello.get("pid")
        return value if isinstance(value, int) and not isinstance(value, bool) else None

    @property
    def hello(self) -> dict[str, Any] | None:
        return dict(self._hello) if self._hello else None

    @property
    def active_session_id(self) -> str | None:
        return self._active_session_id

    @property
    def controller_identity(self) -> str | None:
        return self._controller_identity

    @property
    def last_error(self) -> ScreenHostError | None:
        return self._last_error

    @property
    def crash_looped(self) -> bool:
        return self._crash_loop

    @property
    def stderr_tail(self) -> list[str]:
        return list(self._stderr_lines)

    def set_event_handler(self, handler: EventCallback | None) -> None:
        self._event_handler = handler

    # ------------------------------------------------------------------ lifecycle
    async def start(
        self,
        session_id: str,
        *,
        display_id: str | None = None,
        mode: str = "view",
        quality: Mapping[str, Any] | None = None,
        media: Mapping[str, Any] | None = None,
        consent_required: bool = False,
        deadline_ms: int | None = None,
    ) -> dict[str, Any]:
        if not session_id:
            raise ScreenHostSessionNotFound("sessionId is required")
        if self._shutting_down:
            raise ScreenHostNotRunning("screen host is shutting down")
        try:
            return await self._start_locked(
                session_id,
                display_id=display_id,
                mode=mode,
                quality=quality,
                media=media,
                consent_required=consent_required,
                deadline_ms=deadline_ms,
            )
        except asyncio.CancelledError:
            self._restart_suppressed = True
            self._schedule_cleanup()
            raise

    async def _start_locked(
        self,
        session_id: str,
        *,
        display_id: str | None,
        mode: str,
        quality: Mapping[str, Any] | None,
        media: Mapping[str, Any] | None,
        consent_required: bool,
        deadline_ms: int | None,
    ) -> dict[str, Any]:
        async with self._lifecycle_lock:
            if self._active_session_id == session_id and self.running:
                return {"accepted": True}
            if (
                self._active_session_id is not None
                and self._active_session_id != session_id
            ):
                raise ScreenHostBusy(
                    f"screen host is already running session {self._active_session_id}"
                )
            self._intentional_exit = False
            self._crash_loop = False
            self._restart_times.clear()
            self._restart_attempt = 0
            if not self.running:
                await self._ensure_process()
            if not display_id:
                display_id = await self._default_display_id()
            params: dict[str, Any] = {
                "sessionId": session_id,
                "mode": mode,
                "quality": dict(quality or {}),
                "requireConsent": bool(consent_required),
            }
            if display_id:
                params["displayId"] = display_id
            if media:
                params["media"] = dict(media)
            if deadline_ms is not None:
                params["deadlineMs"] = deadline_ms
            timeout = self._request_timeout
            if deadline_ms is not None:
                timeout = min(timeout, max(0.001, deadline_ms / 1000))
            try:
                result = await self._request(
                    "host.start",
                    params,
                    timeout=timeout,
                    deadline_ms=deadline_ms,
                )
            except ScreenHostTimeout:
                await self._abort_start()
                raise
            self._active_session_id = session_id
            self._active_start_params = params
            return result

    async def _default_display_id(self) -> str | None:
        """Pick a real display instead of the host's placeholder ``"0"``."""

        try:
            info = await self.info()
        except ScreenHostError:
            return None
        displays = info.get("displays")
        if not isinstance(displays, list):
            return None
        for display in displays:
            if isinstance(display, dict) and isinstance(display.get("id"), str):
                return display["id"]
        return None

    async def stop(
        self,
        session_id: str | None = None,
        *,
        reason: str | None = None,
    ) -> dict[str, Any]:
        async with self._lifecycle_lock:
            if self._active_session_id is None:
                return {"stopped": True}
            if session_id is not None and session_id != self._active_session_id:
                return {"stopped": True}
            state = self._process_state
            if state is None or state.process.returncode is not None:
                self._clear_active_session()
                return {"stopped": True}
            self._intentional_exit = True
            params = {"reason": reason} if reason else {}
            try:
                await self._request("host.stop", params, timeout=5.0)
            except ScreenHostNotRunning:
                pass
            except ScreenHostTimeout:
                await self._stop_process(graceful=False)
            finally:
                if self._process_state and self._process_state.process.returncode is None:
                    self._intentional_exit = False
            self._clear_active_session()
            return {"stopped": True}

    async def update_quality(
        self,
        session_id: str,
        quality: Mapping[str, Any],
        *,
        deadline_ms: int | None = None,
    ) -> dict[str, Any]:
        self._require_session(session_id)
        return await self._request(
            "host.updateQuality",
            dict(quality),
            timeout=self._request_timeout,
            deadline_ms=deadline_ms,
        )

    async def control_grant(
        self,
        session_id: str,
        controller_identity: str | None,
        *,
        lease_expires_at: str | None = None,
        epoch: int | None = None,
    ) -> dict[str, Any]:
        self._require_session(session_id)
        identity = controller_identity or ""
        params: dict[str, Any] = {"identity": identity}
        if lease_expires_at is not None:
            params["leaseExpiresAt"] = lease_expires_at
        if epoch is not None:
            params["epoch"] = epoch
        result = await self._request("host.controlGrant", params, timeout=5.0)
        self._controller_identity = controller_identity or None
        return result

    async def request_keyframe(self, session_id: str | None = None) -> dict[str, Any]:
        if session_id is not None:
            self._require_session(session_id)
        return await self._request("host.requestKeyframe", {}, timeout=5.0)

    async def release_all(self, session_id: str | None = None) -> dict[str, Any]:
        # Releasing stuck keys/buttons is safe even after a session ended.
        if not self.running:
            return {"releasedKeys": 0, "releasedButtons": 0}
        return await self._request("host.releaseAll", {}, timeout=5.0)

    async def screenshot(
        self,
        session_id: str | None = None,
        *,
        display_id: str | None = None,
        max_width: int | None = None,
        fmt: str | None = None,
        quality: int | None = None,
    ) -> dict[str, Any]:
        if session_id is not None:
            self._require_session(session_id)
        params: dict[str, Any] = {}
        if display_id is not None:
            params["displayId"] = display_id
        if max_width is not None:
            params["maxWidth"] = max_width
        if fmt is not None:
            params["format"] = fmt
        if quality is not None:
            params["quality"] = quality
        return await self._request("host.screenshot", params, timeout=10.0)

    async def input(
        self,
        session_id: str,
        events: list[Mapping[str, Any]],
        *,
        identity: str | None = None,
    ) -> dict[str, Any]:
        self._require_session(session_id)
        resolved_identity = identity or self._controller_identity
        params: dict[str, Any] = {"events": [dict(event) for event in events]}
        if resolved_identity:
            params["identity"] = resolved_identity
        return await self._request("host.input", params, timeout=10.0)

    async def info(self) -> dict[str, Any]:
        if not self.running:
            raise ScreenHostNotRunning("screen host is not running")
        result = await self._request("host.info", {}, timeout=5.0)
        return self._normalize_info(result)

    async def ensure_started(self) -> None:
        if not self.running:
            await self._ensure_process()

    async def close(self) -> None:
        await self.shutdown()

    async def shutdown(self) -> None:
        self._shutting_down = True
        self._restart_suppressed = True
        task = self._cleanup_task
        if task is None or task.done():
            task = self._schedule_cleanup()
        await asyncio.shield(task)

    def _schedule_cleanup(self) -> asyncio.Task[None]:
        task = self._cleanup_task
        if task is None or task.done():
            task = asyncio.create_task(self._shutdown_impl())
            self._cleanup_task = task
        return task

    async def _shutdown_impl(self) -> None:
        restart_task = self._restart_task
        if restart_task is not None and restart_task is not asyncio.current_task():
            restart_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await restart_task
        state = self._process_state
        if (
            state is not None
            and state.process.returncode is None
            and self._hello is not None
        ):
            with contextlib.suppress(Exception):
                await self._request(
                    "host.releaseAll", {}, timeout=self._release_all_timeout
                )
            with contextlib.suppress(Exception):
                await self._request(
                    "host.stop", {"reason": "connector_shutdown"},
                    timeout=self._stop_timeout,
                )
            with contextlib.suppress(Exception):
                await self._write_frame(
                    {"v": PROTOCOL_VERSION, "type": "shutdown", "reason": "connector_shutdown"}
                )
        await self._stop_process(graceful=True)
        event_task = self._event_task
        if event_task is not None and event_task is not asyncio.current_task():
            event_task.cancel()
            await asyncio.gather(event_task, return_exceptions=True)
        self._event_task = None
        self._clear_active_session()
        self._hello = None
        self._ever_ready = False
        self._state = "stopped"
        if not self._shutting_down:
            self._restart_suppressed = False

    async def _abort_start(self) -> None:
        with contextlib.suppress(Exception):
            await self._request("host.releaseAll", {}, timeout=1.0)
        await self._stop_process(graceful=False)
        self._clear_active_session()

    def _clear_active_session(self) -> None:
        self._active_session_id = None
        self._active_start_params = None
        self._controller_identity = None

    def _require_session(self, session_id: str) -> None:
        if self._active_session_id is None:
            raise ScreenHostSessionNotFound(
                f"remote desktop session is not active: {session_id}"
            )
        if session_id != self._active_session_id:
            raise ScreenHostSessionNotFound(
                f"remote desktop session is not active: {session_id}"
            )

    # ------------------------------------------------------------------ process spawn
    def _resolve_host_path(self) -> Path:
        if self._explicit_host_path is not None:
            path = self._explicit_host_path
            if path.is_file():
                return path
            raise ScreenHostNotInstalled(f"screen host is not installed: {path}")
        return default_screen_host_path(platform=self._platform, arch=self._arch)

    def _command(self, host_path: Path) -> list[str]:
        if host_path.suffix.lower() == ".py":
            command = [sys.executable, str(host_path)]
        else:
            command = [str(host_path)]
        if os.name != "nt":
            guardian = (
                Path(__file__).resolve().parents[2]
                / "runtimes"
                / "acp"
                / "infrastructure"
                / "guardian.py"
            )
            if guardian.is_file():
                command = [sys.executable, str(guardian), str(os.getpid()), *command]
        return command

    def _child_environment(self) -> dict[str, str]:
        allowed = {
            "APPDATA",
            "COMSPEC",
            "DBUS_SESSION_BUS_ADDRESS",
            "DISPLAY",
            "HOMEDRIVE",
            "HOMEPATH",
            "HOME",
            "LANG",
            "LC_ALL",
            "LOCALAPPDATA",
            "NUMBER_OF_PROCESSORS",
            "PATH",
            "PATHEXT",
            "PROCESSOR_ARCHITECTURE",
            "PROGRAMDATA",
            "SYSTEMDRIVE",
            "SYSTEMROOT",
            "TEMP",
            "TMP",
            "TMPDIR",
            "USER",
            "USERPROFILE",
            "WAYLAND_DISPLAY",
            "WINDIR",
            "XDG_RUNTIME_DIR",
        }
        environment = {
            key: value
            for key, value in os.environ.items()
            if key.upper() in allowed or key.upper().startswith("LC_")
        }
        environment.update(self._extra_env)
        environment["PYTHONIOENCODING"] = "utf-8"
        environment["PYTHONUTF8"] = "1"
        environment["PYTHONUNBUFFERED"] = "1"
        return environment

    async def _ensure_process(self) -> None:
        async with self._restart_lock:
            if self.running:
                return
            await self._start_process()

    async def _start_process(self) -> None:
        host_path = self._resolve_host_path()
        self._hello = None
        self._hello_error = None
        self._hello_event = asyncio.Event()
        self._state = "starting"
        self._start_event_worker()
        command = self._command(host_path)
        options: dict[str, Any] = {}
        if os.name != "nt":
            options["start_new_session"] = True
        try:
            process = await asyncio.create_subprocess_exec(
                *command,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                env=self._child_environment(),
                limit=MAX_FRAME_BYTES + 1,
                **options,
            )
        except OSError as exc:
            self._state = "stopped"
            raise ScreenHostNotInstalled(
                f"screen host could not be started: {host_path}"
            ) from exc
        self._assign_windows_job(process.pid)
        generation = self._generation + 1
        self._generation = generation
        reader_task = asyncio.create_task(self._read_loop(process, generation))
        stderr_task = asyncio.create_task(self._drain_stderr(process, generation))
        wait_task = asyncio.create_task(self._watch_process(process, generation))
        ping_task = asyncio.create_task(self._ping_loop(generation))
        self._process_state = _ProcessState(
            process=process,
            generation=generation,
            reader_task=reader_task,
            stderr_task=stderr_task,
            wait_task=wait_task,
            ping_task=ping_task,
        )
        try:
            await self._wait_for_hello(generation)
        except BaseException:
            await self._stop_process(graceful=False)
            raise
        self._ever_ready = True
        self._crash_loop = False
        self._last_error = None
        self._state = "running"
        self._healthy_since = time.monotonic()

    async def _wait_for_hello(self, generation: int) -> None:
        try:
            await asyncio.wait_for(self._hello_event.wait(), timeout=self._hello_timeout)
        except TimeoutError as exc:
            error = ScreenHostTimeout(
                f"screen host did not send hello within {self._hello_timeout:g}s"
            )
            self._hello_error = error
            raise error from exc
        if generation != self._generation:
            raise ScreenHostNotRunning("screen host process was replaced")
        if self._hello_error is not None:
            raise self._hello_error
        if self._hello is None:
            raise ScreenHostProtocolError("screen host closed before hello")

    def _assign_windows_job(self, pid: int) -> None:
        if os.name != "nt":
            return
        job: Any = None
        try:
            from connector.runtimes.acp.infrastructure.windows import WindowsJob

            job = WindowsJob()
            job.assign_and_resume(pid)
            self._job = job
        except Exception as exc:  # noqa: BLE001 - job ownership is best effort
            if job is not None:
                with contextlib.suppress(Exception):
                    job.close()
            logger.warning("screen host job object assignment failed: {}", exc)

    async def _stop_process(self, *, graceful: bool) -> None:
        async with self._stop_lock:
            state = self._process_state
            if state is None:
                return
            self._generation += 1
            self._hello = None
            self._hello_event.set()
            self._fail_pending(ScreenHostNotRunning("screen host stopped"))
            process = state.process
            if graceful and process.returncode is None:
                with contextlib.suppress(Exception):
                    if process.stdin is not None:
                        process.stdin.close()
                        await asyncio.wait_for(
                            process.stdin.wait_closed(), timeout=1.0
                        )
                try:
                    await asyncio.wait_for(
                        process.wait(), timeout=self._shutdown_grace
                    )
                except TimeoutError:
                    self._terminate(process)
                if process.returncode is None:
                    try:
                        await asyncio.wait_for(
                            process.wait(), timeout=self._terminate_grace
                        )
                    except TimeoutError:
                        self._kill(process)
                        await process.wait()
            elif process.returncode is None:
                self._terminate(process)
                try:
                    await asyncio.wait_for(
                        process.wait(), timeout=self._terminate_grace
                    )
                except TimeoutError:
                    self._kill(process)
                    await process.wait()
            for stream in (process.stdout, process.stderr):
                transport = getattr(stream, "_transport", None)
                if transport is not None:
                    with contextlib.suppress(Exception):
                        transport.close()
            current = asyncio.current_task()
            tasks = [
                state.ping_task,
                state.reader_task,
                state.stderr_task,
                state.wait_task,
            ]
            for task in tasks:
                if task is not current and not task.done():
                    task.cancel()
            await asyncio.gather(
                *(task for task in tasks if task is not current),
                return_exceptions=True,
            )
            # Let the proactor pipe transports observe EOF/close before the loop
            # goes away; otherwise Python 3.13 emits ResourceWarnings at GC time.
            await asyncio.sleep(0)
            if self._job is not None:
                with contextlib.suppress(Exception):
                    self._job.close()
                self._job = None
            self._process_state = None
            self._state = "stopped"

    def _terminate(self, process: asyncio.subprocess.Process) -> None:
        with contextlib.suppress(ProcessLookupError, OSError):
            if os.name == "nt":
                process.terminate()
            else:
                os.killpg(process.pid, signal.SIGTERM)

    def _kill(self, process: asyncio.subprocess.Process) -> None:
        with contextlib.suppress(ProcessLookupError, OSError):
            if os.name == "nt":
                process.kill()
            else:
                os.killpg(process.pid, signal.SIGKILL)

    # ------------------------------------------------------------------ protocol IO
    async def _request(
        self,
        method: str,
        params: Mapping[str, Any],
        *,
        timeout: float | None = None,
        deadline_ms: int | None = None,
    ) -> dict[str, Any]:
        process = self.process
        if process is None or process.returncode is not None or self._hello is None:
            raise self._not_running_error()
        request_id = self._next_request_id
        self._next_request_id += 1
        future: asyncio.Future[dict[str, Any]] = (
            asyncio.get_running_loop().create_future()
        )
        self._pending[request_id] = future
        frame: dict[str, Any] = {
            "v": PROTOCOL_VERSION,
            "type": "request",
            "id": request_id,
            "method": method,
            "params": dict(params),
        }
        if deadline_ms is not None:
            frame["deadlineMs"] = deadline_ms
        try:
            await self._write_frame(frame)
        except BaseException as exc:
            self._pending.pop(request_id, None)
            future.cancel()
            if isinstance(exc, (BrokenPipeError, ConnectionResetError)):
                self._process_lost(ScreenHostNotRunning("screen host stdin closed"))
            raise
        try:
            return await asyncio.wait_for(
                asyncio.shield(future),
                timeout=self._request_timeout if timeout is None else timeout,
            )
        except TimeoutError as exc:
            self._pending.pop(request_id, None)
            future.cancel()
            raise ScreenHostTimeout(
                f"screen host request {method} timed out"
            ) from exc
        except asyncio.CancelledError:
            self._pending.pop(request_id, None)
            future.cancel()
            raise

    def _not_running_error(self) -> ScreenHostError:
        last = self._last_error
        if isinstance(last, ScreenHostProtocolError):
            return last
        return ScreenHostNotRunning("screen host is not running")

    async def _write_frame(self, frame: Mapping[str, Any]) -> None:
        encoded = json.dumps(
            dict(frame), ensure_ascii=False, separators=(",", ":")
        ).encode("utf-8")
        if len(encoded) > MAX_FRAME_BYTES:
            raise ScreenHostProtocolError(
                f"outbound host frame is {len(encoded)} bytes; limit is {MAX_FRAME_BYTES}"
            )
        state = self._process_state
        if state is None or state.process.stdin is None:
            raise ScreenHostNotRunning("screen host is not running")
        async with self._write_lock:
            state.process.stdin.write(encoded + b"\n")
            await state.process.stdin.drain()

    async def _read_loop(
        self, process: asyncio.subprocess.Process, generation: int
    ) -> None:
        stream = process.stdout
        if stream is None:
            return
        try:
            while True:
                try:
                    line = await stream.readline()
                except ValueError as exc:
                    raise ScreenHostProtocolError(
                        f"screen host frame exceeds {MAX_FRAME_BYTES} bytes"
                    ) from exc
                if not line:
                    return
                if len(line) > MAX_FRAME_BYTES + 1:
                    raise ScreenHostProtocolError(
                        f"screen host frame exceeds {MAX_FRAME_BYTES} bytes"
                    )
                payload = line.rstrip(b"\r\n")
                if not payload:
                    raise ScreenHostProtocolError("screen host wrote an empty frame")
                if len(payload) > MAX_FRAME_BYTES:
                    raise ScreenHostProtocolError(
                        f"screen host frame is {len(payload)} bytes; "
                        f"limit is {MAX_FRAME_BYTES}"
                    )
                try:
                    decoded = payload.decode("utf-8")
                except UnicodeDecodeError as exc:
                    raise ScreenHostProtocolError(
                        "screen host frame is not valid UTF-8"
                    ) from exc
                try:
                    frame = json.loads(decoded)
                except json.JSONDecodeError as exc:
                    raise ScreenHostProtocolError(
                        f"screen host wrote invalid JSON: {exc.msg}"
                    ) from exc
                self._handle_frame(frame, generation)
        except asyncio.CancelledError:
            raise
        except ScreenHostProtocolError as exc:
            self._protocol_failure(exc, generation)
        except Exception as exc:  # noqa: BLE001 - any read failure is fatal
            self._protocol_failure(
                ScreenHostProtocolError(f"screen host read failed: {exc}"),
                generation,
            )

    async def _drain_stderr(
        self, process: asyncio.subprocess.Process, generation: int
    ) -> None:
        stream = process.stderr
        if stream is None:
            return
        try:
            while True:
                chunk = await stream.read(65536)
                if not chunk:
                    return
                if generation != self._generation:
                    return
                text = chunk.decode("utf-8", "replace")
                for line in text.splitlines():
                    if not line:
                        continue
                    self._stderr_lines.append(line)
                    logger.debug("screen-host: {}", line[:1000])
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001 - stderr is diagnostics only
            return

    def _handle_frame(self, frame: Any, generation: int) -> None:
        if generation != self._generation:
            return
        if not isinstance(frame, dict):
            raise ScreenHostProtocolError("screen host frame must be an object")
        if frame.get("v") != PROTOCOL_VERSION:
            raise ScreenHostProtocolError("screen host frame has an invalid version")
        frame_type = frame.get("type")
        if self._hello is None:
            if frame_type != "hello":
                raise ScreenHostProtocolError("screen host did not start with hello")
            self._accept_hello(frame)
            return
        if frame_type == "response":
            self._accept_response(frame)
        elif frame_type == "event":
            self._accept_event(frame)
        elif frame_type == "error":
            self._accept_host_error(frame)
        elif frame_type == "shutdown":
            self._intentional_exit = True
            session_id = self._active_session_id
            if session_id:
                self._emit_event_nowait(
                    "state", {"sessionId": session_id, "state": "stopped"}
                )
            self._clear_active_session()
        elif frame_type == "hello":
            raise ScreenHostProtocolError("screen host sent a duplicate hello")
        else:
            raise ScreenHostProtocolError(
                f"screen host sent an unknown frame type: {frame_type!r}"
            )

    def _accept_hello(self, frame: Mapping[str, Any]) -> None:
        protocol_version = frame.get("protocolVersion")
        if protocol_version != PROTOCOL_VERSION:
            error = ScreenHostProtocolError(
                "unsupported screen host protocol version: "
                f"{protocol_version!r} (expected {PROTOCOL_VERSION})"
            )
            self._hello_error = error
            self._hello_event.set()
            raise error
        host_version = frame.get("hostVersion")
        platform = frame.get("platform")
        raw_arch = frame.get("arch")
        arch = _host_arch_alias(raw_arch)
        pid = frame.get("pid")
        capabilities = frame.get("capabilities")
        if not isinstance(host_version, str) or not host_version:
            raise ScreenHostProtocolError("screen host hello has no hostVersion")
        if platform not in {"windows", "macos", "linux", "unknown"}:
            raise ScreenHostProtocolError("screen host hello has an invalid platform")
        if arch is None:
            raise ScreenHostProtocolError("screen host hello has an invalid arch")
        if isinstance(pid, bool) or not isinstance(pid, int) or pid < 1:
            raise ScreenHostProtocolError("screen host hello has an invalid pid")
        if not isinstance(capabilities, dict):
            raise ScreenHostProtocolError("screen host hello has no capabilities")
        self._hello = {
            "protocolVersion": PROTOCOL_VERSION,
            "hostVersion": host_version,
            "platform": platform,
            "arch": arch,
            "pid": pid,
            "capabilities": dict(capabilities),
        }
        self._hello_error = None
        self._hello_event.set()
        logger.info(
            "screen host ready version={} platform={} arch={} pid={}",
            host_version,
            platform,
            arch,
            pid,
        )

    def _accept_response(self, frame: Mapping[str, Any]) -> None:
        request_id = frame.get("id")
        if isinstance(request_id, bool) or not isinstance(request_id, int):
            raise ScreenHostProtocolError("screen host response has an invalid id")
        future = self._pending.pop(request_id, None)
        if future is None or future.done():
            logger.debug("ignoring late screen host response id={}", request_id)
            return
        if frame.get("ok") is True:
            result = frame.get("result", {})
            if not isinstance(result, dict):
                raise ScreenHostProtocolError(
                    "screen host response result must be an object"
                )
            future.set_result(dict(result))
            return
        error = frame.get("error")
        error_map = error if isinstance(error, dict) else {}
        code = error_map.get("code")
        message = error_map.get("message")
        future.set_exception(
            ScreenHostRemoteError(
                str(message or code or "screen host request failed"),
                code=str(code) if isinstance(code, str) and code else None,
            )
        )

    def _accept_event(self, frame: Mapping[str, Any]) -> None:
        event = frame.get("event")
        params = frame.get("params")
        if event not in EVENT_KINDS:
            raise ScreenHostProtocolError(
                f"screen host sent an unknown event: {event!r}"
            )
        if not isinstance(params, dict):
            raise ScreenHostProtocolError("screen host event params must be an object")
        event_params = dict(params)
        if (
            "sessionId" not in event_params
            and event != "displays"
            and self._active_session_id
        ):
            event_params["sessionId"] = self._active_session_id
        self._emit_event_nowait(str(event), event_params)

    def _accept_host_error(self, frame: Mapping[str, Any]) -> None:
        error = frame.get("error")
        error_map = error if isinstance(error, dict) else {}
        code = error_map.get("code")
        message = error_map.get("message")
        retryable = error_map.get("retryable")
        exc = ScreenHostRemoteError(
            str(message or code or "screen host failed"),
            code=str(code) if isinstance(code, str) and code else None,
            retryable=retryable if isinstance(retryable, bool) else False,
        )
        self._last_error = exc
        self._fail_pending(exc)
        self._emit_error_event(exc.code, str(exc), bool(exc.retryable))
        if (
            self._ever_ready
            and not self._shutting_down
            and not self._restart_suppressed
        ):
            self._schedule_restart(f"screen host error: {exc}")

    # ------------------------------------------------------------------ events
    def _start_event_worker(self) -> None:
        task = self._event_task
        if task is not None and not task.done():
            return
        self._event_task = asyncio.create_task(self._event_loop())

    async def _event_loop(self) -> None:
        while True:
            event, params = await self._event_queue.get()
            handler = self._event_handler
            if handler is None:
                continue
            try:
                outcome = handler(event, params)
                if inspect.isawaitable(outcome):
                    await outcome
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001 - one bad handler must not stop events
                logger.exception("screen host event handler failed event={}", event)

    def _emit_event_nowait(self, event: str, params: Mapping[str, Any]) -> None:
        self._event_queue.put_nowait((event, dict(params)))

    def _emit_error_event(self, code: str, message: str, retryable: bool) -> None:
        params: dict[str, Any] = {
            "code": code,
            "message": message,
            "retryable": retryable,
        }
        if self._active_session_id:
            params["sessionId"] = self._active_session_id
        self._emit_event_nowait("error", params)

    # ------------------------------------------------------------------ watchdogs
    async def _watch_process(
        self, process: asyncio.subprocess.Process, generation: int
    ) -> None:
        return_code = await process.wait()
        if generation != self._generation:
            return
        logger.warning("screen host exited code={}", return_code)
        self._state = "crashed"
        self._hello = None
        self._hello_event.set()
        error = ScreenHostNotRunning(f"screen host exited with code {return_code}")
        self._last_error = error
        self._fail_pending(error)
        if self._ever_ready and not self._intentional_exit and not self._shutting_down:
            self._emit_error_event(error.code, str(error), True)
        if (
            not self._shutting_down
            and not self._restart_suppressed
            and not self._intentional_exit
            and self._ever_ready
        ):
            self._schedule_restart(f"screen host exited with code {return_code}")

    async def _ping_loop(self, generation: int) -> None:
        failures = 0
        while True:
            await asyncio.sleep(self._ping_interval)
            if generation != self._generation or not self.running:
                return
            if (
                self._healthy_since is not None
                and self._restart_times
                and time.monotonic() - self._healthy_since
                >= self._healthy_reset_seconds
            ):
                self._restart_times.clear()
                self._restart_attempt = 0
                self._healthy_since = time.monotonic()
            try:
                await self._request("host.ping", {}, timeout=self._ping_timeout)
            except asyncio.CancelledError:
                raise
            except ScreenHostNotRunning:
                return
            except ScreenHostError as exc:
                failures += 1
                logger.warning(
                    "screen host ping failed attempt={} error={}", failures, exc
                )
                if failures >= self._max_ping_failures:
                    self._schedule_restart(
                        f"screen host ping failed {failures} times"
                    )
                    return
            else:
                failures = 0
                self._healthy_since = time.monotonic()

    def _schedule_restart(self, reason: str) -> None:
        if self._shutting_down or self._restart_suppressed:
            return
        task = self._restart_task
        if task is not None and not task.done():
            return
        self._restart_task = asyncio.create_task(self._restart(reason))

    async def _restart(self, reason: str) -> None:
        async with self._restart_lock:
            if self._shutting_down or self._restart_suppressed:
                return
            logger.warning("restarting screen host: {}", reason)
            self._state = "restarting"
            await self._stop_process(graceful=False)
            for attempt in range(1, self._max_restarts + 1):
                if self._shutting_down or self._restart_suppressed:
                    return
                if not self._reserve_restart_attempt():
                    self._enter_crash_loop(reason)
                    return
                delay = self._restart_backoff[
                    min(attempt - 1, len(self._restart_backoff) - 1)
                ]
                if delay > 0:
                    await asyncio.sleep(delay)
                if self._shutting_down or self._restart_suppressed:
                    return
                try:
                    await self._start_process()
                except ScreenHostNotInstalled as exc:
                    self._last_error = exc
                    self._emit_error_event(exc.code, str(exc), False)
                    self._clear_active_session()
                    return
                except ScreenHostError as exc:
                    self._last_error = exc
                    self._emit_error_event(exc.code, str(exc), True)
                    continue
                except Exception as exc:  # noqa: BLE001 - keep the supervisor alive
                    self._last_error = ScreenHostProtocolError(
                        f"screen host restart failed: {exc}"
                    )
                    self._emit_error_event(
                        self._last_error.code, str(self._last_error), True
                    )
                    continue
                await self._resume_active_session()
                return
            self._enter_crash_loop(reason)

    def _reserve_restart_attempt(self) -> bool:
        now = time.monotonic()
        while (
            self._restart_times
            and now - self._restart_times[0] > self._restart_window_seconds
        ):
            self._restart_times.popleft()
        if len(self._restart_times) >= self._max_restarts:
            return False
        self._restart_times.append(now)
        self._restart_attempt += 1
        return True

    def _enter_crash_loop(self, reason: str) -> None:
        self._crash_loop = True
        self._state = "crashed"
        error = ScreenHostCrashLoop(
            f"screen host restart limit reached ({reason})"
        )
        self._last_error = error
        self._fail_pending(error)
        session_id = self._active_session_id
        params: dict[str, Any] = {
            "code": error.code,
            "message": str(error),
            "retryable": False,
        }
        if session_id:
            params["sessionId"] = session_id
        self._emit_event_nowait("error", params)
        self._clear_active_session()
        logger.error("screen host crash loop: {}", reason)

    async def _resume_active_session(self) -> None:
        session_id = self._active_session_id
        params = self._active_start_params
        if not session_id or params is None:
            return
        self._emit_event_nowait(
            "state", {"sessionId": session_id, "state": "interrupted"}
        )
        try:
            await self._request("host.start", params, timeout=self._request_timeout)
            if self._controller_identity:
                await self._request(
                    "host.controlGrant",
                    {"identity": self._controller_identity},
                    timeout=5.0,
                )
        except asyncio.CancelledError:
            raise
        except ScreenHostError as exc:
            self._last_error = exc
            self._emit_event_nowait(
                "error",
                {
                    "code": exc.code,
                    "message": str(exc),
                    "retryable": bool(getattr(exc, "retryable", False)),
                    "sessionId": session_id,
                },
            )
            self._clear_active_session()

    # ------------------------------------------------------------------ helpers
    def _fail_pending(self, error: ScreenHostError) -> None:
        pending = list(self._pending.values())
        self._pending.clear()
        for future in pending:
            if not future.done():
                future.set_exception(error)

    def _process_lost(self, error: ScreenHostError) -> None:
        self._fail_pending(error)
        self._hello = None
        self._state = "crashed"
        if (
            self._ever_ready
            and not self._shutting_down
            and not self._restart_suppressed
        ):
            self._schedule_restart(str(error))

    def _protocol_failure(self, error: ScreenHostProtocolError, generation: int) -> None:
        if generation != self._generation:
            return
        self._last_error = error
        self._hello = None
        self._hello_error = error
        self._hello_event.set()
        self._state = "crashed"
        self._fail_pending(error)
        self._emit_error_event(error.code, str(error), False)
        if (
            self._ever_ready
            and not self._shutting_down
            and not self._restart_suppressed
        ):
            self._schedule_restart(f"screen host protocol error: {error}")

    def _normalize_info(self, result: Mapping[str, Any]) -> dict[str, Any]:
        capabilities = result.get("capabilities")
        caps = dict(capabilities) if isinstance(capabilities, dict) else {}
        displays = result.get("displays")
        if not isinstance(displays, list):
            displays = caps.get("displays")
        if not isinstance(displays, list):
            displays = []
        input_caps = result.get("input")
        if not isinstance(input_caps, dict):
            input_caps = caps.get("input")
        permissions = result.get("permissions")
        if not isinstance(permissions, dict):
            permissions = caps.get("permissions")
        quality = caps.get("quality")
        max_fps = 30
        if isinstance(quality, dict) and isinstance(quality.get("maxFps"), int):
            max_fps = max(1, min(120, quality["maxFps"]))
        supported = caps.get("supported")
        supported_value = supported if isinstance(supported, bool) else True
        return {
            "supported": supported_value,
            "available": supported_value,
            "platform": self.platform,
            "arch": self.arch,
            "hostVersion": self._hello.get("hostVersion") if self._hello else None,
            "pid": self.pid,
            "displays": [dict(display) for display in displays if isinstance(display, dict)],
            "codecs": ["vp8"],
            "maxFps": max_fps,
            "input": _normalize_flags(
                input_caps,
                ("pointer", "keyboard", "touch", "clipboard"),
                False,
            ),
            "permissions": _normalize_permissions(permissions),
            "activeSessions": 1 if self._active_session_id else 0,
        }


def _host_arch_alias(value: Any) -> str | None:
    """Accept serde's ``x8664`` spelling while emitting the contract value."""

    if not isinstance(value, str):
        return None
    aliases = {
        "x86_64": "x86_64",
        "x8664": "x86_64",
        "amd64": "x86_64",
        "aarch64": "aarch64",
        "arm64": "aarch64",
        "unknown": "unknown",
    }
    return aliases.get(value.lower())


def _normalize_flags(
    value: Any, keys: tuple[str, ...], default: bool
) -> dict[str, bool]:
    source = value if isinstance(value, dict) else {}
    return {
        key: source[key] if isinstance(source.get(key), bool) else default
        for key in keys
    }


def _normalize_permissions(value: Any) -> dict[str, str]:
    source = value if isinstance(value, dict) else {}
    allowed = {"granted", "denied", "not_determined", "unsupported"}
    return {
        key: source[key]
        if isinstance(source.get(key), str) and source.get(key) in allowed
        else "not_determined"
        for key in ("screenRecording", "accessibility", "inputMonitoring")
    }

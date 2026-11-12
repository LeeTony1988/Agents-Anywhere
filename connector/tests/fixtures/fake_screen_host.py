#!/usr/bin/env python3
"""Minimal aa-screen-host stand-in for Connector tests.

It speaks the real HostControl NDJSON protocol over stdio and supports the
methods the supervisor uses. Behaviour knobs are environment variables so the
same fixture covers handshake, event and crash scenarios:

- ``AA_FAKE_HOST_SILENT=1``: never write hello (handshake timeout).
- ``AA_FAKE_HOST_PROTOCOL_VERSION=2``: advertise a different protocol version.
- ``AA_FAKE_HOST_BAD_FRAME=1``: write one frame larger than 4 MiB after hello.
- ``AA_FAKE_HOST_EVENT_LOG=<path>``: append every received method name.
- ``AA_FAKE_HOST_START_DELAY_MS=<ms>``: delay host.start handling.
- ``AA_FAKE_HOST_CRASH_FILE=<path>``: count host.start calls in the file.
- ``AA_FAKE_HOST_CRASH_STARTS=<n>``: exit after accepting the first n starts.
"""

from __future__ import annotations

import json
import os
import platform
import sys
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

MAX_FRAME_BYTES = 4 * 1024 * 1024
PNG_1X1 = (
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAF"
    "gwJ/lxwVAAAAAElFTkSuQmCC"
)
DISPLAYS = [
    {
        "id": "1",
        "name": "Fake Display",
        "width": 1280,
        "height": 720,
        "scale": 1.0,
        "primary": True,
        "refreshHz": 60,
        "x": 0,
        "y": 0,
    }
]
INPUT = {"pointer": True, "keyboard": True, "touch": False, "clipboard": False}
PERMISSIONS = {
    "screenRecording": "granted",
    "accessibility": "granted",
    "inputMonitoring": "granted",
}
QUALITY = {"maxFps": 30, "maxBitrateKbps": 4000, "maxWidth": 1600}


def _write(frame: dict[str, Any]) -> None:
    encoded = (
        json.dumps(frame, ensure_ascii=False, separators=(",", ":")) + "\n"
    ).encode("utf-8")
    sys.stdout.buffer.write(encoded)
    sys.stdout.buffer.flush()


def _event(name: str, params: dict[str, Any]) -> None:
    _write({"v": 1, "type": "event", "event": name, "params": params})


def _response(request_id: int, result: dict[str, Any]) -> None:
    _write({"v": 1, "type": "response", "id": request_id, "ok": True, "result": result})


def _error(request_id: int, code: str, message: str) -> None:
    _write(
        {
            "v": 1,
            "type": "response",
            "id": request_id,
            "ok": False,
            "error": {"code": code, "message": message},
        }
    )


def _log_method(method: str) -> None:
    path = os.environ.get("AA_FAKE_HOST_EVENT_LOG")
    if not path:
        return
    with Path(path).open("a", encoding="utf-8") as handle:
        handle.write(f"{method}\n")


def _platform_name() -> str:
    if sys.platform == "win32":
        return "windows"
    if sys.platform == "darwin":
        return "macos"
    return "linux"


def _arch_name() -> str:
    machine = platform.machine().lower()
    if machine in {"x86_64", "amd64"}:
        return "x86_64"
    if machine in {"aarch64", "arm64"}:
        return "aarch64"
    return "unknown"


def _hello() -> None:
    protocol_version = int(os.environ.get("AA_FAKE_HOST_PROTOCOL_VERSION", "1"))
    _write(
        {
            "v": 1,
            "type": "hello",
            "protocolVersion": protocol_version,
            "hostVersion": "0.0.0-fake",
            "platform": _platform_name(),
            "arch": _arch_name(),
            "pid": os.getpid(),
            "capabilities": {
                "supported": True,
                "codecs": ["vp8"],
                "quality": dict(QUALITY),
            },
        }
    )


def _maybe_crash_after_start() -> bool:
    crash_file = os.environ.get("AA_FAKE_HOST_CRASH_FILE")
    crash_starts = int(os.environ.get("AA_FAKE_HOST_CRASH_STARTS", "0"))
    if not crash_file or crash_starts <= 0:
        return False
    path = Path(crash_file)
    count = 0
    if path.is_file():
        try:
            count = int(path.read_text(encoding="utf-8").strip() or "0")
        except ValueError:
            count = 0
    count += 1
    path.write_text(str(count), encoding="utf-8")
    return count <= crash_starts


def _handle_request(
    message: dict[str, Any],
    state: dict[str, Any],
) -> None:
    request_id = message.get("id")
    method = message.get("method")
    params = message.get("params")
    if not isinstance(request_id, int) or not isinstance(method, str):
        return
    if not isinstance(params, dict):
        params = {}
    _log_method(method)

    if method == "host.ping":
        if os.environ.get("AA_FAKE_HOST_IGNORE_PING") == "1":
            return
        _response(request_id, {"pong": True})
        return
    if method == "host.info":
        _response(
            request_id,
            {
                "platform": _platform_name(),
                "arch": _arch_name(),
                "hostVersion": "0.0.0-fake",
                "pid": os.getpid(),
                "capabilities": {
                    "supported": True,
                    "codecs": ["vp8"],
                    "input": dict(INPUT),
                    "permissions": dict(PERMISSIONS),
                    "displays": DISPLAYS,
                    "quality": dict(QUALITY),
                },
                "displays": DISPLAYS,
                "input": dict(INPUT),
                "permissions": dict(PERMISSIONS),
            },
        )
        return
    if method == "host.start":
        delay_ms = int(os.environ.get("AA_FAKE_HOST_START_DELAY_MS", "0"))
        if delay_ms > 0:
            time.sleep(delay_ms / 1000)
        session_id = params.get("sessionId")
        if not isinstance(session_id, str) or not session_id:
            _error(request_id, "rd_invalid_params", "params.sessionId is required")
            return
        if state["session_id"] == session_id:
            _response(request_id, {"accepted": True})
            return
        if state["session_id"] is not None:
            _error(request_id, "rd_busy", "another session is active")
            return
        state["session_id"] = session_id
        _event("state", {"sessionId": session_id, "state": "starting"})
        _response(request_id, {"accepted": True})
        _event("state", {"sessionId": session_id, "state": "running"})
        _event(
            "stats",
            {
                "sessionId": session_id,
                "fps": 30.0,
                "bitrateKbps": 1200.0,
                "rttMs": 12.0,
                "jitterMs": 1.5,
                "packetLossPct": 0.1,
                "codec": "vp8",
                "width": 1280,
                "height": 720,
                "encoder": "fake",
                "subscribers": 1,
            },
        )
        _event("displays", {"displays": DISPLAYS})
        if _maybe_crash_after_start():
            time.sleep(0.15)
            os._exit(12)
        return
    if method == "host.stop":
        session_id = state["session_id"]
        if isinstance(session_id, str):
            _event("state", {"sessionId": session_id, "state": "stopping"})
        _response(request_id, {"stopped": True})
        if isinstance(session_id, str):
            _event("state", {"sessionId": session_id, "state": "stopped"})
        state["session_id"] = None
        state["controller"] = None
        return
    if method == "host.updateQuality":
        state["quality"].update(
            {
                key: params[key]
                for key in ("maxFps", "maxBitrateKbps", "maxWidth")
                if isinstance(params.get(key), int)
            }
        )
        _response(request_id, {"applied": True})
        return
    if method == "host.controlGrant":
        identity = params.get("identity")
        if not isinstance(identity, str):
            _error(request_id, "rd_invalid_params", "params.identity is required")
            return
        state["controller"] = identity or None
        _response(request_id, {"applied": True})
        return
    if method == "host.requestKeyframe":
        _response(request_id, {"requested": True})
        return
    if method == "host.releaseAll":
        _response(request_id, {"releasedKeys": 0, "releasedButtons": 0})
        return
    if method == "host.screenshot":
        _response(
            request_id,
            {
                "dataBase64": PNG_1X1,
                "width": 1,
                "height": 1,
                "capturedAt": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
                "format": "png",
            },
        )
        return
    if method == "host.input":
        identity = params.get("identity")
        if state["controller"] is not None and identity != state["controller"]:
            _error(request_id, "rd_not_controller", "identity is not the controller")
            return
        events = params.get("events")
        if not isinstance(events, list):
            _error(request_id, "rd_invalid_params", "params.events must be an array")
            return
        applied_seq = 0
        for event in events:
            if isinstance(event, dict) and event.get("t") == "crash":
                os._exit(13)
            if isinstance(event, dict) and isinstance(event.get("seq"), int):
                applied_seq = max(applied_seq, event["seq"])
        _response(request_id, {"appliedSeq": applied_seq})
        return
    if method == "host.crash":
        os._exit(14)
    _error(request_id, "rd_unsupported", f"unsupported method: {method}")


def main() -> None:
    if os.environ.get("AA_FAKE_HOST_SILENT") == "1":
        while True:
            time.sleep(1)
    _hello()
    if os.environ.get("AA_FAKE_HOST_BAD_FRAME") == "1":
        huge = "x" * (MAX_FRAME_BYTES + 4096)
        sys.stdout.buffer.write(
            (json.dumps({"v": 1, "type": "event", "event": "state", "params": {"pad": huge}}) + "\n").encode(
                "utf-8"
            )
        )
        sys.stdout.buffer.flush()
        while True:
            time.sleep(1)
    state: dict[str, Any] = {
        "session_id": None,
        "controller": None,
        "quality": dict(QUALITY),
    }
    for raw_line in sys.stdin.buffer:
        if len(raw_line) > MAX_FRAME_BYTES:
            _write(
                {
                    "v": 1,
                    "type": "error",
                    "error": {
                        "code": "rd_host_protocol_error",
                        "message": "frame exceeds 4 MiB",
                        "retryable": False,
                    },
                }
            )
            sys.exit(78)
        stripped = raw_line.strip()
        if not stripped:
            continue
        try:
            message = json.loads(stripped.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            _write(
                {
                    "v": 1,
                    "type": "error",
                    "error": {
                        "code": "rd_host_protocol_error",
                        "message": "invalid JSON frame",
                        "retryable": False,
                    },
                }
            )
            sys.exit(78)
        if not isinstance(message, dict):
            continue
        frame_type = message.get("type")
        if frame_type == "shutdown":
            sys.exit(0)
        if frame_type == "request":
            _handle_request(message, state)
    sys.exit(0)


if __name__ == "__main__":
    main()

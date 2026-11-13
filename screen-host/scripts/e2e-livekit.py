"""End-to-end: real aa-screen-host binary + real LiveKit server.

Drives the host over its NDJSON stdio protocol, asserts on the frames it emits,
and prints a compact JSON summary. Capture is the Windows stub (no `wgc`
feature) so this runs in a session-0 environment; the media leg is real.

Usage (from the repository root):

    # 1. start a throwaway SFU (matches the `devkey`/`secret` pair below)
    livekit-server --dev --bind 127.0.0.1

    # 2. build the host with the publisher enabled
    screen-host/scripts/build-livekit.ps1 -WebrtcDir <win-x64-release>

    # 3. drive one session
    python -X utf8 screen-host/scripts/e2e-livekit.py \
        screen-host/target/debug/aa-screen-host.exe

Exit codes: 0 success, 1 no hello, 2 wrong `arch`, 3 start rejected,
4 no `running` state, 5 unexpected frame sequence or exit code.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import queue
import subprocess
import sys
import threading
import time

FRAMES: list[dict] = []


def b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def host_token(*, room: str, identity: str, secret: str, key: str) -> str:
    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "iss": key,
        "sub": identity,
        "iat": now,
        "nbf": now,
        "exp": now + 600,
        "video": {
            "roomJoin": True,
            "room": room,
            "canPublish": True,
            "canSubscribe": False,
            "canPublishData": True,
        },
    }
    signing = (
        f"{b64(json.dumps(header, separators=(',', ':')).encode())}."
        f"{b64(json.dumps(payload, separators=(',', ':')).encode())}"
    )
    signature = hmac.new(secret.encode(), signing.encode(), hashlib.sha256).digest()
    return f"{signing}.{b64(signature)}"


def main() -> int:
    binary = sys.argv[1]
    room = "rd_e2e"
    token = host_token(
        room=room, identity="host:conn_e2e", secret="secret", key="devkey"
    )
    process = subprocess.Popen(
        [binary, "--stdio"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        bufsize=1,
        env={**os.environ, "RUST_LOG": "warn"},
    )
    frames: "queue.Queue[dict]" = queue.Queue()

    def read_stdout() -> None:
        assert process.stdout is not None
        for line in process.stdout:
            line = line.strip()
            if not line:
                continue
            try:
                frame = json.loads(line)
            except json.JSONDecodeError:
                continue
            FRAMES.append(frame)
            frames.put(frame)

    def read_stderr() -> None:
        assert process.stderr is not None
        for line in process.stderr:
            print(f"[host] {line.rstrip()}", flush=True)

    threading.Thread(target=read_stdout, daemon=True).start()
    threading.Thread(target=read_stderr, daemon=True).start()

    def send(message: dict) -> None:
        assert process.stdin is not None
        process.stdin.write(json.dumps(message) + "\n")
        process.stdin.flush()

    def wait_for(predicate, timeout: float) -> dict | None:
        for frame in list(FRAMES):
            if predicate(frame):
                return frame
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                frame = frames.get(timeout=0.2)
            except queue.Empty:
                continue
            if predicate(frame):
                return frame
        return None

    hello = wait_for(lambda frame: frame.get("type") == "hello", 10)
    if hello is None:
        print("FAIL: no hello frame")
        process.kill()
        return 1
    print(
        "hello: platform={} arch={} version={}".format(
            hello.get("platform"), hello.get("arch"), hello.get("hostVersion")
        ),
        flush=True,
    )
    if hello.get("arch") != "x86_64":
        print(f"FAIL: hello arch is {hello.get('arch')!r}, expected 'x86_64'")
        process.kill()
        return 2

    send(
        {
            "v": 1,
            "type": "request",
            "id": 1,
            "method": "host.start",
            "params": {
                "sessionId": "rds_e2e",
                "displayId": "0",
                "mode": "view",
                "quality": {"maxFps": 15, "maxBitrateKbps": 1500, "maxWidth": 640},
                "media": {
                    "url": "ws://127.0.0.1:7880",
                    "room": room,
                    "token": token,
                    "identity": "host:conn_e2e",
                },
            },
        }
    )
    started = wait_for(
        lambda frame: frame.get("type") == "response" and frame.get("id") == 1, 30
    )
    print(f"start: {started}", flush=True)
    if not started or not started.get("ok"):
        process.kill()
        return 3

    running = wait_for(
        lambda frame: frame.get("type") == "event"
        and frame.get("event") == "state"
        and frame.get("params", {}).get("state") == "running",
        10,
    )
    print(f"running state event: {json.dumps(running)}", flush=True)
    if running is None:
        process.kill()
        return 4

    time.sleep(3)

    send({"v": 1, "type": "request", "id": 2, "method": "host.stop", "params": {}})
    stopped = wait_for(
        lambda frame: frame.get("type") == "response" and frame.get("id") == 2, 15
    )
    print(f"stop: {stopped}", flush=True)

    send({"v": 1, "type": "shutdown", "reason": "e2e"})
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        process.kill()
    print(f"exit code: {process.returncode}", flush=True)
    state_events = [
        frame.get("params", {}).get("state")
        for frame in FRAMES
        if frame.get("type") == "event" and frame.get("event") == "state"
    ]
    print(
        "summary: "
        + json.dumps(
            {
                "frames": len(FRAMES),
                "stateEvents": state_events,
                "startOk": bool(started and started.get("ok")),
                "stopOk": bool(stopped and stopped.get("ok")),
                "exitCode": process.returncode,
            }
        ),
        flush=True,
    )
    ok = (
        started
        and started.get("ok")
        and "running" in state_events
        and "stopped" in state_events
        and process.returncode == 0
    )
    return 0 if ok else 5


if __name__ == "__main__":
    raise SystemExit(main())

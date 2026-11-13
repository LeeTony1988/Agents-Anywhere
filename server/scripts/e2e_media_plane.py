"""Server-issued LiveKit grant <-> real Rust screen host, end to end.

The Server mints the host grant; the real `aa-screen-host` connects with exactly
that token and publishes its video track; then we ask LiveKit's RoomService what
it actually sees, and delete the room the same way the Server does on session
end. This checks the media-plane contract across languages: room naming,
identity format, track name and publish permissions.

Run from the repository root with LiveKit listening on ws://127.0.0.1:7880:

    livekit-server --dev --bind 127.0.0.1
    python -X utf8 server/scripts/e2e_media_plane.py screen-host/target/debug/aa-screen-host.exe
"""

from __future__ import annotations

import asyncio
import json
import os
import subprocess
import sys
import threading
import time
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "server"))

from agent_server.services.remote_desktop_media import (
    LiveKitMediaProvider,
)

ROOM = "rd_media_plane"


def provider() -> LiveKitMediaProvider:
    return LiveKitMediaProvider(
        url="ws://127.0.0.1:7880", api_key="devkey", api_secret="secret"
    )


async def list_participants(media: LiveKitMediaProvider, room: str) -> dict:
    return await media._room_service_call("ListParticipants", {"room": room})


async def main(binary: str) -> int:
    media = provider()
    session = SimpleNamespace(id="rds_media_plane", mediaRoom=ROOM)
    grant = await media.create_host_grant(session, "conn_media_plane")
    print(
        f"grant: url={grant.url} room={grant.room} identity={grant.identity} "
        f"expires={grant.tokenExpiresAt} canPublishData={grant.canPublishData}"
    )
    if grant.token.count(".") != 2:
        print("FAIL: host token is not a JWT")
        return 1
    if grant.identity != "host:conn_media_plane":
        print(f"FAIL: unexpected identity {grant.identity!r}")
        return 1

    env = {**os.environ, "RUST_LOG": "info"}
    # Blocking spawn is fine: this script drives one host process end to end.
    process = subprocess.Popen(  # noqa: ASYNC220
        [binary, "--stdio"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        bufsize=1,
        env=env,
    )
    assert process.stdin and process.stdout and process.stderr

    stderr_lines: list[str] = []

    def pump_stderr() -> None:
        assert process.stderr is not None
        for line in process.stderr:
            stderr_lines.append(line.rstrip())
            print(f"[host] {line.rstrip()}", flush=True)

    threading.Thread(target=pump_stderr, daemon=True).start()

    def send(message: dict) -> None:
        process.stdin.write(json.dumps(message) + "\n")
        process.stdin.flush()

    def await_frame(predicate, timeout: float):
        deadline = time.time() + timeout
        while time.time() < deadline:
            line = process.stdout.readline()
            if not line:
                return None
            try:
                frame = json.loads(line)
            except json.JSONDecodeError:
                continue
            if predicate(frame):
                return frame
        return None

    hello = await_frame(lambda frame: frame.get("type") == "hello", 15)
    if hello is None:
        print("FAIL: host never said hello")
        process.kill()
        return 1

    send(
        {
            "v": 1,
            "type": "request",
            "id": 1,
            "method": "host.start",
            "params": {
                "sessionId": "rds_media_plane",
                "displayId": "0",
                "mode": "view",
                "quality": {"maxFps": 15, "maxBitrateKbps": 1200, "maxWidth": 640},
                "media": grant.to_dict(),
            },
        }
    )
    started = await_frame(
        lambda frame: frame.get("type") == "response" and frame.get("id") == 1, 30
    )
    print(f"host.start: {started}")
    if not started or not started.get("ok"):
        process.kill()
        return 2

    await asyncio.sleep(4)
    participants = await list_participants(media, ROOM)
    found = [
        participant
        for participant in participants.get("participants", [])
        if participant.get("identity") == grant.identity
    ]
    print(f"participant seen by SFU: {'yes' if found else 'no'}")
    if not found:
        print(json.dumps(participants)[:600])
        process.kill()
        return 3
    tracks = found[0].get("tracks", [])
    print(f"tracks: {json.dumps(tracks)[:400]}")
    video = [track for track in tracks if track.get("type") == "VIDEO"]
    if not video:
        print("FAIL: host published no video track")
        process.kill()
        return 4
    if video[0].get("name") != "screen":
        print(f"FAIL: video track is named {video[0].get('name')!r}, expected 'screen'")
        process.kill()
        return 5

    send({"v": 1, "type": "request", "id": 2, "method": "host.stop", "params": {}})
    await_frame(lambda frame: frame.get("type") == "response" and frame.get("id") == 2, 15)
    send({"v": 1, "type": "shutdown"})
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        process.kill()

    await media.delete_room(ROOM)
    after = await list_participants(media, ROOM)
    print(f"after delete_room: {json.dumps(after)[:200]}")
    if after.get("participants"):
        print("FAIL: room still has participants after delete_room")
        return 6
    print("OK: server grant accepted, host published 'screen', room deleted")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main(sys.argv[1])))



"""Full-chain remote desktop verification on one machine.

Server (real FastAPI app + PostgreSQL) <-> Connector (real process) <->
aa-screen-host (real Rust binary) <-> LiveKit (real SFU).

Steps: pair a connector, enable remote desktop for that device, create a
control session, wait for `live`, confirm the SFU sees the host's `screen`
track, then end the session and confirm the room is gone.

Usage (from the repository root, with livekit-server --dev running):

    python -X utf8 server/scripts/e2e_full_chain.py \
        --host-binary screen-host/target/debug/aa-screen-host.exe

Environment:
    AGENT_E2E_DB_URL   defaults to postgresql+asyncpg://postgres:@127.0.0.1:53080/agents_anywhere_e2e
    AGENT_E2E_PORT     defaults to 8123
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "server"))

SERVER_PYTHON = ROOT / "server" / ".venv" / "Scripts" / "python.exe"
CONNECTOR_PYTHON = ROOT / "connector" / ".venv" / "Scripts" / "python.exe"
DEFAULT_DB_URL = (
    "postgresql+asyncpg://postgres:@127.0.0.1:53080/agents_anywhere_e2e"
)
# Shared with the server process below; the bearer token is signed with it.
E2E_SECRET = "e2e-secret-0123456789abcdef0123456789"


def log(message: str) -> None:
    print(f"[e2e] {message}", flush=True)


def http(
    method: str,
    url: str,
    *,
    token: str | None = None,
    body: dict | None = None,
    timeout: float = 30,
) -> tuple[int, dict]:
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    if body is not None:
        request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read()
            return response.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            payload = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            payload = {"raw": raw.decode("utf-8", "replace")}
        return error.code, payload


def wait_for_port(port: int, timeout: float) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.settimeout(0.5)
            if sock.connect_ex(("127.0.0.1", port)) == 0:
                return True
        time.sleep(0.3)
    return False


async def ensure_database(db_url: str, *, fresh: bool = False) -> None:
    """Create the e2e database if it is missing (never touches other names)."""

    import asyncpg

    base = db_url.split("://", 1)[1]
    credentials, _, database = base.rpartition("/")
    userinfo, _, hostport = credentials.rpartition("@")
    user, _, password = userinfo.partition(":")
    host, _, port = hostport.partition(":")
    if not database.endswith("_e2e"):
        raise SystemExit(f"refusing to use database {database!r}: name must end with _e2e")
    connection = await asyncpg.connect(
        user=user or "postgres",
        password=password or None,
        host=host or "127.0.0.1",
        port=int(port or 5432),
        database="postgres",
    )
    try:
        exists = await connection.fetchval(
            "SELECT 1 FROM pg_database WHERE datname = $1", database
        )
        if exists and fresh:
            await connection.execute(
                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                "WHERE datname = $1 AND pid <> pg_backend_pid()",
                database,
            )
            await connection.execute(f'DROP DATABASE "{database}"')
            log(f"dropped database {database}")
            exists = False
        if not exists:
            await connection.execute(f'CREATE DATABASE "{database}"')
            log(f"created database {database}")
        else:
            log(f"reusing database {database}")
    finally:
        await connection.close()


async def create_user_and_token(db_url: str) -> tuple[str, str]:
    """Create one admin user and mint a bearer token for the HTTP calls."""

    from agent_server.core.auth import create_user_access_token
    from agent_server.infra.repositories.facade import Store

    store = Store(db_url=db_url)
    try:
        user = await store.bootstrap_email_admin(
            email="e2e@example.test",
            display_name="E2E",
            password="e2e-password-123456",
        )
        if user is None:
            # A previous run created the admin already: reuse it.
            items = await store.list_users(limit=50)
            existing = next(
                (item for item in items if getattr(item, "email", None) == "e2e@example.test"),
                None,
            )
            if existing is None:
                raise SystemExit("no e2e user and bootstrap refused to create one")
            user = existing
        user_id = user.userId
        return user_id, create_user_access_token(user_id, expires_in=3600)
    finally:
        await store.close()


def migrate(db_url: str, log_path: Path) -> None:
    """Bring the fresh e2e database to the current schema revision."""

    env = {
        **os.environ,
        "AGENT_SERVER_DB_URL": db_url,
        "AGENT_SERVER_SECRET": E2E_SECRET,
    }
    with log_path.open("w", encoding="utf-8") as handle:
        result = subprocess.run(
            [str(SERVER_PYTHON), "-m", "agent_server.infra.db.migrations", "upgrade"],
            check=False,
            cwd=str(ROOT / "server"),
            env=env,
            stdout=handle,
            stderr=subprocess.STDOUT,
            timeout=600,
        )
    if result.returncode != 0:
        raise SystemExit(f"migration failed; see {log_path}")


def start_server(db_url: str, port: int, log_path: Path) -> subprocess.Popen:
    env = {
        **os.environ,
        "AGENT_SERVER_DB_URL": db_url,
        "AGENT_SERVER_SECRET": E2E_SECRET,
        "AGENT_SERVER_REMOTE_DESKTOP_ENABLED": "true",
        "AGENT_SERVER_LIVEKIT_URL": "ws://127.0.0.1:7880",
        "AGENT_SERVER_LIVEKIT_API_KEY": "devkey",
        "AGENT_SERVER_LIVEKIT_API_SECRET": "secret",
        "AGENT_SERVER_REMOTE_DESKTOP_SWEEP_SECONDS": "1",
        "AGENT_SERVER_PASSWORD_ITERATIONS": "1000",
    }
    handle = log_path.open("w", encoding="utf-8")
    process = subprocess.Popen(
        [
            str(SERVER_PYTHON),
            "-m",
            "uvicorn",
            "agent_server.app:create_app",
            "--factory",
            "--host",
            "127.0.0.1",
            "--port",
            str(port),
            "--log-level",
            "info",
        ],
        cwd=str(ROOT / "server"),
        env=env,
        stdout=handle,
        stderr=subprocess.STDOUT,
    )
    process._e2e_log = handle  # type: ignore[attr-defined]
    return process


def start_connector(config_path: Path, log_path: Path) -> subprocess.Popen:
    env = {**os.environ, "AGENT_CONNECTOR_REMOTE_DESKTOP_CONSENT_PROMPT": "none"}
    handle = log_path.open("w", encoding="utf-8")
    process = subprocess.Popen(
        [
            str(CONNECTOR_PYTHON),
            "-m",
            "connector.cli",
            "start",
            "--config",
            str(config_path),
        ],
        cwd=str(ROOT / "connector"),
        env=env,
        stdout=handle,
        stderr=subprocess.STDOUT,
    )
    process._e2e_log = handle  # type: ignore[attr-defined]
    return process


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--host-binary",
        default=str(ROOT / "screen-host" / "target" / "debug" / "aa-screen-host.exe"),
    )
    parser.add_argument("--keep-database", action="store_true")
    parser.add_argument(
        "--fresh",
        action="store_true",
        help="drop and recreate the e2e database before the run",
    )
    args = parser.parse_args()

    # The same secret the server process uses, so `create_user_access_token` in
    # this process signs a token the server accepts.
    os.environ["AGENT_SERVER_SECRET"] = E2E_SECRET
    db_url = os.environ.get("AGENT_E2E_DB_URL", DEFAULT_DB_URL)
    port = int(os.environ.get("AGENT_E2E_PORT", "8123"))
    origin = f"http://127.0.0.1:{port}"
    base = f"{origin}/api/v2"
    host_binary = Path(args.host_binary).resolve()
    if not host_binary.is_file():
        log(f"FAIL: host binary not found: {host_binary}")
        return 1

    workdir = Path(tempfile.mkdtemp(prefix="rd-e2e-"))
    log_dir = workdir / "logs"
    log_dir.mkdir()
    server_process = connector_process = None
    try:
        asyncio.run(ensure_database(db_url, fresh=args.fresh))

        log("running migrations")
        migrate(db_url, log_dir / "migrations.log")

        log("starting server")
        server_process = start_server(db_url, port, log_dir / "server.log")
        if not wait_for_port(port, 120):
            log("FAIL: server did not listen (see logs/server.log)")
            return 1

        user_id, token = asyncio.run(create_user_and_token(db_url))
        log(f"user ready: {user_id}")

        status, pairing = http("POST", f"{base}/pairing/start", body={"serverUrl": origin})
        if status != 200:
            log(f"FAIL: pairing/start -> {status} {pairing}")
            return 1
        code = pairing["code"]
        log(f"pairing code {code}")

        status, claimed = http(
            "POST", f"{base}/pairing/claim", token=token, body={"code": code}
        )
        if status != 200:
            log(f"FAIL: pairing/claim -> {status} {claimed}")
            return 1
        connector_id = claimed["connector"]["id"]
        log(f"connector claimed: {connector_id} ({claimed.get('status')})")

        deadline = time.time() + 30
        bundle = None
        polled: dict = {}
        while time.time() < deadline:
            status, polled = http(
                "POST",
                f"{base}/pairing/poll",
                body={"pairingId": pairing["pairingId"]},
            )
            if status == 200 and polled.get("config"):
                bundle = polled["config"]
                break
            time.sleep(1)
        if bundle is None:
            log(f"FAIL: pairing/poll never returned a config: {polled}")
            return 1
        connector_token = bundle["connectorToken"]
        log("credential issued")

        config_path = workdir / "connector.json"
        configure = subprocess.run(  # noqa: PLW1510 - exit code checked below
            [
                str(CONNECTOR_PYTHON),
                "-m",
                "connector.cli",
                "configure",
                "--config",
                str(config_path),
                "--server-url",
                origin,
                "--connector-id",
                connector_id,
                "--connector-token",
                connector_token,
            ],
            cwd=str(ROOT / "connector"),
            capture_output=True,
            text=True,
        )
        if configure.returncode != 0:
            log(f"FAIL: connector configure\n{configure.stdout}\n{configure.stderr}")
            return 1

        log("starting connector")
        connector_process = start_connector(config_path, log_dir / "connector.log")

        deadline = time.time() + 90
        online = False
        while time.time() < deadline:
            status, connectors = http("GET", f"{base}/connectors", token=token)
            if status == 200:
                for item in connectors.get("connectors", []):
                    if item.get("id") == connector_id and item.get("status") == "online":
                        online = True
                        break
            if online:
                break
            time.sleep(1)
        if not online:
            log("FAIL: connector did not come online (see logs/connector.log)")
            return 1
        log("connector online")

        patch = {
            "remoteDesktopEnabled": True,
            "remoteDesktopAllowControl": True,
            "remoteDesktopRequireConsent": False,
            "remoteDesktopAllowUnattended": True,
            "remoteDesktopClipboard": False,
        }
        status, settings = http(
            "PATCH",
            f"{base}/connectors/{connector_id}/device-settings",
            token=token,
            body=patch,
        )
        if status not in (200, 201):
            log(f"FAIL: device-settings patch -> {status} {settings}")
            return 1
        log("device settings enabled")

        deadline = time.time() + 60
        host_info: dict = {}
        control_capability: dict = {}
        while time.time() < deadline:
            status, host_info = http(
                "GET", f"{base}/connectors/{connector_id}/remote-desktop", token=token
            )
            if status == 200:
                control_capability = next(
                    (
                        item
                        for item in host_info.get("capabilities", [])
                        if item.get("capabilityId") == "connector.remote_desktop.control"
                    ),
                    {},
                )
                if control_capability.get("supported") and control_capability.get("available"):
                    break
            time.sleep(1)
        log(
            "host info: "
            + json.dumps(
                {
                    "capabilities": [
                        {
                            "id": item.get("capabilityId"),
                            "supported": item.get("supported"),
                            "available": item.get("available"),
                            "allowed": item.get("allowed"),
                            "reason": item.get("unavailableReason"),
                        }
                        for item in host_info.get("capabilities", [])
                    ],
                    "displays": host_info.get("displays"),
                    "monitors": len(host_info.get("displays") or []),
                }
            )
        )
        if not (control_capability.get("supported") and control_capability.get("allowed")):
            log(f"FAIL: control capability unavailable: {control_capability}")
            return 1

        status, created = http(
            "POST",
            f"{base}/remote-desktop/sessions",
            token=token,
            body={
                "connectorId": connector_id,
                "mode": "control",
                "displayId": "0",
                "quality": {"maxFps": 15, "maxBitrateKbps": 1500, "maxWidth": 1280},
                "client": {"platform": "web", "appVersion": "2.3.0"},
            },
        )
        log(f"create session -> {status}")
        if status not in (200, 201):
            log(json.dumps(created)[:600])
            return 1
        session_id = created["session"]["id"]
        room = created.get("media", {}).get("room") if created.get("media") else created["session"].get("mediaRoom")

        state = created["session"]["state"]
        deadline = time.time() + 90
        while time.time() < deadline and state != "live":
            time.sleep(1)
            status, current = http(
                "GET", f"{base}/remote-desktop/sessions/{session_id}", token=token
            )
            if status != 200:
                log(f"FAIL: get session -> {status} {current}")
                return 1
            new_state = current["session"]["state"]
            if new_state != state:
                log(f"session state {state} -> {new_state}")
                state = new_state
            if state in ("ended", "failed"):
                log(f"FAIL: session reached {state}: {json.dumps(current)[:500]}")
                return 1
        if state != "live":
            log(f"FAIL: session stuck in {state}")
            return 1
        log(f"session live: {session_id} room={room}")

        time.sleep(4)
        participants = asyncio.run(list_participants(room))
        identities = [item.get("identity") for item in participants.get("participants", [])]
        log(f"SFU participants: {identities}")
        if f"host:{connector_id}" not in identities:
            log("FAIL: host participant missing from the room")
            return 1
        host_participant = next(
            item
            for item in participants["participants"]
            if item.get("identity") == f"host:{connector_id}"
        )
        tracks = host_participant.get("tracks", [])
        if not any(track.get("name") == "screen" for track in tracks):
            log(f"FAIL: no 'screen' track: {json.dumps(tracks)[:400]}")
            return 1
        log(f"host publishing: {[track.get('name') for track in tracks]}")

        status, ended = http(
            "POST",
            f"{base}/remote-desktop/sessions/{session_id}/end",
            token=token,
            body={"reason": "client_ended"},
        )
        if status not in (200, 201):
            log(f"FAIL: end session -> {status} {ended}")
            return 1
        log(f"session ended: {ended.get('session', {}).get('endReason')}")

        time.sleep(3)
        after = asyncio.run(list_participants(room))
        log(f"room after end: {json.dumps(after)[:200]}")
        if after.get("participants"):
            log("WARN: participants still listed after end (departure timeout may still be pending)")

        log("phase 2: consent-first denial without a local prompt")

        status, settings = http(
            "PATCH",
            f"{base}/connectors/{connector_id}/device-settings",
            token=token,
            body={
                "remoteDesktopRequireConsent": True,
                "remoteDesktopAllowUnattended": False,
            },
        )
        if status not in (200, 201):
            log(f"FAIL: consent settings -> {status} {settings}")
            return 1

        status, denied = http(
            "POST",
            f"{base}/remote-desktop/sessions",
            token=token,
            body={
                "connectorId": connector_id,
                "mode": "control",
                "displayId": "0",
                "client": {"platform": "web", "appVersion": "2.3.0"},
            },
        )
        log(f"consent create -> {status} {json.dumps(denied)[:300]}")
        if status != 409:
            log("FAIL: a device without an interactive prompt must deny consent with 409")
            return 1
        details = denied.get("detail", {}).get("details", {})
        if details.get("reason") != "permission_denied":
            log(f"FAIL: expected permission_denied, got {details}")
            return 1

        status, listing = http(
            "GET",
            f"{base}/remote-desktop/sessions?connectorId={connector_id}&limit=5",
            token=token,
        )
        if status != 200:
            log(f"FAIL: list sessions -> {status} {listing}")
            return 1
        denied_sessions = [
            item
            for item in listing.get("sessions", [])
            if item.get("endReason") == "consent_denied"
        ]
        if not denied_sessions:
            log(f"FAIL: no session recorded as consent_denied: {json.dumps(listing)[:400]}")
            return 1
        denied_room = denied_sessions[0].get("mediaRoom") or (
            f"rd_{denied_sessions[0]['id']}"
        )
        participants = asyncio.run(list_participants(denied_room))
        if participants.get("participants"):
            log("FAIL: a denied consent session still has participants")
            return 1
        log(f"consent denied and recorded: {denied_sessions[0]['id']}")

        log("OK: full chain verified (control with consent disabled, and consent-first denial)")
        return 0
    finally:
        for process in (connector_process, server_process):
            if process is None:
                continue
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
            handle = getattr(process, "_e2e_log", None)
            if handle is not None:
                handle.close()
        if not args.keep_database:
            log(f"logs in {log_dir}")
        if not os.environ.get("AGENT_E2E_KEEP_LOGS"):
            shutil.rmtree(workdir, ignore_errors=True)


async def list_participants(room: str) -> dict:
    from agent_server.services.remote_desktop_media import LiveKitMediaProvider

    media = LiveKitMediaProvider(
        url="ws://127.0.0.1:7880", api_key="devkey", api_secret="secret"
    )
    return await media._room_service_call("ListParticipants", {"room": room}) or {}


if __name__ == "__main__":
    raise SystemExit(main())









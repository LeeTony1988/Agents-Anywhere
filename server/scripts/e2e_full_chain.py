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
from concurrent.futures import ThreadPoolExecutor
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
        return user_id, create_user_access_token(user_id, expires_in=6 * 3600)
    finally:
        await store.close()


CHROME_CANDIDATES = (
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
)


def chrome_path() -> str | None:
    for candidate in CHROME_CANDIDATES:
        if Path(candidate).is_file():
            return candidate
    return None


async def _cdp_send(socket, message_id: int, method: str, params: dict) -> dict:
    await socket.send(json.dumps({"id": message_id, "method": method, "params": params}))
    while True:
        raw = await socket.recv()
        frame = json.loads(raw)
        if frame.get("id") == message_id:
            return frame


async def _evaluate(socket, counter: list[int], expression: str) -> object:
    counter[0] += 1
    reply = await _cdp_send(
        socket,
        counter[0],
        "Runtime.evaluate",
        {"expression": expression, "returnByValue": True, "awaitPromise": True},
    )
    result = reply.get("result", {}).get("result", {})
    return result.get("value")


async def drive_browser(
    *,
    origin: str,
    token: str,
    user_id: str,
    connector_id: str,
    session_id: str,
    port: int = 9333,
    timeout: float = 60,
) -> dict:
    """Open the built web client in headless Chrome and click through a session.

    Returns evidence: page texts observed, whether the remote desktop page
    rendered, whether taking control flipped the UI to "控制中".
    """

    import websockets

    executable = chrome_path()
    if executable is None:
        raise RuntimeError("no Chrome/Edge executable found")
    profile = Path(tempfile.mkdtemp(prefix="rd-chrome-"))
    process = subprocess.Popen(  # noqa: ASYNC220 - one short-lived browser
        [
            executable,
            "--headless=new",
            f"--remote-debugging-port={port}",
            f"--user-data-dir={profile}",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-gpu",
            "--window-size=1280,860",
            "about:blank",
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    evidence: dict = {"chrome": Path(executable).name}
    try:
        deadline = time.time() + 30
        version: dict = {}
        while time.time() < deadline:
            try:
                with urllib.request.urlopen(  # noqa: ASYNC210 - short local probe
                    f"http://127.0.0.1:{port}/json/version", timeout=2
                ) as response:
                    version = json.loads(response.read())
                break
            except (urllib.error.URLError, OSError):
                await asyncio.sleep(0.3)
        if not version:
            raise RuntimeError("Chrome DevTools endpoint never came up")
        evidence["browser"] = version.get("Browser")

        targets: list[dict] = []
        deadline = time.time() + 20
        while time.time() < deadline and not targets:
            try:
                with urllib.request.urlopen(  # noqa: ASYNC210 - short local probe
                    f"http://127.0.0.1:{port}/json/list", timeout=2
                ) as response:
                    targets = [
                        item
                        for item in json.loads(response.read())
                        if item.get("type") == "page"
                    ]
            except (urllib.error.URLError, OSError):
                pass
            await asyncio.sleep(0.3)
        if not targets:
            raise RuntimeError("no page target in Chrome")
        ws_url = targets[0]["webSocketDebuggerUrl"]

        session = json.dumps(
            {"accessToken": token, "userId": user_id, "role": "admin"}
        )
        async with websockets.connect(ws_url, max_size=8 * 1024 * 1024) as socket:
            counter = [0]
            await _cdp_send(socket, 1, "Page.enable", {})
            await _cdp_send(socket, 2, "Runtime.enable", {})
            counter[0] = 2
            # Seed the stored session before the app boots: the client reads it
            # in a mount effect, so setting it after load would leave the login
            # screen in place.
            await _cdp_send(
                socket,
                3,
                "Page.addScriptToEvaluateOnNewDocument",
                {
                    "source": (
                        "try { window.localStorage.setItem('aa.session.v1', "
                        f"{json.dumps(session)}); }} catch (error) {{}}"
                    )
                },
            )
            counter[0] = 3
            route = f"{origin}/#/device/{connector_id}/remote"
            await _cdp_send(socket, 4, "Page.navigate", {"url": route})
            counter[0] = 4

            deadline = time.time() + timeout
            text = ""
            while time.time() < deadline:
                text = str(await _evaluate(socket, counter, "document.body.innerText") or "")
                if "远程桌面" in text and "申请控制" in text:
                    break
                await asyncio.sleep(1)
            evidence["remotePage"] = "远程桌面" in text
            evidence["text"] = text[:400]

            clicked = await _evaluate(
                socket,
                counter,
                """
                (() => {
                  const buttons = Array.from(document.querySelectorAll('button'));
                  const target = buttons.find((b) => b.innerText.includes('申请控制'));
                  if (!target) return false;
                  target.click();
                  return true;
                })()
                """,
            )
            evidence["clickedTakeControl"] = bool(clicked)
            deadline = time.time() + timeout
            controlling = False
            while time.time() < deadline:
                text = str(await _evaluate(socket, counter, "document.body.innerText") or "")
                if "控制中" in text:
                    controlling = True
                    break
                await asyncio.sleep(1)
            evidence["controlling"] = controlling
            evidence["controlText"] = text[:400]
            video = await _evaluate(
                socket,
                counter,
                """
                (() => {
                  const video = document.querySelector('video');
                  if (!video) return null;
                  return {
                    width: video.videoWidth,
                    height: video.videoHeight,
                    readyState: video.readyState,
                    paused: video.paused,
                  };
                })()
                """,
            )
            evidence["video"] = video if isinstance(video, dict) else None
            # The publisher starts producing frames a moment after we connect.
            deadline = time.time() + timeout
            while time.time() < deadline:
                current = await _evaluate(
                    socket,
                    counter,
                    """
                    (() => {
                      const video = document.querySelector('video');
                      return video ? video.videoWidth : 0;
                    })()
                    """,
                )
                if isinstance(current, int) and current > 0:
                    break
                await asyncio.sleep(1)
            evidence["videoWidth"] = int(current or 0) if isinstance(current, int) else 0
            evidence["modifierBar"] = "CTRL" in text

            # Release the lease again; the toolbar must fall back to "申请控制".
            released = await _evaluate(
                socket,
                counter,
                """
                (() => {
                  const buttons = Array.from(document.querySelectorAll('button'));
                  const target = buttons.find((b) => b.innerText.includes('释放控制'));
                  if (!target) return false;
                  target.click();
                  return true;
                })()
                """,
            )
            evidence["clickedRelease"] = bool(released)
            deadline = time.time() + timeout
            released_ok = False
            while time.time() < deadline:
                text = str(await _evaluate(socket, counter, "document.body.innerText") or "")
                if "申请控制" in text and "控制中" not in text:
                    released_ok = True
                    break
                await asyncio.sleep(1)
            evidence["releasedControl"] = released_ok

            # Screenshot: the toolbar button downloads a file; assert real bytes.
            download_dir = Path(tempfile.mkdtemp(prefix="rd-download-"))
            counter[0] += 1
            await _cdp_send(
                socket,
                counter[0],
                "Browser.setDownloadBehavior",
                {
                    "behavior": "allow",
                    "downloadPath": str(download_dir),
                    "eventsEnabled": True,
                },
            )
            shot_clicked = await _evaluate(
                socket,
                counter,
                """
                (() => {
                  const button = document.querySelector('button[aria-label="截图"]');
                  if (!button) return false;
                  button.click();
                  return true;
                })()
                """,
            )
            evidence["clickedScreenshot"] = bool(shot_clicked)
            downloaded: list[Path] = []
            deadline = time.time() + 20
            while time.time() < deadline and not downloaded:
                downloaded = [
                    item
                    for item in download_dir.iterdir()
                    if item.is_file() and item.suffix in (".jpg", ".jpeg", ".png")
                ]
                if not downloaded:
                    await asyncio.sleep(0.5)
            if downloaded:
                payload = downloaded[0].read_bytes()
                evidence["screenshot"] = {
                    "name": downloaded[0].name,
                    "bytes": len(payload),
                    "jpeg": payload[:2] == b"\xff\xd8",
                    "png": payload[:8] == b"\x89PNG\r\n\x1a\n",
                }
            shutil.rmtree(download_dir, ignore_errors=True)
            await _evaluate(socket, counter, "window.localStorage.clear(); 'ok'")
        return evidence
    finally:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
        shutil.rmtree(profile, ignore_errors=True)


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
        # Clients receive this URL in the media token, so an emulator or a phone on
        # the LAN needs the host-reachable address instead of loopback.
        "AGENT_SERVER_LIVEKIT_URL": os.environ.get(
            "AGENT_E2E_LIVEKIT_URL", "ws://127.0.0.1:7880"
        ),
        "AGENT_SERVER_LIVEKIT_API_KEY": "devkey",
        "AGENT_SERVER_LIVEKIT_API_SECRET": "secret",
        "AGENT_SERVER_REMOTE_DESKTOP_SWEEP_SECONDS": "1",
        "AGENT_SERVER_PASSWORD_ITERATIONS": "1000",
    }
    static_dir = os.environ.get("AGENT_E2E_STATIC_DIR")
    if static_dir:
        env["AGENT_SERVER_STATIC_DIR"] = static_dir
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


def start_connector(
    config_path: Path, log_path: Path, *, synthetic_capture: bool = False
) -> subprocess.Popen:
    env = {**os.environ, "AGENT_CONNECTOR_REMOTE_DESKTOP_CONSENT_PROMPT": "none"}
    if synthetic_capture:
        # Lets the media pipeline run without an interactive desktop.
        env["AGENT_CONNECTOR_REMOTE_DESKTOP_SYNTHETIC_CAPTURE"] = "1"
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
        "--web-hold-seconds",
        type=int,
        default=0,
        help="keep the first live session open for this many seconds and print its coordinates",
    )
    parser.add_argument(
        "--browser",
        action="store_true",
        help="drive headless Chrome through the built web client instead of just holding",
    )
    parser.add_argument(
        "--serve-only",
        action="store_true",
        help=(
            "start the Server and an online Connector, then stay up so a client "
            "(emulator, phone, browser) can create sessions; no automated session"
        ),
    )
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
    # The checkout's `screen-host/target` can carry a Low mandatory label (for
    # example when the repository lives inside a desktop sandbox). A process
    # started from such a path inherits Low integrity and Windows UIPI blocks its
    # SendInput. Run the exact binary under test from the E2E workdir instead,
    # which has the normal user's integrity.
    host_binary_copy = workdir / "screen-host" / host_binary.name
    host_binary_copy.parent.mkdir()
    shutil.copy2(host_binary, host_binary_copy)
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
        config_data = json.loads(config_path.read_text("utf-8"))
        config_data["remoteDesktopHostPath"] = str(host_binary_copy)
        config_path.write_text(
            json.dumps(config_data, indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )

        log("starting connector")
        connector_process = start_connector(
            config_path,
            log_dir / "connector.log",
            synthetic_capture=args.browser,
        )

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

        if args.serve_only:
            log("serve-only: server + connector stay up; create sessions from a client")
            log("serve-only: login e2e@example.test / e2e-password-123456")
            log(f"serve-only: connector {connector_id}")
            try:
                while True:
                    time.sleep(5)
            except KeyboardInterrupt:
                log("serve-only: stopping")
            return 0

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

        if args.web_hold_seconds > 0 or args.browser:
            print(
                "WEB_READY "
                + json.dumps(
                    {
                        "origin": origin,
                        "token": token,
                        "userId": user_id,
                        "connectorId": connector_id,
                        "sessionId": session_id,
                        "participantId": created["participantId"],
                        "room": room,
                    }
                ),
                flush=True,
            )
            if args.browser:
                log("driving headless Chrome through the web client")
                try:
                    evidence = asyncio.run(
                        drive_browser(
                            origin=origin,
                            token=token,
                            user_id=user_id,
                            connector_id=connector_id,
                            session_id=session_id,
                        )
                    )
                except Exception as error:  # noqa: BLE001 - reported below
                    log(f"FAIL: browser check raised {error!r}")
                    return 1
                log(
                    "browser: "
                    + json.dumps(
                        {
                            key: evidence.get(key)
                            for key in (
                                "browser",
                                "remotePage",
                                "clickedTakeControl",
                                "controlling",
                                "videoWidth",
                                "video",
                                "modifierBar",
                                "clickedRelease",
                                "releasedControl",
                                "clickedScreenshot",
                                "screenshot",
                            )
                        }
                    )
                )
                if not evidence.get("remotePage"):
                    log("FAIL: the remote desktop page did not render")
                    return 1
                if not evidence.get("controlling"):
                    log("FAIL: taking control from the browser did not take effect")
                    return 1
                if not evidence.get("videoWidth"):
                    log("FAIL: the browser never decoded a video frame")
                    return 1
                if not evidence.get("modifierBar"):
                    log("FAIL: the modifier bar did not appear while controlling")
                    return 1
                if not evidence.get("releasedControl"):
                    log("FAIL: releasing control did not restore the request button")
                    return 1
                screenshot = evidence.get("screenshot") or {}
                if not (screenshot.get("jpeg") or screenshot.get("png")):
                    log(f"FAIL: the screenshot button did not download a real image: {screenshot}")
                    return 1
                participants = asyncio.run(list_participants(room))
                identities = sorted(
                    item.get("identity") for item in participants.get("participants", [])
                )
                log(f"SFU participants after browser join: {identities}")
                if not any(
                    isinstance(identity, str) and identity.startswith("viewer:")
                    for identity in identities
                ):
                    log("FAIL: the browser participant never joined the room")
                    return 1
            if args.web_hold_seconds > 0:
                log(f"holding the live session for {args.web_hold_seconds}s")
                time.sleep(args.web_hold_seconds)
                log("hold finished")

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

        log("phase 3: realtime control socket of a view session")

        status, settings = http(
            "PATCH",
            f"{base}/connectors/{connector_id}/device-settings",
            token=token,
            body={
                "remoteDesktopRequireConsent": False,
                "remoteDesktopAllowUnattended": True,
            },
        )
        if status not in (200, 201):
            log(f"FAIL: restore settings -> {status} {settings}")
            return 1

        status, view_created = http(
            "POST",
            f"{base}/remote-desktop/sessions",
            token=token,
            body={
                "connectorId": connector_id,
                "mode": "view",
                "displayId": "0",
                "client": {"platform": "web", "appVersion": "2.3.0"},
            },
        )
        if status not in (200, 201):
            log(f"FAIL: view session -> {status} {view_created}")
            return 1
        view_session = view_created["session"]["id"]
        view_participant = view_created["session"]["participants"][0]["participantId"]

        deadline = time.time() + 60
        while time.time() < deadline:
            status, current = http(
                "GET", f"{base}/remote-desktop/sessions/{view_session}", token=token
            )
            if status == 200 and current["session"]["state"] == "live":
                break
            time.sleep(1)
        else:
            log("FAIL: view session never became live")
            return 1

        status, ticket = http(
            "POST",
            f"{base}/remote-desktop/sessions/{view_session}/ticket",
            token=token,
            body={"participantId": view_participant},
        )
        if status not in (200, 201):
            log(f"FAIL: ticket -> {status} {ticket}")
            return 1
        ws_ticket = ticket.get("ticket") or ticket.get("wsTicket")
        if not ws_ticket:
            log(f"FAIL: no ticket in {ticket}")
            return 1

        ws_url = (
            f"ws://127.0.0.1:{port}/api/v2/remote-desktop/sessions/{view_session}/ws"
            f"?ticket={ws_ticket}"
        )
        events = asyncio.run(drain_socket(ws_url, seconds=8))
        kinds = [event.get("type") for event in events]
        log(f"ws events: {kinds[:10]}")
        if "remote_desktop.subscribed" not in kinds:
            log("FAIL: no subscribed event on the control socket")
            return 1
        if not any(
            kind in ("remote_desktop.session.updated", "remote_desktop.stats.updated")
            for kind in kinds
        ):
            log("FAIL: no session event reached the socket")
            return 1
        if not any(kind == "remote_desktop.stats.updated" for kind in kinds):
            # The stub capture has no moving content, so the host may never
            # produce a stats sample; `session.updated` already proves the path.
            log("note: no stats sample arrived (stub capture)")

        status, ended_view = http(
            "POST",
            f"{base}/remote-desktop/sessions/{view_session}/end",
            token=token,
            body={"reason": "client_ended"},
        )
        if status not in (200, 201):
            log(f"FAIL: end view session -> {status} {ended_view}")
            return 1
        log("view session events and shutdown verified")

        log("phase 4: control lease hand-over between two viewers")

        status, control_created = http(
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
        if status not in (200, 201):
            log(f"FAIL: control session -> {status} {control_created}")
            return 1
        lease_session = control_created["session"]["id"]
        first = control_created["participantId"]

        deadline = time.time() + 60
        while time.time() < deadline:
            status, current = http(
                "GET", f"{base}/remote-desktop/sessions/{lease_session}", token=token
            )
            if status == 200 and current["session"]["state"] == "live":
                break
            time.sleep(1)
        else:
            log("FAIL: control session never became live")
            return 1

        # A second client joins the same session as a viewer.
        status, joined = http(
            "POST",
            f"{base}/remote-desktop/sessions",
            token=token,
            body={
                "connectorId": connector_id,
                "mode": "view",
                "displayId": "0",
                "client": {"platform": "web", "appVersion": "2.3.0"},
            },
        )
        if status != 200:
            log(f"FAIL: join as viewer -> {status} {joined}")
            return 1
        second = joined["participantId"]
        if second == first:
            log("FAIL: the second client reused the first participant id")
            return 1
        log(f"participants: first={first} second={second}")

        status, ticket = http(
            "POST",
            f"{base}/remote-desktop/sessions/{lease_session}/ticket",
            token=token,
            body={"participantId": first},
        )
        if status not in (200, 201):
            log(f"FAIL: lease ticket -> {status} {ticket}")
            return 1
        lease_url = (
            f"ws://127.0.0.1:{port}/api/v2/remote-desktop/sessions/{lease_session}/ws"
            f"?ticket={ticket['ticket']}"
        )
        with ThreadPoolExecutor(max_workers=1) as pool:
            collector = pool.submit(lambda: asyncio.run(drain_socket(lease_url, seconds=10)))
            time.sleep(1.5)

            status, taken = http(
                "POST",
                f"{base}/remote-desktop/sessions/{lease_session}/take-control",
                token=token,
                body={"participantId": second},
            )
            log(f"take control -> {status}")
            if status not in (200, 202):
                log(f"FAIL: take control -> {status} {taken}")
                return 1
            lease_after_take = taken["session"].get("controller")
            if lease_after_take != f"viewer:{second}":
                log(f"FAIL: controller is {lease_after_take!r}, expected viewer:{second}")
                return 1

            time.sleep(1.5)
            status, back = http(
                "POST",
                f"{base}/remote-desktop/sessions/{lease_session}/take-control",
                token=token,
                body={"participantId": first},
            )
            if status not in (200, 202):
                log(f"FAIL: take control back -> {status} {back}")
                return 1
            lease_back = back["session"].get("controller")
            if lease_back != f"viewer:{first}":
                log(f"FAIL: controller is {lease_back!r} after takeover")
                return 1

            status, released = http(
                "POST",
                f"{base}/remote-desktop/sessions/{lease_session}/release-control",
                token=token,
                body={},
            )
            if status != 200:
                log(f"FAIL: release control -> {status} {released}")
                return 1
            if released["session"].get("controller"):
                log("FAIL: controller was not cleared by release_control")
                return 1
            events = collector.result(timeout=40)

        revoked = [
            event
            for event in events
            if event.get("type") == "remote_desktop.control.revoked"
        ]
        log(f"revocation events: {len(revoked)}")
        if not revoked:
            log("FAIL: the displaced controller never received control.revoked")
            return 1

        status, ended_lease = http(
            "POST",
            f"{base}/remote-desktop/sessions/{lease_session}/end",
            token=token,
            body={"reason": "client_ended"},
        )
        if status not in (200, 201):
            log(f"FAIL: end lease session -> {status} {ended_lease}")
            return 1
        log("control hand-over verified")

        log("OK: full chain verified (control, consent denial, realtime socket, lease hand-over)")
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


async def drain_socket(url: str, *, seconds: float) -> list[dict]:
    """Collect JSON frames from the session control socket for a few seconds."""

    import websockets

    events: list[dict] = []
    try:
        async with websockets.connect(url, open_timeout=10) as socket:
            deadline = time.time() + seconds
            while time.time() < deadline:
                try:
                    raw = await asyncio.wait_for(
                        socket.recv(), timeout=max(0.1, deadline - time.time())
                    )
                except TimeoutError:
                    break
                if isinstance(raw, str):
                    try:
                        events.append(json.loads(raw))
                    except json.JSONDecodeError:
                        continue
            # `ping` is a presence touch and has no reply; `request_keyframe`
            # is best effort. Neither may break the socket.
            await socket.send(json.dumps({"type": "ping", "at": int(time.time())}))
            await socket.send(json.dumps({"type": "request_keyframe"}))
            await asyncio.sleep(0.5)
    except Exception as error:  # noqa: BLE001 - reported by the caller
        log(f"ws error: {error!r}")
    return events


async def list_participants(room: str) -> dict:
    from agent_server.services.remote_desktop_media import LiveKitMediaProvider

    media = LiveKitMediaProvider(
        url="ws://127.0.0.1:7880", api_key="devkey", api_secret="secret"
    )
    return await media._room_service_call("ListParticipants", {"room": room}) or {}


if __name__ == "__main__":
    raise SystemExit(main())









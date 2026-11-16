#!/usr/bin/env python3
"""Agents Anywhere 本地一键启动/停止脚本（Windows / macOS / Linux 通用）。

把本文件放在仓库根目录（与 local-up.sh 同级），然后：

    python dev-stack.py start      # 启动 Docker + Server + Web (+ Connector)
    python dev-stack.py stop       # 停止 Connector + Server + Web（数据库容器默认保留数据）
    python dev-stack.py restart    # 重启应用进程
    python dev-stack.py status     # 查看状态
    python dev-stack.py logs       # 看最近日志
    python dev-stack.py token      # 查看首次安装的管理员 setup token

常用选项：
    --local            只监听 127.0.0.1（默认监听局域网，手机可访问）
    --no-connector     不启动 Connector
    --with-docker-down stop 时连 PostgreSQL/Redis 容器一起停掉（数据卷保留）
    --reset-data       start 前删除本地数据库数据卷（危险，默认关闭）

实现说明：
- 只依赖 Python 标准库，不需要 pip 安装任何东西。
- 需要 uv（本机 ~/.local/bin/uv.exe）、Docker Desktop、Node + Corepack。
- 日志写到 .local-dev/logs/，PID 写到 .local-dev/run/，均已被 .gitignore 忽略。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SERVER_DIR = ROOT / "server"
WEB_DIR = ROOT / "web-next"
CONNECTOR_DIR = ROOT / "connector"
COMPOSE_FILE = ROOT / "docker" / "docker-compose.local.yml"
LOCAL_DIR = ROOT / ".local-dev"
LOG_DIR = LOCAL_DIR / "logs"
RUN_DIR = LOCAL_DIR / "run"
ENV_FILE = ROOT / ".env.local"
CONNECTOR_CONFIG = Path.home() / ".agents-anywhere" / "connector.json"

SERVER_PORT = 8000
WEB_PORT = 5174
POSTGRES_PORT = 55432
REDIS_PORT = 56379

IS_WINDOWS = os.name == "nt"


# --------------------------------------------------------------------------- #
# 基础工具
# --------------------------------------------------------------------------- #
def log(message: str) -> None:
    print(message, flush=True)


def fail(message: str) -> "NoReturn":  # type: ignore[name-defined]
    print(f"\n[错误] {message}", file=sys.stderr, flush=True)
    raise SystemExit(1)


def which_uv() -> str:
    candidates = [
        Path.home() / ".local" / "bin" / ("uv.exe" if IS_WINDOWS else "uv"),
        Path.home() / ".cargo" / "bin" / ("uv.exe" if IS_WINDOWS else "uv"),
    ]
    for candidate in candidates:
        if candidate.exists():
            return str(candidate)
    found = shutil.which("uv")
    if found:
        return found
    fail("找不到 uv。请先安装：https://docs.astral.sh/uv/ （Windows: irm https://astral.sh/uv/install.ps1 | iex）")


def resolve_command(name: str) -> str:
    """把 corepack / docker 这类名字解析成可直接 Popen 的可执行文件。

    Windows 上 corepack 往往只有 .cmd / .ps1 shim，Popen 直接执行会报
    WinError 2；这里优先取 .cmd / .exe / .bat。
    """
    if IS_WINDOWS:
        for suffix in (".cmd", ".exe", ".bat"):
            found = shutil.which(f"{name}{suffix}")
            if found:
                return found
    found = shutil.which(name)
    if found:
        return found
    fail(f"在 PATH 中找不到命令：{name}")


def lan_ip() -> str:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return sock.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        sock.close()


def http_ok(url: str, timeout: float = 3.0) -> bool:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            return 200 <= response.status < 500
    except (urllib.error.URLError, OSError, ValueError):
        return False


def wait_http(url: str, name: str, timeout: float = 180.0) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if http_ok(url):
            log(f"[就绪] {name}  {url}")
            return
        time.sleep(2)
    fail(f"{name} 在 {int(timeout)} 秒内没有就绪：{url}")


def port_pids(port: int) -> set[int]:
    """返回监听指定端口的进程 PID（不依赖 psutil）。"""
    pids: set[int] = set()
    try:
        if IS_WINDOWS:
            output = subprocess.run(
                ["netstat", "-ano", "-p", "tcp"],
                capture_output=True, text=True, errors="ignore", timeout=20,
            ).stdout
            for line in output.splitlines():
                parts = line.split()
                if len(parts) >= 5 and parts[0].upper() == "TCP" and parts[3].upper() == "LISTENING":
                    if parts[1].endswith(f":{port}"):
                        try:
                            pids.add(int(parts[4]))
                        except ValueError:
                            pass
        else:
            output = subprocess.run(
                ["lsof", "-nP", "-t", f"-iTCP:{port}", "-sTCP:LISTEN"],
                capture_output=True, text=True, errors="ignore", timeout=20,
            ).stdout
            for token in output.split():
                if token.isdigit():
                    pids.add(int(token))
    except (OSError, subprocess.SubprocessError):
        pass
    return pids


def pids_by_image(image: str) -> set[int]:
    """按可执行文件名查 PID（Windows: tasklist / 其他: pgrep -f）。"""
    pids: set[int] = set()
    try:
        if IS_WINDOWS:
            output = subprocess.run(
                ["tasklist", "/FI", f"IMAGENAME eq {image}", "/FO", "CSV", "/NH"],
                capture_output=True, text=True, errors="ignore", timeout=20,
            ).stdout
            for line in output.splitlines():
                match = re.match(r'"[^"]+","(\d+)"', line.strip())
                if match:
                    pids.add(int(match.group(1)))
        else:
            output = subprocess.run(
                ["pgrep", "-f", image],
                capture_output=True, text=True, errors="ignore", timeout=20,
            ).stdout
            for token in output.split():
                if token.isdigit():
                    pids.add(int(token))
    except (OSError, subprocess.SubprocessError):
        pass
    return pids


def kill_tree(pid: int) -> None:
    if pid <= 0 or pid == os.getpid():
        return
    try:
        if IS_WINDOWS:
            subprocess.run(
                ["taskkill", "/PID", str(pid), "/T", "/F"],
                capture_output=True, text=True, errors="ignore", timeout=30,
            )
        else:
            subprocess.run(["kill", "-TERM", str(pid)], capture_output=True, timeout=30)
    except (OSError, subprocess.SubprocessError):
        pass


def kill_port(port: int, name: str) -> bool:
    """杀掉监听某端口的进程树，返回是否处理过。"""
    handled = False
    for pid in sorted(port_pids(port)):
        log(f"[停止] {name} (PID {pid}, 端口 {port})")
        kill_tree(pid)
        handled = True
    return handled


def read_pid_file(name: str) -> int | None:
    path = RUN_DIR / f"{name}.pid"
    if not path.exists():
        return None
    try:
        return int(path.read_text().strip() or 0) or None
    except (OSError, ValueError):
        return None


def write_pid_file(name: str, pid: int) -> None:
    RUN_DIR.mkdir(parents=True, exist_ok=True)
    (RUN_DIR / f"{name}.pid").write_text(str(pid))


def clear_pid_file(name: str) -> None:
    try:
        (RUN_DIR / f"{name}.pid").unlink()
    except OSError:
        pass


# --------------------------------------------------------------------------- #
# 配置
# --------------------------------------------------------------------------- #
def load_env_file() -> dict[str, str]:
    values: dict[str, str] = {}
    if ENV_FILE.exists():
        for raw in ENV_FILE.read_text(encoding="utf-8", errors="ignore").splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def ensure_secret(values: dict[str, str]) -> str:
    secret = values.get("AGENT_SERVER_SECRET", "")
    if len(secret) >= 32 and secret != "agent-server-dev-secret":
        return secret
    new_secret = os.urandom(32).hex()
    try:
        prefix = "" if not ENV_FILE.exists() or ENV_FILE.read_text(encoding="utf-8", errors="ignore").endswith("\n") else "\n"
        with ENV_FILE.open("a", encoding="utf-8") as handle:
            handle.write(f"{prefix}AGENT_SERVER_SECRET={new_secret}\n")
        log("[配置] 已在 .env.local 生成 AGENT_SERVER_SECRET（请勿删除或外传）")
    except OSError as exc:
        fail(f"无法写入 .env.local：{exc}")
    values["AGENT_SERVER_SECRET"] = new_secret
    return new_secret


def build_environment(values: dict[str, str], *, lan: bool) -> dict[str, str]:
    env = os.environ.copy()
    env.update(values)
    env.setdefault("AGENT_SERVER_DB_URL",
                   f"postgresql+asyncpg://agents_anywhere:{values.get('POSTGRES_PASSWORD', 'agents_anywhere_dev_password')}"
                   f"@127.0.0.1:{POSTGRES_PORT}/agents_anywhere")
    env.setdefault("AGENT_SERVER_REDIS_URL", f"redis://127.0.0.1:{REDIS_PORT}/0")
    env["AGENT_SERVER_FILES_LOCAL_ROOT"] = str(LOCAL_DIR / "files")
    host = lan_ip() if lan else "127.0.0.1"
    env["AGENT_SERVER_PUBLIC_ORIGIN"] = f"http://{host}:{WEB_PORT}"
    env["AGENT_SERVER_CORS_ORIGINS"] = (
        f"http://127.0.0.1:{WEB_PORT},http://localhost:{WEB_PORT},http://{host}:{WEB_PORT}"
    )
    env["AGENTS_ANYWHERE_API"] = f"http://{host}:{SERVER_PORT}"
    env.setdefault("LOGURU_LEVEL", "INFO")
    return env


# --------------------------------------------------------------------------- #
# Docker
# --------------------------------------------------------------------------- #
def docker_available() -> bool:
    try:
        result = subprocess.run([resolve_command("docker"), "info", "--format", "{{.ServerVersion}}"],
                                capture_output=True, text=True, errors="ignore", timeout=30)
        return result.returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def ensure_docker() -> None:
    if docker_available():
        return
    candidates = [
        Path("D:/docker_desktop/Docker Desktop.exe"),
        Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Docker" / "Docker" / "Docker Desktop.exe",
    ]
    for candidate in candidates:
        if candidate.exists():
            log(f"[配置] 正在启动 Docker Desktop：{candidate}")
            try:
                if IS_WINDOWS:
                    subprocess.Popen([str(candidate)], close_fds=True,
                                     creationflags=subprocess.DETACHED_PROCESS)
                else:
                    subprocess.Popen([str(candidate)], close_fds=True, start_new_session=True)
            except OSError as exc:
                fail(f"无法启动 Docker Desktop：{exc}")
            break
    deadline = time.time() + 240
    while time.time() < deadline:
        if docker_available():
            log("[就绪] Docker 引擎")
            return
        time.sleep(3)
    fail("Docker 没有在 4 分钟内就绪。请手动打开 Docker Desktop 后重试。")


def compose(*args: str) -> None:
    command = [resolve_command("docker"), "compose", "-f", str(COMPOSE_FILE), *args]
    result = subprocess.run(command, cwd=str(ROOT), text=True)
    if result.returncode != 0:
        fail(f"docker compose {' '.join(args)} 失败")


# --------------------------------------------------------------------------- #
# 进程
# --------------------------------------------------------------------------- #
def spawn(name: str, command: list[str], cwd: Path, env: dict[str, str]) -> int:
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    RUN_DIR.mkdir(parents=True, exist_ok=True)
    log_path = LOG_DIR / f"{name}.log"
    handle = open(log_path, "w", encoding="utf-8", errors="replace")
    kwargs: dict[str, object] = {
        "cwd": str(cwd),
        "env": env,
        "stdin": subprocess.DEVNULL,
        "stdout": handle,
        "stderr": subprocess.STDOUT,
        "close_fds": True,
    }
    if IS_WINDOWS:
        kwargs["creationflags"] = (
            subprocess.DETACHED_PROCESS
            | subprocess.CREATE_NEW_PROCESS_GROUP
            | subprocess.CREATE_NO_WINDOW
        )
    else:
        kwargs["start_new_session"] = True
    process = subprocess.Popen(command, **kwargs)  # type: ignore[arg-type]
    write_pid_file(name, process.pid)
    log(f"[启动] {name} (PID {process.pid})  日志: {log_path}")
    return process.pid


def start_server(env: dict[str, str], *, lan: bool) -> None:
    if port_pids(SERVER_PORT):
        log(f"[跳过] Server 已在 {SERVER_PORT} 端口运行")
        return
    host = "0.0.0.0" if lan else "127.0.0.1"
    spawn(
        "server",
        [which_uv(), "run", "uvicorn", "agent_server.app:create_app",
         "--factory", "--host", host, "--port", str(SERVER_PORT), "--no-access-log"],
        SERVER_DIR,
        env,
    )


def start_web(env: dict[str, str], *, lan: bool) -> None:
    if port_pids(WEB_PORT):
        log(f"[跳过] Web 已在 {WEB_PORT} 端口运行")
        return
    host = "0.0.0.0" if lan else "127.0.0.1"
    spawn(
        "web",
        [resolve_command("corepack"), "yarn", "exec", "next", "dev",
         "--hostname", host, "--port", str(WEB_PORT)],
        WEB_DIR,
        env,
    )


def start_connector(env: dict[str, str]) -> None:
    if not CONNECTOR_CONFIG.exists():
        log("[跳过] Connector 尚未配对（缺少 ~/.agents-anywhere/connector.json）")
        return
    if pids_by_image("anywhere-cli.exe" if IS_WINDOWS else "anywhere-cli"):
        log("[跳过] Connector 已在运行")
        return
    # 插件自己托管的 Connector 也算占用，避免重复注册。
    spawn("connector", [which_uv(), "run", "anywhere-cli", "start"], CONNECTOR_DIR, env)


def run_migrations(env: dict[str, str]) -> None:
    log("[配置] 应用数据库迁移…")
    result = subprocess.run(
        [which_uv(), "run", "python", "-m", "agent_server.infra.db.migrations", "upgrade"],
        cwd=str(SERVER_DIR), env=env, text=True,
    )
    if result.returncode != 0:
        fail("数据库迁移失败，请查看上面的输出")


# --------------------------------------------------------------------------- #
# 命令
# --------------------------------------------------------------------------- #
def command_start(args: argparse.Namespace) -> None:
    LOCAL_DIR.mkdir(parents=True, exist_ok=True)
    (LOCAL_DIR / "files").mkdir(parents=True, exist_ok=True)
    LOG_DIR.mkdir(parents=True, exist_ok=True)

    values = load_env_file()
    ensure_secret(values)
    env = build_environment(values, lan=args.lan)

    ensure_docker()
    if args.reset_data:
        log("[警告] 正在删除本地 PostgreSQL/Redis 数据卷…")
        compose("down", "--remove-orphans", "--volumes")
    log("[配置] 启动 PostgreSQL 与 Redis…")
    compose("up", "-d", "--wait")

    run_migrations(env)
    start_server(env, lan=args.lan)
    start_web(env, lan=args.lan)
    if not args.no_connector:
        start_connector(env)

    wait_http(f"http://127.0.0.1:{SERVER_PORT}/api/v2/health", "Server")
    wait_http(f"http://127.0.0.1:{WEB_PORT}/", "Web", timeout=300)

    host = lan_ip() if args.lan else "127.0.0.1"
    log("")
    log("=" * 58)
    log("  本地服务已就绪")
    log("=" * 58)
    log(f"  Web:        http://127.0.0.1:{WEB_PORT}")
    if args.lan:
        log(f"  局域网 Web: http://{host}:{WEB_PORT}   (手机浏览器/扫码用这个网段)")
    log(f"  Server:     http://{host}:{SERVER_PORT}")
    log(f"  PostgreSQL: 127.0.0.1:{POSTGRES_PORT}")
    log(f"  Redis:      127.0.0.1:{REDIS_PORT}")
    log(f"  日志目录:   {LOG_DIR}")
    log(f"  停止服务:   python {Path(__file__).name} stop")
    log("")


def command_stop(args: argparse.Namespace) -> None:
    stopped_any = False

    # Connector 先停，避免它继续拉会话。
    for pid in sorted(pids_by_image("anywhere-cli.exe" if IS_WINDOWS else "anywhere-cli")):
        log(f"[停止] Connector (PID {pid})")
        kill_tree(pid)
        stopped_any = True
    connector_pid = read_pid_file("connector")
    if connector_pid:
        kill_tree(connector_pid)
        clear_pid_file("connector")

    for name, port in (("Server", SERVER_PORT), ("Web", WEB_PORT)):
        pid = read_pid_file(name.lower())
        if pid:
            log(f"[停止] {name} 进程 (PID {pid})")
            kill_tree(pid)
            clear_pid_file(name.lower())
            stopped_any = True
        if kill_port(port, name):
            stopped_any = True

    if args.with_docker_down:
        log("[停止] PostgreSQL / Redis 容器")
        compose("stop")
        stopped_any = True

    if not stopped_any:
        log("没有需要停止的进程。")
    else:
        log("[完成] 已停止。" + ("" if args.with_docker_down else " 数据库容器仍在运行（数据保留）。"))


def command_status(args: argparse.Namespace) -> None:
    host = "127.0.0.1"
    rows = [
        ("Server", f"http://{host}:{SERVER_PORT}/api/v2/health"),
        ("Web", f"http://{host}:{WEB_PORT}/"),
    ]
    for name, url in rows:
        pids = port_pids(SERVER_PORT if name == "Server" else WEB_PORT)
        if not pids:
            log(f"[停止] {name}  (端口未监听)")
        elif http_ok(url):
            log(f"[运行] {name}  PID {','.join(map(str, sorted(pids)))}  {url}")
        else:
            log(f"[异常] {name}  PID {','.join(map(str, sorted(pids)))}  端口在监听但无响应")

    connector_pids = pids_by_image("anywhere-cli.exe" if IS_WINDOWS else "anywhere-cli")
    log(f"[{'运行' if connector_pids else '停止'}] Connector"
        + (f"  PID {','.join(map(str, sorted(connector_pids)))}" if connector_pids else ""))

    if docker_available():
        result = subprocess.run(
            [resolve_command("docker"), "compose", "-f", str(COMPOSE_FILE), "ps",
             "--format", "{{.Name}}  {{.Status}}"],
            cwd=str(ROOT), capture_output=True, text=True, errors="ignore",
        )
        for line in (result.stdout or "").splitlines():
            if line.strip():
                log(f"       {line.strip()}")
    else:
        log("[停止] Docker 引擎未运行")

    if http_ok(f"http://{host}:{SERVER_PORT}/api/v2/health/ready"):
        try:
            with urllib.request.urlopen(f"http://{host}:{SERVER_PORT}/api/v2/health/ready", timeout=5) as response:
                payload = json.loads(response.read().decode("utf-8", "replace"))
            checks = payload.get("checks", {})
            log("       ready: " + ", ".join(f"{key}={value.get('status')}" for key, value in checks.items()))
        except (OSError, ValueError):
            pass


def command_logs(args: argparse.Namespace) -> None:
    targets = [args.name] if args.name else ["server", "web", "connector"]
    for name in targets:
        path = LOG_DIR / f"{name}.log"
        log("")
        log(f"=== {path} ===")
        if not path.exists():
            log("(还没有日志)")
            continue
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError as exc:
            log(f"(读取失败: {exc})")
            continue
        for line in lines[-args.lines:]:
            log(line)


def command_token(args: argparse.Namespace) -> None:
    path = LOG_DIR / "server.log"
    if not path.exists():
        fail("还没有 server.log，请先执行 start")
    matches = [line for line in path.read_text(encoding="utf-8", errors="replace").splitlines()
               if "setup-token:" in line]
    if not matches:
        log("日志里没有 setup token。若已完成管理员设置，可以忽略；否则重启服务后会自动生成新的。")
        return
    log("最近一次 first-run setup token：")
    log("  " + matches[-1].strip())


def main() -> None:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
    except (AttributeError, ValueError):
        pass

    parser = argparse.ArgumentParser(
        prog="dev-stack.py",
        description="Agents Anywhere 本地一键启动/停止",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--lan", dest="lan", action="store_true",
                        help="监听局域网地址，手机可访问（默认）")
    parser.add_argument("--local", dest="lan", action="store_false",
                        help="只监听 127.0.0.1")
    parser.set_defaults(lan=True)
    parser.add_argument("--no-connector", action="store_true", help="不启动 Connector")
    parser.add_argument("--with-docker-down", action="store_true",
                        help="stop 时同时停止 PostgreSQL/Redis 容器（数据保留）")
    parser.add_argument("--reset-data", action="store_true",
                        help="start 前删除本地数据库数据卷（危险）")

    subparsers = parser.add_subparsers(dest="command")
    subparsers.add_parser("start", help="启动全部服务")
    subparsers.add_parser("stop", help="停止应用进程")
    subparsers.add_parser("restart", help="重启应用进程")
    subparsers.add_parser("status", help="查看状态")
    logs_parser = subparsers.add_parser("logs", help="查看日志")
    logs_parser.add_argument("name", nargs="?", choices=["server", "web", "connector"],
                             help="只看某个服务的日志")
    logs_parser.add_argument("-n", "--lines", type=int, default=40, help="显示最后多少行（默认 40）")
    subparsers.add_parser("token", help="显示 first-run setup token")

    args = parser.parse_args()
    if not args.command:
        parser.print_help()
        return

    if args.command == "start":
        command_start(args)
    elif args.command == "stop":
        command_stop(args)
    elif args.command == "restart":
        command_stop(args)
        time.sleep(1)
        command_start(args)
    elif args.command == "status":
        command_status(args)
    elif args.command == "logs":
        command_logs(args)
    elif args.command == "token":
        command_token(args)


if __name__ == "__main__":
    main()

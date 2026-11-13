"""Acceptance harness for one real `aa-screen-host` process.

Runs the automated part of the真机 checklist over the NDJSON stdio protocol:

  1. `host.hello`
  2. `host.info` — prints every display (id/size/scale/primary) so the operator
     can compare it with the physical monitors
  3. per display: `host.start` (view) → `host.screenshot` → `host.stop`
  4. one control session: `host.control.grant` → pointer moves across a grid →
     wheel → key with a modifier → unicode text → `host.screenshot` →
     `host.releaseAll` (must report no pressed keys/buttons) → `host.stop`

Everything it can check without eyes is asserted; the rest is printed as a short
manual checklist. Exit code 0 means the automated part passed.

Usage (from `screen-host/`):

    python scripts/acceptance.py                       # real desktop
    python scripts/acceptance.py --synthetic           # no interactive desktop
    python scripts/acceptance.py --json report.json    # also write the summary
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import queue
import subprocess
import sys
import threading
import time
from pathlib import Path

MAX_FRAME_SECONDS = 30.0


class HostProcess:
    def __init__(self, binary: Path, env: dict[str, str]) -> None:
        self.process = subprocess.Popen(
            [str(binary), "--stdio"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            bufsize=1,
            env=env,
        )
        self.frames: "queue.Queue[dict]" = queue.Queue()
        # Every frame ever seen, so a `wait_for` can look at frames that an
        # earlier wait skipped over (state events arrive before responses).
        self.history: list[dict] = []
        self.stderr: list[str] = []
        self._next_id = 0
        assert self.process.stdout is not None
        assert self.process.stderr is not None
        threading.Thread(target=self._read_stdout, daemon=True).start()
        threading.Thread(target=self._read_stderr, daemon=True).start()

    def _read_stdout(self) -> None:
        assert self.process.stdout is not None
        for line in self.process.stdout:
            line = line.strip()
            if not line:
                continue
            try:
                frame = json.loads(line)
            except json.JSONDecodeError:
                frame = {"type": "garbage", "raw": line}
            self.history.append(frame)
            self.frames.put(frame)

    def _read_stderr(self) -> None:
        assert self.process.stderr is not None
        for line in self.process.stderr:
            line = line.rstrip()
            self.stderr.append(line)
            print(f"[host] {line}", flush=True)

    def mark(self) -> int:
        """Index of the next frame; pair with `wait_for(..., since=mark)`."""

        return len(self.history)

    def wait_for(
        self, predicate, timeout: float = MAX_FRAME_SECONDS, since: int = 0
    ) -> dict | None:
        for frame in list(self.history[since:]):
            if predicate(frame):
                return frame
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                frame = self.frames.get(timeout=0.2)
            except queue.Empty:
                continue
            if predicate(frame):
                return frame
        return None

    def request(self, method: str, params: dict | None = None) -> dict:
        self._next_id += 1
        request_id = self._next_id
        payload = {
            "v": 1,
            "type": "request",
            "id": request_id,
            "method": method,
            "params": params or {},
        }
        assert self.process.stdin is not None
        self.process.stdin.write(json.dumps(payload) + "\n")
        self.process.stdin.flush()
        reply = self.wait_for(
            lambda frame: frame.get("type") == "response" and frame.get("id") == request_id
        )
        if reply is None:
            raise SystemExit(f"no response to {method} within {MAX_FRAME_SECONDS}s")
        return reply

    def shutdown(self) -> int | None:
        """Ask the host to exit and return its exit code."""

        assert self.process.stdin is not None
        try:
            self.process.stdin.write(json.dumps({"v": 1, "type": "shutdown"}) + "\n")
            self.process.stdin.flush()
        except (BrokenPipeError, OSError):
            pass
        try:
            self.process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            self.process.kill()
        return self.process.returncode

    def close(self) -> None:
        if self.process.poll() is None:
            try:
                self.process.stdin.write(json.dumps({"v": 1, "type": "shutdown"}) + "\n")
                self.process.stdin.flush()
            except (BrokenPipeError, OSError):
                pass
            try:
                self.process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.process.kill()


def check(report: dict, name: str, condition: bool, detail: str = "") -> None:
    report[name] = {"ok": bool(condition), "detail": detail}
    status = "PASS" if condition else "FAIL"
    print(f"[{status}] {name}{f' — {detail}' if detail else ''}", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--binary",
        default=str(Path(__file__).resolve().parents[1] / "target" / "debug" / "aa-screen-host.exe"),
    )
    parser.add_argument("--synthetic", action="store_true", help="use the synthetic capture source")
    parser.add_argument("--json", help="write the summary to this path")
    args = parser.parse_args()

    binary = Path(args.binary)
    if not binary.is_file():
        raise SystemExit(f"host binary not found: {binary}")
    env = {**os.environ, "RUST_LOG": "info"}
    if args.synthetic:
        env["AA_SCREEN_HOST_SYNTHETIC_CAPTURE"] = "1"

    report: dict = {"binary": str(binary), "synthetic": args.synthetic}
    host = HostProcess(binary, env)
    try:
        hello = host.wait_for(lambda frame: frame.get("type") == "hello", 15)
        check(report, "hello", hello is not None, json.dumps(hello)[:160] if hello else "no hello")
        if hello is None:
            return 1
        check(
            report,
            "hello.arch_is_contract_value",
            hello.get("arch") in ("x86_64", "aarch64"),
            str(hello.get("arch")),
        )

        info = host.request("host.info")
        displays = (info.get("result") or {}).get("displays") or []
        check(report, "host.info", bool(info.get("ok")) and bool(displays), f"{len(displays)} display(s)")
        for display in displays:
            print(
                "    display {id} {width}x{height} scale={scale} primary={primary} at ({x},{y})".format(
                    **{
                        "id": display.get("id"),
                        "width": display.get("width"),
                        "height": display.get("height"),
                        "scale": display.get("scale"),
                        "primary": display.get("primary"),
                        "x": display.get("x"),
                        "y": display.get("y"),
                    }
                ),
                flush=True,
            )

        # Per-display view sessions with a screenshot each.
        shots: list[dict] = []
        for index, display in enumerate(displays):
            display_id = str(display.get("id"))
            started = host.request(
                "host.start",
                {
                    "sessionId": f"rds_accept_view_{index}",
                    "displayId": display_id,
                    "mode": "view",
                    "quality": {"maxFps": 10, "maxBitrateKbps": 800, "maxWidth": 1280},
                },
            )
            if not started.get("ok"):
                check(report, f"view.start[{display_id}]", False, json.dumps(started.get("error")))
                continue
            shot = host.request(
                "host.screenshot",
                {"displayId": display_id, "maxWidth": 640, "format": "jpeg", "quality": 80},
            )
            payload = shot.get("result") or {}
            data = payload.get("dataBase64") or ""
            try:
                raw = base64.b64decode(data)
            except ValueError:
                raw = b""
            is_jpeg = raw[:2] == b"\xff\xd8" and raw[-2:] == b"\xff\xd9"
            shots.append({"display": display_id, "bytes": len(raw), "jpeg": is_jpeg})
            check(
                report,
                f"view.screenshot[{display_id}]",
                bool(shot.get("ok")) and is_jpeg and payload.get("format") == "jpeg",
                f"{len(raw)} bytes, format={payload.get('format')}",
            )
            host.request("host.stop")

        # One control session exercising the input path.
        started = host.request(
            "host.start",
            {
                "sessionId": "rds_accept_control",
                "displayId": "0",
                "mode": "control",
                "quality": {"maxFps": 10, "maxBitrateKbps": 800, "maxWidth": 640},
            },
        )
        check(report, "control.start", bool(started.get("ok")), json.dumps(started.get("error") or {}))
        granted = host.request("host.controlGrant", {"identity": "viewer:pt_accept"})
        check(report, "control.grant", bool(granted.get("ok")))

        seq = 0
        events: list[dict] = []
        # Pointer grid: four corners plus the centre, then a wheel tick.
        for x, y in ((0.02, 0.02), (0.98, 0.02), (0.98, 0.98), (0.02, 0.98), (0.5, 0.5)):
            seq += 1
            events.append({"v": 1, "seq": seq, "t": "pointer", "a": "move", "x": x, "y": y})
        seq += 1
        events.append({"v": 1, "seq": seq, "t": "wheel", "dx": 0, "dy": -120, "unit": "pixel"})
        seq += 1
        events.append({"v": 1, "seq": seq, "t": "pointer", "a": "down", "x": 0.5, "y": 0.5, "b": "left"})
        seq += 1
        events.append({"v": 1, "seq": seq, "t": "pointer", "a": "up", "x": 0.5, "y": 0.5, "b": "left"})
        seq += 1
        events.append({"v": 1, "seq": seq, "t": "key", "a": "down", "code": "ShiftLeft", "mods": []})
        seq += 1
        events.append({"v": 1, "seq": seq, "t": "key", "a": "down", "code": "KeyA", "mods": ["shift"]})
        seq += 1
        events.append({"v": 1, "seq": seq, "t": "key", "a": "up", "code": "KeyA"})
        seq += 1
        events.append({"v": 1, "seq": seq, "t": "key", "a": "up", "code": "ShiftLeft"})
        seq += 1
        events.append({"v": 1, "seq": seq, "t": "text", "s": "hello 你好"})

        applied = host.request(
            "host.input", {"identity": "viewer:pt_accept", "events": events}
        )
        applied_seq = (applied.get("result") or {}).get("appliedSeq")
        check(
            report,
            "control.input_applied",
            bool(applied.get("ok")) and applied_seq == seq,
            f"appliedSeq={applied_seq} expected={seq}",
        )

        mid_shot = host.request("host.screenshot", {"maxWidth": 640, "format": "png"})
        mid_payload = mid_shot.get("result") or {}
        mid_data = mid_payload.get("dataBase64") or ""
        try:
            mid_raw = base64.b64decode(mid_data) if mid_data else b""
        except ValueError:
            mid_raw = b""
        check(
            report,
            "control.screenshot_png",
            mid_raw[:8] == b"\x89PNG\r\n\x1a\n" and mid_payload.get("format") == "png",
            f"{len(mid_raw)} bytes, format={mid_payload.get('format')}",
        )

        released = host.request("host.releaseAll")
        result = released.get("result") or {}
        check(
            report,
            "control.release_all_clears_keys",
            bool(released.get("ok")) and not result.get("releasedKeys"),
            json.dumps(result),
        )

        stop_mark = host.mark()
        stopped = host.request("host.stop")
        check(report, "control.stop", bool(stopped.get("ok")))

        stopped_state = host.wait_for(
            lambda frame: frame.get("type") == "event"
            and frame.get("event") == "state"
            and frame.get("params", {}).get("state") == "stopped",
            10,
            since=stop_mark,
        )
        check(report, "state.stopped", stopped_state is not None)

        exit_code = host.shutdown()
        check(report, "shutdown.exit_zero", exit_code == 0, f"exit={exit_code}")
    finally:
        host.close()

    passed = all(item["ok"] for item in report.values() if isinstance(item, dict) and "ok" in item)
    report["passed"] = passed
    if args.json:
        Path(args.json).write_text(json.dumps(report, ensure_ascii=False, indent=2), "utf-8")
    print()
    print("自动检查：" + ("全部通过" if passed else "有失败项，见上面的 FAIL"))
    print(
        """
人工确认清单（在真实桌面上逐项确认）：
  [ ] host.info 列出的显示器与物理屏幕一一对应（id/分辨率/缩放/主屏）
  [ ] 多显示器：对每个显示器重复运行，指针落点与画面一致（不是固定落在主屏）
  [ ] 4K/高缩放：截图与画面清晰，缩放后点选位置仍然准确
  [ ] 运行中插拔显示器：host 不崩溃，host.info 反映新的显示器
  [ ] 输入：光标按四角→中心的顺序移动，滚轮方向正确，Shift+A 输入大写 A
  [ ] 中文文本 "你好" 能被输入到目标应用
  [ ] releaseAll 之后没有残留按键（组合键不会卡住）
  [ ] 锁屏/安全桌面下客户端表现为「点了没反应」，host 日志有一条 SendInput 警告
"""
    )
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())

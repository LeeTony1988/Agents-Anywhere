"""In-process Prometheus metrics for remote desktop.

The Server is usually run with several workers, so every series carries the
instance id. Counters are per process; ``rd_sessions_active`` is read from
PostgreSQL on scrape so it stays correct across workers.

This module deliberately depends on nothing but the standard library: the API
layer renders it as ``text/plain; version=0.0.4``.
"""

from __future__ import annotations

import math
import threading
from collections import Counter
from collections.abc import Mapping
from typing import Any

DURATION_BUCKETS_SECONDS: tuple[float, ...] = (
    30.0,
    60.0,
    300.0,
    900.0,
    1800.0,
    3600.0,
    7200.0,
    28800.0,
)


def _escape_label(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def _float(value: Any, default: float = 0.0) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return default
    if math.isnan(value) or math.isinf(value):
        return default
    return float(value)


class RemoteDesktopMetrics:
    """Counters and latest-sample gauges for one Server process."""

    def __init__(self, *, instance_id: str = "unknown") -> None:
        self._instance = instance_id or "unknown"
        self._lock = threading.Lock()
        self._created: Counter[str] = Counter()
        self._ended: Counter[str] = Counter()
        self._errors: Counter[str] = Counter()
        self._duration_sum = 0.0
        self._duration_count = 0
        self._duration_buckets: list[int] = [0] * len(DURATION_BUCKETS_SECONDS)
        self._latest: dict[str, float] = {}

    # ------------------------------------------------------------------ updates
    def record_created(self, mode: str) -> None:
        with self._lock:
            self._created[mode or "unknown"] += 1

    def record_ended(self, reason: str, *, duration_seconds: float | None = None) -> None:
        with self._lock:
            self._ended[reason or "unknown"] += 1
            if duration_seconds is not None and duration_seconds >= 0:
                self._duration_sum += duration_seconds
                self._duration_count += 1
                for index, bound in enumerate(DURATION_BUCKETS_SECONDS):
                    if duration_seconds <= bound:
                        self._duration_buckets[index] += 1

    def record_error(self, code: str) -> None:
        with self._lock:
            self._errors[code or "unknown"] += 1

    def observe_stats(self, stats: Mapping[str, Any]) -> None:
        """Keep the newest host sample for the gauge series."""
        with self._lock:
            self._latest["rd_fps"] = _float(stats.get("fps"))
            self._latest["rd_bitrate_kbps"] = _float(stats.get("bitrateKbps"))
            self._latest["rd_packet_loss_pct"] = _float(stats.get("packetLossPct"))
            self._latest["rd_input_rtt_ms"] = _float(stats.get("rttMs"))

    # ------------------------------------------------------------------ render
    def render(self, *, active_sessions: int) -> str:
        with self._lock:
            created = dict(self._created)
            ended = dict(self._ended)
            errors = dict(self._errors)
            duration_sum = self._duration_sum
            duration_count = self._duration_count
            buckets = list(self._duration_buckets)
            latest = dict(self._latest)
        instance = _escape_label(self._instance)
        lines: list[str] = []

        lines.append("# HELP rd_sessions_active Remote desktop sessions that have not ended.")
        lines.append("# TYPE rd_sessions_active gauge")
        lines.append(
            f'rd_sessions_active{{instance="{instance}"}} {max(0, int(active_sessions))}'
        )

        lines.append("# HELP rd_sessions_total Remote desktop sessions created by this process.")
        lines.append("# TYPE rd_sessions_total counter")
        if created:
            for mode in sorted(created):
                lines.append(
                    f'rd_sessions_total{{mode="{_escape_label(mode)}",instance="{instance}"}} '
                    f"{created[mode]}"
                )
        else:
            lines.append(f'rd_sessions_total{{mode="view",instance="{instance}"}} 0')

        lines.append("# HELP rd_sessions_ended_total Sessions ended by this process, by reason.")
        lines.append("# TYPE rd_sessions_ended_total counter")
        if ended:
            for reason in sorted(ended):
                lines.append(
                    f'rd_sessions_ended_total{{reason="{_escape_label(reason)}",instance="{instance}"}} '
                    f"{ended[reason]}"
                )
        else:
            lines.append(f'rd_sessions_ended_total{{reason="client_ended",instance="{instance}"}} 0')

        lines.append("# HELP rd_session_duration_seconds Ended session duration.")
        lines.append("# TYPE rd_session_duration_seconds histogram")
        cumulative = 0
        for index, bound in enumerate(DURATION_BUCKETS_SECONDS):
            cumulative += buckets[index]
            lines.append(
                f'rd_session_duration_seconds_bucket{{le="{bound:g}",instance="{instance}"}} '
                f"{cumulative}"
            )
        lines.append(
            f'rd_session_duration_seconds_bucket{{le="+Inf",instance="{instance}"}} '
            f"{duration_count}"
        )
        lines.append(
            f'rd_session_duration_seconds_sum{{instance="{instance}"}} {duration_sum:g}'
        )
        lines.append(
            f'rd_session_duration_seconds_count{{instance="{instance}"}} {duration_count}'
        )

        lines.append("# HELP rd_errors_total Remote desktop errors reported to clients.")
        lines.append("# TYPE rd_errors_total counter")
        if errors:
            for code in sorted(errors):
                lines.append(
                    f'rd_errors_total{{code="{_escape_label(code)}",instance="{instance}"}} '
                    f"{errors[code]}"
                )
        else:
            lines.append(f'rd_errors_total{{code="media_error",instance="{instance}"}} 0')

        for name, help_text in (
            ("rd_fps", "Newest host frame rate sample."),
            ("rd_bitrate_kbps", "Newest host bitrate sample in kbit/s."),
            ("rd_packet_loss_pct", "Newest host packet loss sample in percent."),
            ("rd_input_rtt_ms", "Newest host round-trip time sample in ms."),
        ):
            lines.append(f"# HELP {name} {help_text}")
            lines.append(f"# TYPE {name} gauge")
            lines.append(f'{name}{{instance="{instance}"}} {latest.get(name, 0.0):g}')

        return "\n".join(lines) + "\n"

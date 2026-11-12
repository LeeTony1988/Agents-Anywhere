from __future__ import annotations

from agent_server.services.remote_desktop_metrics import (
    DURATION_BUCKETS_SECONDS,
    RemoteDesktopMetrics,
)


def _sample_lines(text: str) -> list[str]:
    return [line for line in text.splitlines() if line and not line.startswith("#")]


def test_render_has_every_series_with_even_when_empty() -> None:
    metrics = RemoteDesktopMetrics(instance_id="inst-1")
    text = metrics.render(active_sessions=0)
    names = {line.split("{")[0] for line in _sample_lines(text)}
    assert "rd_sessions_active" in names
    assert "rd_sessions_total" in names
    assert "rd_sessions_ended_total" in names
    assert "rd_session_duration_seconds_sum" in names
    assert "rd_session_duration_seconds_count" in names
    assert "rd_errors_total" in names
    assert {"rd_fps", "rd_bitrate_kbps", "rd_packet_loss_pct", "rd_input_rtt_ms"} <= names
    assert 'instance="inst-1"' in text
    assert text.endswith("\n")


def test_render_reports_counters_and_histogram() -> None:
    metrics = RemoteDesktopMetrics(instance_id="inst-2")
    metrics.record_created("control")
    metrics.record_created("control")
    metrics.record_created("view")
    metrics.record_ended("client_ended", duration_seconds=10.0)
    metrics.record_ended("idle_timeout", duration_seconds=1000.0)
    metrics.record_error("media_unavailable")
    metrics.observe_stats(
        {"fps": 29.5, "bitrateKbps": 2100, "packetLossPct": 0.4, "rttMs": 42}
    )

    text = metrics.render(active_sessions=3)
    lines = _sample_lines(text)

    assert 'rd_sessions_active{instance="inst-2"} 3' in lines
    assert 'rd_sessions_total{mode="control",instance="inst-2"} 2' in lines
    assert 'rd_sessions_total{mode="view",instance="inst-2"} 1' in lines
    assert 'rd_sessions_ended_total{reason="client_ended",instance="inst-2"} 1' in lines
    assert 'rd_sessions_ended_total{reason="idle_timeout",instance="inst-2"} 1' in lines
    assert 'rd_errors_total{code="media_unavailable",instance="inst-2"} 1' in lines
    assert 'rd_session_duration_seconds_count{instance="inst-2"} 2' in lines
    assert 'rd_session_duration_seconds_sum{instance="inst-2"} 1010' in lines
    assert 'rd_fps{instance="inst-2"} 29.5' in lines
    assert 'rd_bitrate_kbps{instance="inst-2"} 2100' in lines
    assert 'rd_packet_loss_pct{instance="inst-2"} 0.4' in lines
    assert 'rd_input_rtt_ms{instance="inst-2"} 42' in lines

    # Histogram buckets are cumulative and end with +Inf == count.
    first_bucket = DURATION_BUCKETS_SECONDS[0]
    assert (
        f'rd_session_duration_seconds_bucket{{le="{first_bucket:g}",instance="inst-2"}} 1'
        in lines
    )
    assert 'rd_session_duration_seconds_bucket{le="+Inf",instance="inst-2"} 2' in lines


def test_observe_stats_ignores_malformed_values() -> None:
    metrics = RemoteDesktopMetrics(instance_id="inst-3")
    metrics.observe_stats(
        {"fps": "fast", "bitrateKbps": True, "packetLossPct": None, "rttMs": float("nan")}
    )
    lines = _sample_lines(metrics.render(active_sessions=0))
    assert 'rd_fps{instance="inst-3"} 0' in lines
    assert 'rd_bitrate_kbps{instance="inst-3"} 0' in lines
    assert 'rd_packet_loss_pct{instance="inst-3"} 0' in lines
    assert 'rd_input_rtt_ms{instance="inst-3"} 0' in lines


def test_negative_active_sessions_are_clamped() -> None:
    metrics = RemoteDesktopMetrics(instance_id="inst-4")
    assert 'rd_sessions_active{instance="inst-4"} 0' in _sample_lines(
        metrics.render(active_sessions=-2)
    )

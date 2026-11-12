from __future__ import annotations

from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from agent_server.api import metrics as metrics_api
from agent_server.deps import get_store
from agent_server.services.remote_desktop_metrics import RemoteDesktopMetrics


class FakeStore:
    def __init__(self, active: int = 2) -> None:
        self.active = active

    async def list_active_remote_desktop_sessions(self, limit: int = 500) -> list[Any]:
        return [object() for _ in range(self.active)]


def _client(active: int = 2) -> TestClient:
    app = FastAPI()
    app.include_router(metrics_api.router, prefix="/api/v2")
    app.state.remote_desktop_metrics = RemoteDesktopMetrics(instance_id="test-inst")
    app.state.remote_desktop_metrics.record_created("control")
    app.dependency_overrides[get_store] = lambda: FakeStore(active)
    return TestClient(app)


def test_metrics_are_disabled_by_default(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("AGENT_SERVER_METRICS_ENABLED", raising=False)
    response = _client().get("/api/v2/metrics")
    assert response.status_code == 404


def test_metrics_render_prometheus_text_when_enabled(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("AGENT_SERVER_METRICS_ENABLED", "true")
    monkeypatch.delenv("AGENT_SERVER_METRICS_TOKEN", raising=False)
    response = _client(active=2).get("/api/v2/metrics")
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/plain; version=0.0.4")
    body = response.text
    assert 'rd_sessions_active{instance="test-inst"} 2' in body
    assert 'rd_sessions_total{mode="control",instance="test-inst"} 1' in body


def test_metrics_require_the_configured_token(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("AGENT_SERVER_METRICS_ENABLED", "true")
    monkeypatch.setenv("AGENT_SERVER_METRICS_TOKEN", "scrape-me")
    client = _client()
    assert client.get("/api/v2/metrics").status_code == 401
    assert (
        client.get(
            "/api/v2/metrics", headers={"Authorization": "Bearer wrong"}
        ).status_code
        == 401
    )
    allowed = client.get(
        "/api/v2/metrics", headers={"Authorization": "Bearer scrape-me"}
    )
    assert allowed.status_code == 200

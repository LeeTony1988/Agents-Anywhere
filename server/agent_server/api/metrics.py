"""Prometheus scrape endpoint for remote desktop metrics.

Disabled by default. Set ``AGENT_SERVER_METRICS_ENABLED=true`` to expose
``GET /api/v2/metrics``; set ``AGENT_SERVER_METRICS_TOKEN`` to require
``Authorization: Bearer <token>`` from the scraper. Nothing else in the API is
affected when the endpoint is off.
"""

from __future__ import annotations

import os
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import PlainTextResponse

from agent_server.deps import get_store
from agent_server.infra.repositories.facade import Store
from agent_server.services.remote_desktop_metrics import RemoteDesktopMetrics

router = APIRouter(tags=["metrics"])

PROMETHEUS_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8"


def _metrics_enabled() -> bool:
    return (os.environ.get("AGENT_SERVER_METRICS_ENABLED", "") or "").strip().lower() in {
        "1",
        "true",
        "yes",
        "on",
    }


def _authorized(request: Request) -> bool:
    expected = (os.environ.get("AGENT_SERVER_METRICS_TOKEN", "") or "").strip()
    if not expected:
        return True
    header = request.headers.get("authorization") or ""
    scheme, _, token = header.partition(" ")
    return scheme.lower() == "bearer" and token.strip() == expected


@router.get("/metrics", response_class=PlainTextResponse, include_in_schema=False)
async def metrics(
    request: Request,
    store: Annotated[Store, Depends(get_store)],
) -> PlainTextResponse:
    if not _metrics_enabled():
        raise HTTPException(status_code=404, detail="metrics are disabled")
    if not _authorized(request):
        raise HTTPException(status_code=401, detail="invalid metrics token")
    collector: RemoteDesktopMetrics = request.app.state.remote_desktop_metrics
    active_sessions = len(await store.list_active_remote_desktop_sessions(limit=500))
    body = collector.render(active_sessions=active_sessions)
    return PlainTextResponse(body, media_type=PROMETHEUS_CONTENT_TYPE)

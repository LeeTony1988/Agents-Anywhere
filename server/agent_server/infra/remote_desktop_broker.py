"""Cross-worker ephemeral state and event fanout for remote desktop.

PostgreSQL owns session facts. This module only owns data that can be rebuilt:
the per-session event ring, viewer presence, the rolling stats window and
short-lived caches. It mirrors the local-dict + Redis Pub/Sub pattern used by
``TerminalStreamHub``.

The class also works with an injected Redis client (``RedisCoordinator(client=...)``
in tests) and falls back to process-local dictionaries when Redis is absent, so
unit tests never need a real server.
"""

from __future__ import annotations

import asyncio
import json
import time
from contextlib import suppress
from dataclasses import dataclass, field
from typing import Any

from fastapi import WebSocket

from agent_server.infra.redis_coordinator import RedisCoordinator

EVENT_RING_SIZE = 256
EVENT_RING_TTL_SECONDS = 8 * 60 * 60 + 300
PRESENCE_TTL_SECONDS = 90
HOST_FACTS_TTL_SECONDS = 5


@dataclass
class _ClientState:
    queue: asyncio.Queue[dict[str, Any]] = field(
        default_factory=lambda: asyncio.Queue(maxsize=256)
    )
    writer: asyncio.Task[None] | None = None


class RemoteDesktopBroker:
    def __init__(self, coordinator: RedisCoordinator | None = None) -> None:
        self._coordinator = coordinator or RedisCoordinator()
        self._clients: dict[str, dict[str, _ClientState]] = {}
        self._lock = asyncio.Lock()
        self._pubsub = None
        self._listener_task: asyncio.Task[None] | None = None
        # Process-local fallbacks when tests run without Redis.
        self._local_seq: dict[str, int] = {}
        self._local_events: dict[str, list[dict[str, Any]]] = {}
        self._local_presence: dict[str, dict[str, float]] = {}
        self._local_stats: dict[str, list[dict[str, Any]]] = {}
        self._local_values: dict[str, Any] = {}
        self._local_expiry: dict[str, float] = {}

    # ------------------------------------------------------------------ lifecycle
    async def start(self) -> None:
        if not self._coordinator.distributed or self._listener_task is not None:
            return
        self._pubsub = self._coordinator.client.pubsub()
        await self._pubsub.psubscribe(self._coordinator.channel("rd-events", "*"))
        self._listener_task = asyncio.create_task(
            self._listen(), name="remote-desktop-broker-listener"
        )

    async def close(self) -> None:
        for session_id, participants in list(self._clients.items()):
            for participant_id in list(participants):
                await self.detach(session_id, participant_id)
        if self._listener_task is not None:
            self._listener_task.cancel()
            with suppress(asyncio.CancelledError):
                await self._listener_task
            self._listener_task = None
        if self._pubsub is not None:
            await self._pubsub.aclose()
            self._pubsub = None

    # ------------------------------------------------------------------ sockets
    async def attach(
        self, session_id: str, participant_id: str, websocket: WebSocket
    ) -> None:
        async with self._lock:
            state = _ClientState()
            self._clients.setdefault(session_id, {})[participant_id] = state
            state.writer = asyncio.create_task(
                self._write_client(websocket, state),
                name=f"rd-ws-writer-{session_id}-{participant_id}",
            )

    async def detach(self, session_id: str, participant_id: str) -> None:
        async with self._lock:
            participants = self._clients.get(session_id)
            state = participants.pop(participant_id, None) if participants else None
            if participants is not None and not participants:
                self._clients.pop(session_id, None)
        if (
            state is not None
            and state.writer is not None
            and state.writer is not asyncio.current_task()
        ):
            state.writer.cancel()
            with suppress(asyncio.CancelledError):
                await state.writer

    async def _write_client(
        self, websocket: WebSocket, state: _ClientState
    ) -> None:
        while True:
            event = await state.queue.get()
            await websocket.send_json(event)

    async def publish_event(
        self,
        session_id: str,
        event: dict[str, Any],
        *,
        target_participant: str | None = None,
    ) -> str:
        """Assign an event cursor, keep it in the ring and fan it out."""
        cursor = await self.next_cursor(session_id)
        envelope = {"cursor": cursor, "event": event}
        if self._coordinator.distributed:
            await self._coordinator.client.rpush(
                self._key(session_id, "events"),
                json.dumps(envelope, separators=(",", ":")),
            )
            await self._coordinator.client.ltrim(
                self._key(session_id, "events"), -EVENT_RING_SIZE, -1
            )
            await self._coordinator.client.expire(
                self._key(session_id, "events"), EVENT_RING_TTL_SECONDS
            )
            await self._coordinator.client.publish(
                self._coordinator.channel("rd-events", session_id),
                json.dumps(
                    {
                        "sessionId": session_id,
                        "participantId": target_participant,
                        "event": event,
                    },
                    separators=(",", ":"),
                ),
            )
        else:
            ring = self._local_events.setdefault(session_id, [])
            ring.append(envelope)
            del ring[:-EVENT_RING_SIZE]
        await self._deliver_local(session_id, event, target_participant)
        return cursor

    async def _deliver_local(
        self,
        session_id: str,
        event: dict[str, Any],
        target_participant: str | None,
    ) -> None:
        async with self._lock:
            participants = dict(self._clients.get(session_id, {}))
        for participant_id, state in participants.items():
            if target_participant is not None and participant_id != target_participant:
                continue
            try:
                state.queue.put_nowait(event)
            except asyncio.QueueFull:
                # A stuck viewer must not stall the publisher. Dropping is safe:
                # reconnect replays from the event ring.
                continue

    async def _listen(self) -> None:
        assert self._pubsub is not None
        async for message in self._pubsub.listen():
            if message.get("type") != "pmessage":
                continue
            try:
                payload = json.loads(message["data"])
                session_id = payload["sessionId"]
                event = payload["event"]
            except (KeyError, TypeError, ValueError):
                continue
            await self._deliver_local(session_id, event, payload.get("participantId"))

    # ------------------------------------------------------------------ cursor / ring
    async def next_cursor(self, session_id: str) -> str:
        if self._coordinator.distributed:
            value = await self._coordinator.client.incr(
                self._key(session_id, "eventSeq")
            )
            await self._coordinator.client.expire(
                self._key(session_id, "eventSeq"), EVENT_RING_TTL_SECONDS
            )
            return str(int(value))
        value = self._local_seq.get(session_id, 0) + 1
        self._local_seq[session_id] = value
        return str(value)

    async def current_cursor(self, session_id: str) -> str:
        if self._coordinator.distributed:
            value = await self._coordinator.client.get(self._key(session_id, "eventSeq"))
            return str(value or "0")
        return str(self._local_seq.get(session_id, 0))

    async def replay_after(
        self, session_id: str, cursor: str | None
    ) -> list[dict[str, Any]] | None:
        """Return events after ``cursor`` or None when the cursor fell out of the ring."""
        if not cursor:
            return []
        try:
            after = int(cursor)
        except ValueError:
            return None
        if self._coordinator.distributed:
            raw = await self._coordinator.client.lrange(
                self._key(session_id, "events"), 0, -1
            )
            entries = [json.loads(item) for item in raw]
        else:
            entries = list(self._local_events.get(session_id, []))
        if not entries:
            return []
        first = int(entries[0]["cursor"])
        if after + 1 < first:
            return None
        return [
            entry["event"]
            for entry in entries
            if int(entry["cursor"]) > after
        ]

    # ------------------------------------------------------------------ presence
    async def set_presence(
        self,
        session_id: str,
        participant_id: str,
        *,
        ttl_seconds: int = PRESENCE_TTL_SECONDS,
    ) -> None:
        if self._coordinator.distributed:
            await self._coordinator.client.set(
                self._key(session_id, "sub", participant_id),
                str(int(time.time())),
                ex=ttl_seconds,
            )
        else:
            self._local_presence.setdefault(session_id, {})[
                participant_id
            ] = time.time() + ttl_seconds

    async def clear_presence(self, session_id: str, participant_id: str) -> None:
        if self._coordinator.distributed:
            await self._coordinator.client.delete(
                self._key(session_id, "sub", participant_id)
            )
        else:
            self._local_presence.get(session_id, {}).pop(participant_id, None)

    async def participants_seen(self, session_id: str) -> dict[str, float]:
        if self._coordinator.distributed:
            result: dict[str, float] = {}
            async for key in self._coordinator.client.scan_iter(
                self._key(session_id, "sub", "*")
            ):
                participant_id = key.rsplit(":", 1)[-1]
                raw = await self._coordinator.client.get(key)
                try:
                    result[participant_id] = float(raw) if raw is not None else 0.0
                except ValueError:
                    result[participant_id] = 0.0
            return result
        now = time.time()
        presence = self._local_presence.get(session_id, {})
        return {key: value for key, value in presence.items() if value > now}

    # ------------------------------------------------------------------ stats
    async def add_stats(self, session_id: str, stats: dict[str, Any]) -> None:
        if self._coordinator.distributed:
            key = self._key(session_id, "stats")
            await self._coordinator.client.rpush(
                key, json.dumps(stats, separators=(",", ":"))
            )
            await self._coordinator.client.ltrim(key, -30, -1)
            await self._coordinator.client.expire(key, EVENT_RING_TTL_SECONDS)
        else:
            samples = self._local_stats.setdefault(session_id, [])
            samples.append(stats)
            del samples[:-30]

    async def stats_summary(self, session_id: str) -> dict[str, Any] | None:
        if self._coordinator.distributed:
            raw = await self._coordinator.client.lrange(
                self._key(session_id, "stats"), 0, -1
            )
            samples = [json.loads(item) for item in raw]
        else:
            samples = list(self._local_stats.get(session_id, []))
        if not samples:
            return None
        numeric_keys = (
            "fps",
            "bitrateKbps",
            "rttMs",
            "jitterMs",
            "packetLossPct",
        )
        summary: dict[str, Any] = {"samples": len(samples)}
        for key in numeric_keys:
            values = [
                float(sample[key])
                for sample in samples
                if isinstance(sample.get(key), (int, float))
                and not isinstance(sample.get(key), bool)
            ]
            if values:
                summary[f"avg{key[0].upper()}{key[1:]}"] = sum(values) / len(values)
        last = samples[-1]
        for key in ("codec", "width", "height", "encoder", "subscribers"):
            if key in last:
                summary[key] = last[key]
        return summary

    # ------------------------------------------------------------------ small cache
    async def set_json(self, key: str, value: Any, *, ttl_seconds: int) -> None:
        if self._coordinator.distributed:
            await self._coordinator.client.set(
                self._key("cache", key),
                json.dumps(value, separators=(",", ":")),
                ex=ttl_seconds,
            )
        else:
            self._local_values[key] = value
            self._local_expiry[key] = time.time() + ttl_seconds

    async def get_json(self, key: str) -> Any | None:
        if self._coordinator.distributed:
            raw = await self._coordinator.client.get(self._key("cache", key))
            if raw is None:
                return None
            try:
                return json.loads(raw)
            except ValueError:
                return None
        if self._local_expiry.get(key, 0) <= time.time():
            self._local_values.pop(key, None)
            return None
        return self._local_values.get(key)

    async def delete(self, key: str) -> None:
        if self._coordinator.distributed:
            await self._coordinator.client.delete(self._key("cache", key))
        else:
            self._local_values.pop(key, None)
            self._local_expiry.pop(key, None)

    async def increment(self, key: str, *, ttl_seconds: int | None = None) -> int:
        if self._coordinator.distributed:
            redis_key = self._key("counter", key)
            value = await self._coordinator.client.incr(redis_key)
            if ttl_seconds is not None:
                await self._coordinator.client.expire(redis_key, ttl_seconds)
            return int(value)
        value = int(self._local_values.get(key, 0)) + 1
        self._local_values[key] = value
        if ttl_seconds is not None:
            self._local_expiry[key] = time.time() + ttl_seconds
        return value

    async def rate_limit(
        self, key: str, *, limit: int, window_seconds: int
    ) -> int:
        """Return 0 when allowed, else the retry delay in milliseconds."""
        if self._coordinator.distributed:
            redis_key = self._key("rate", key)
            value = await self._coordinator.client.incr(redis_key)
            if value == 1:
                await self._coordinator.client.expire(redis_key, window_seconds)
            if value <= limit:
                return 0
            ttl = await self._coordinator.client.ttl(redis_key)
            return max(1, int(ttl)) * 1000
        now = time.time()
        window = self._local_values.setdefault(f"rate:{key}", [])
        if isinstance(window, list):
            cutoff = now - window_seconds
            window[:] = [stamp for stamp in window if stamp > cutoff]
            if len(window) >= limit:
                return max(1, int(window_seconds - (now - window[0]))) * 1000
            window.append(now)
        return 0

    # ------------------------------------------------------------------ helpers
    def _key(self, *parts: str) -> str:
        return self._coordinator.key("rd", *parts)

"use client"

import * as React from "react"
import { remoteDesktopApi } from "@/lib/remote-desktop/api"
import type { RemoteDesktopMediaGrant } from "@/generated/remote-desktop/v1_0/media-grant"
import type { RemoteDesktopSession } from "@/generated/remote-desktop/v1_0/session"
import type { RemoteDesktopInputEvent } from "@/generated/remote-desktop/v1_0/input-event"
import type { RemoteDesktopHostControlEvent } from "@/generated/remote-desktop/v1_0/host-control-event"
import type { RemoteDesktopClientWsMessage } from "@/generated/remote-desktop/v1_0/client-ws-message"
import type {
  RemoteVideoTrack,
  RemoteTrack,
  RemoteTrackPublication,
  Room as LiveKitRoom,
  VideoReceiverStats,
} from "livekit-client"

/**
 * Client hook wiring a remote-desktop session together:
 *
 * - LiveKit media: joins the session room with the short-TTL `media` grant,
 *   subscribes the host's `screen` video track and exposes stream stats.
 *   `livekit-client` is loaded with a dynamic `import()` from an effect — never
 *   at module top level — so the static export never touches `window` at load.
 * - Session control WebSocket: `WS /remote-desktop/sessions/{id}/ws?ticket=…`,
 *   handling the six `remote_desktop.*` event types, resuming with `cursor`
 *   after drops (tickets are one-shot; reconnects request a fresh one) and
 *   sending the three client control frames (ping / request_keyframe /
 *   update_quality).
 * - Input: JSON `aa.input` events on the reliable data channel and binary
 *   `aa.pointer` frames on the lossy channel.
 *
 * Fallback note: when the data channel cannot publish, v1 does NOT re-route
 * input over the control WebSocket — the contract (`client-ws-message`) only
 * allows ping/keyframe/quality there, and media/input never travel on that
 * socket (docs/api/remote-desktop.md §会话 WebSocket). Send helpers return
 * `false` on failure; a transport-level input fallback is left for a later
 * revision (TODO).
 */

type LiveKitModule = typeof import("livekit-client")

// Lazily cached dynamic import — evaluated on first connect, never statically.
let livekitModule: Promise<LiveKitModule> | null = null
function loadLiveKit(): Promise<LiveKitModule> {
  if (!livekitModule) livekitModule = import("livekit-client")
  return livekitModule
}

export type RemoteDesktopConnectionState =
  | "idle"
  | "connecting"
  | "connected"
  | "reconnecting"
  | "ended"
  | "failed"

export type RemoteDesktopStreamStats = {
  fps: number
  bitrateKbps: number
  rttMs: number
  jitterMs: number
  packetLossPct: number
  codec: string
  width: number
  height: number
}

export type RemoteDesktopQualityRequest = {
  maxFps?: number
  maxBitrateKbps?: number
  maxWidth?: number
}

export type UseRemoteDesktopOptions = {
  /** API bearer token; `null` tears everything down. */
  token: string | null
  sessionId: string | null
  participantId: string | null
  /**
   * LiveKit join grant from the create/join response. `null` while awaiting
   * consent (the server issues no media token then). Replacing it (e.g. with a
   * controller grant after take-control) re-joins the room.
   */
  media: RemoteDesktopMediaGrant | null
  /** One-shot WS ticket from the create/join response (`ws.ticket`). */
  wsTicket: string | null
  /** Which grant role to request on token renewal. */
  role: "viewer" | "controller"
  onSessionUpdated?: (session: RemoteDesktopSession) => void
  onControlRevoked?: (reason: "taken_over" | "lease_expired" | "policy_revoked") => void
  onEnded?: (endReason: string, endedAt: string) => void
  /** `remote_desktop.error` and host → client ack/reject/clipboard events. */
  onError?: (code: string, message: string, retryable: boolean) => void
  onHostEvent?: (event: RemoteDesktopHostControlEvent) => void
}

export type UseRemoteDesktopResult = {
  state: RemoteDesktopConnectionState
  /** Merged stream stats: server `stats.updated` plus local receiver samples. */
  stats: RemoteDesktopStreamStats | null
  /** Attach to the `<video>` element rendering the screen track. */
  videoRef: (element: HTMLVideoElement | null) => void
  /** Publish a JSON `aa.input` event (reliable). `false` when it cannot be sent. */
  sendInput: (event: RemoteDesktopInputEvent) => boolean
  /** Publish a 12-byte `aa.pointer` frame (lossy). `false` when it cannot be sent. */
  sendPointerFrame: (frame: Uint8Array) => boolean
  /** Best-effort keyframe request over the control WS (contract: best-effort). */
  requestKeyframe: () => void
  updateQuality: (quality: RemoteDesktopQualityRequest) => void
  /** Control-plane keepalive (also implicitly renews the controller lease). */
  ping: () => void
  /** Manual retry after `failed`; refreshes the media token before re-joining. */
  reconnect: () => void
}

const WS_RECONNECT_DELAY_MS = 2_000
const WS_PING_INTERVAL_MS = 15_000
const STATS_POLL_INTERVAL_MS = 2_000
/** Renew the LiveKit token when this much TTL is left (controller TTL is 120 s). */
const TOKEN_RENEW_BEFORE_MS = 120_000

export function useRemoteDesktop(options: UseRemoteDesktopOptions): UseRemoteDesktopResult {
  const {
    token,
    sessionId,
    participantId,
    media,
    wsTicket,
    role,
  } = options

  const [state, setState] = React.useState<RemoteDesktopConnectionState>("idle")
  const [stats, setStats] = React.useState<RemoteDesktopStreamStats | null>(null)

  const videoElementRef = React.useRef<HTMLVideoElement | null>(null)
  const screenTrackRef = React.useRef<RemoteVideoTrack | null>(null)
  const roomRef = React.useRef<LiveKitRoom | null>(null)
  const wsRef = React.useRef<WebSocket | null>(null)
  const grantRef = React.useRef<RemoteDesktopMediaGrant | null>(media)
  grantRef.current = media
  /** Bumped by `reconnect()` so the media effect re-joins with a fresh grant. */
  const [rejoinNonce, setRejoinNonce] = React.useState(0)

  // Latest callbacks without re-arming connection effects.
  const callbacksRef = React.useRef(options)
  callbacksRef.current = options

  const attachScreenTrack = React.useCallback(() => {
    const track = screenTrackRef.current
    const element = videoElementRef.current
    if (track && element) track.attach(element)
  }, [])

  const videoRef = React.useCallback((element: HTMLVideoElement | null) => {
    videoElementRef.current = element
    attachScreenTrack()
  }, [attachScreenTrack])

  const publish = React.useCallback((
    payload: Uint8Array<ArrayBuffer>,
    topic: string,
    reliable: boolean,
  ): boolean => {
    const room = roomRef.current
    const grant = grantRef.current
    if (!room || !grant?.canPublishData || room.state !== "connected") return false
    try {
      // Fire-and-forget: LiveKit queues on the data channel; a rejected
      // promise means the packet was not delivered (TODO: transport fallback).
      void room.localParticipant.publishData(payload, { reliable, topic })
      return true
    } catch {
      return false
    }
  }, [])

  const sendInput = React.useCallback((event: RemoteDesktopInputEvent): boolean => {
    const bytes = new TextEncoder().encode(JSON.stringify(event))
    return publish(bytes, "aa.input", true)
  }, [publish])

  const sendPointerFrame = React.useCallback((frame: Uint8Array): boolean => {
    const copy = new Uint8Array(frame.byteLength)
    copy.set(frame)
    return publish(copy, "aa.pointer", false)
  }, [publish])

  const sendControlMessage = React.useCallback((message: RemoteDesktopClientWsMessage) => {
    // The control WS is owned by the media effect below; the socket handle
    // lives in its closure, so expose it through a ref set at connect time.
    const socket = wsRef.current
    if (socket && socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify(message))
    }
  }, [])

  const requestKeyframe = React.useCallback(() => {
    sendControlMessage({ type: "request_keyframe" })
  }, [sendControlMessage])

  const updateQuality = React.useCallback((quality: RemoteDesktopQualityRequest) => {
    sendControlMessage({ type: "update_quality", ...quality })
  }, [sendControlMessage])

  const ping = React.useCallback(() => {
    sendControlMessage({ type: "ping", at: Date.now() })
  }, [sendControlMessage])

  // ── LiveKit media ────────────────────────────────────────────────────────
  React.useEffect(() => {
    if (!token || !sessionId || !media) {
      if (!media) {
        screenTrackRef.current = null
        roomRef.current = null
        setState((current) => (current === "idle" || current === "ended" ? current : "idle"))
      }
      return
    }

    let cancelled = false
    let room: LiveKitRoom | null = null
    let statsTimer: number | null = null
    let renewalTimer: number | null = null
    let previousSample: { stats: VideoReceiverStats; at: number } | null = null

    const publishRoomState = () => {
      if (cancelled || !room) return
      if (room.state === "connected") {
        setState((current) => (current === "ended" ? current : "connected"))
      } else if (room.state === "reconnecting" || room.state === "signalReconnecting") {
        setState((current) => (current === "ended" ? current : "reconnecting"))
      }
    }

    const pollStats = async () => {
      const track = screenTrackRef.current
      if (!track) return
      const sample = await track.getReceiverStats().catch(() => undefined)
      if (!sample || cancelled) return
      const now = Date.now()
      const previous = previousSample
      previousSample = { stats: sample, at: now }
      if (!previous) return
      const elapsedMs = Math.max(1, now - previous.at)
      const frames = sample.framesDecoded - previous.stats.framesDecoded
      const packetsLost = sample.packetsLost ?? 0
      const packetsReceived = sample.packetsReceived ?? 0
      const packetsTotal = packetsLost + packetsReceived
      const bytesDelta = (sample.bytesReceived ?? 0) - (previous.stats.bytesReceived ?? 0)
      const bitrateKbps = Math.max(0, Math.round(((bytesDelta * 8) / elapsedMs) * 10) / 10)
      setStats((current) => ({
        fps: Math.max(0, Math.round((frames * 1000) / elapsedMs * 10) / 10),
        bitrateKbps,
        rttMs: current?.rttMs ?? 0,
        jitterMs: current?.jitterMs ?? Math.round((sample.jitter ?? 0) * 1000 * 10) / 10,
        packetLossPct: packetsTotal > 0
          ? Math.round((packetsLost / packetsTotal) * 1000) / 10
          : current?.packetLossPct ?? 0,
        codec: current?.codec ?? (sample.mimeType?.split("/").pop() ?? ""),
        width: sample.frameWidth ?? current?.width ?? 0,
        height: sample.frameHeight ?? current?.height ?? 0,
      }))
    }

    const scheduleRenewal = () => {
      if (cancelled) return
      const grant = grantRef.current
      if (!grant) return
      const ttlMs = Date.parse(grant.tokenExpiresAt) - Date.now() - TOKEN_RENEW_BEFORE_MS
      renewalTimer = window.setTimeout(() => {
        void (async () => {
          if (cancelled || !token || !sessionId || !participantId) return
          try {
            const refreshed = await remoteDesktopApi.refreshMediaToken(token, sessionId, {
              participantId,
              role,
            })
            if (cancelled) return
            grantRef.current = refreshed
          } catch {
            // A failed renewal surfaces when the next re-join needs a token.
          }
          scheduleRenewal()
        })()
      }, Math.max(1_000, ttlMs))
    }

    const connectRoom = async () => {
      const lk = await loadLiveKit()
      if (cancelled) return
      room = new lk.Room()
      roomRef.current = room

      room.on(lk.RoomEvent.TrackSubscribed, (track: RemoteTrack, publication: RemoteTrackPublication) => {
        if (track.kind !== lk.Track.Kind.Video || publication.trackName !== "screen") return
        screenTrackRef.current = track as RemoteVideoTrack
        attachScreenTrack()
        setState("connected")
      })
      room.on(lk.RoomEvent.TrackUnsubscribed, (_track: RemoteTrack, publication: RemoteTrackPublication) => {
        if (publication.trackName !== "screen") return
        screenTrackRef.current = null
      })
      room.on(lk.RoomEvent.Connected, publishRoomState)
      room.on(lk.RoomEvent.Reconnecting, publishRoomState)
      room.on(lk.RoomEvent.Reconnected, publishRoomState)
      room.on(lk.RoomEvent.Disconnected, () => {
        if (cancelled) return
        screenTrackRef.current = null
        setState("failed")
      })
      room.on(lk.RoomEvent.DataReceived, (
        payload: Uint8Array,
        _participant,
        _kind,
        topic?: string,
      ) => {
        if (cancelled) return
        if (topic !== "aa.input.ack" && topic !== "aa.host.control") return
        try {
          const event = JSON.parse(new TextDecoder().decode(payload)) as RemoteDesktopHostControlEvent
          callbacksRef.current.onHostEvent?.(event)
        } catch {
          // Ignore malformed host frames.
        }
      })

      const grant = grantRef.current
      if (!grant) return
      setState("connecting")
      try {
        await room.connect(grant.url, grant.token)
        if (cancelled) return
        attachScreenTrack()
        scheduleRenewal()
        statsTimer = window.setInterval(() => { void pollStats() }, STATS_POLL_INTERVAL_MS)
      } catch {
        if (!cancelled) setState("failed")
      }
    }

    void connectRoom()

    return () => {
      cancelled = true
      if (statsTimer !== null) window.clearInterval(statsTimer)
      if (renewalTimer !== null) window.clearTimeout(renewalTimer)
      previousSample = null
      screenTrackRef.current = null
      roomRef.current = null
      try {
        room?.disconnect()
      } catch {
        // Best-effort teardown.
      }
    }
  }, [attachScreenTrack, media, participantId, rejoinNonce, role, sessionId, token])

  // ── Session control WebSocket ────────────────────────────────────────────
  React.useEffect(() => {
    if (!token || !sessionId || !participantId || !wsTicket) return
    let cancelled = false
    let cursor: string | null = null
    let ticketUsed = false
    let reconnectTimer: number | null = null
    let pingTimer: number | null = null

    const scheduleReconnect = () => {
      if (cancelled || reconnectTimer !== null) return
      reconnectTimer = window.setTimeout(() => {
        reconnectTimer = null
        void connect()
      }, WS_RECONNECT_DELAY_MS)
    }

    const connect = async () => {
      if (cancelled) return
      let ticket = wsTicket
      if (ticketUsed) {
        // Tickets are one-shot; every re-connect needs a fresh one plus the
        // last cursor to resume the event stream.
        try {
          const fresh = await remoteDesktopApi.createTicket(token, sessionId, { participantId })
          ticket = fresh.ticket
        } catch {
          scheduleReconnect()
          return
        }
      }
      if (cancelled) return
      ticketUsed = true
      const socket = new WebSocket(remoteDesktopApi.sessionWsUrl(sessionId, ticket, cursor))
      wsRef.current = socket

      socket.onmessage = (event) => {
        if (cancelled || typeof event.data !== "string") return
        let message: { type?: unknown; payload?: unknown }
        try {
          message = JSON.parse(event.data) as { type?: unknown; payload?: unknown }
        } catch {
          return
        }
        const type = typeof message.type === "string" ? message.type : ""
        const payload = (message.payload ?? {}) as Record<string, unknown> & {
          sessionId?: unknown
          cursor?: unknown
          session?: unknown
          stats?: unknown
          at?: unknown
          reason?: unknown
          endReason?: unknown
          endedAt?: unknown
          code?: unknown
          message?: unknown
          retryable?: unknown
        }
        switch (type) {
          case "remote_desktop.subscribed": {
            if (typeof payload.cursor === "string") {
              // Empty string = no history replayed; keep any earlier cursor so
              // the next reconnect still resumes from the newest known point.
              cursor = payload.cursor || cursor
            }
            break
          }
          case "remote_desktop.session.updated": {
            if (payload.session) callbacksRef.current.onSessionUpdated?.(payload.session as RemoteDesktopSession)
            break
          }
          case "remote_desktop.stats.updated": {
            const server = payload.stats as RemoteDesktopStreamStats | undefined
            if (server) {
              setStats((current) => ({ ...current, ...server }))
            }
            break
          }
          case "remote_desktop.control.revoked": {
            const reason = payload.reason
            if (reason === "taken_over" || reason === "lease_expired" || reason === "policy_revoked") {
              callbacksRef.current.onControlRevoked?.(reason)
            }
            break
          }
          case "remote_desktop.ended": {
            if (typeof payload.endReason === "string" && typeof payload.endedAt === "string") {
              callbacksRef.current.onEnded?.(payload.endReason, payload.endedAt)
            }
            setState("ended")
            break
          }
          case "remote_desktop.error": {
            callbacksRef.current.onError?.(
              typeof payload.code === "string" ? payload.code : "unknown",
              typeof payload.message === "string" ? payload.message : "",
              payload.retryable === true,
            )
            break
          }
          default:
            // Tolerate unknown event types (contract allows forward additions).
            break
        }
      }
      socket.onclose = () => {
        if (cancelled) return
        if (wsRef.current === socket) wsRef.current = null
        scheduleReconnect()
      }
      socket.onerror = () => {
        socket.close()
      }
    }

    void connect()
    pingTimer = window.setInterval(() => {
      const socket = wsRef.current
      if (socket && socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({ type: "ping", at: Date.now() } satisfies RemoteDesktopClientWsMessage))
      }
    }, WS_PING_INTERVAL_MS)

    return () => {
      cancelled = true
      if (reconnectTimer !== null) window.clearTimeout(reconnectTimer)
      if (pingTimer !== null) window.clearInterval(pingTimer)
      const socket = wsRef.current
      wsRef.current = null
      socket?.close()
    }
  }, [participantId, sessionId, token, wsTicket])

  const reconnect = React.useCallback(() => {
    // Refresh the grant first, then bump `rejoinNonce` so the media effect
    // re-joins with a token that is not already near expiry.
    if (!token || !sessionId || !participantId) return
    setState("connecting")
    void (async () => {
      try {
        const refreshed = await remoteDesktopApi.refreshMediaToken(token, sessionId, {
          participantId,
          role,
        })
        grantRef.current = refreshed
      } catch {
        // Keep the old grant; the re-join error will surface as `failed`.
      }
      if (grantRef.current) setRejoinNonce((current) => current + 1)
    })()
  }, [participantId, role, sessionId, token])

  return {
    state,
    stats,
    videoRef,
    sendInput,
    sendPointerFrame,
    requestKeyframe,
    updateQuality,
    ping,
    reconnect,
  }
}

// Re-exported for callers that merge server stats with local samples.
export type { VideoReceiverStats }

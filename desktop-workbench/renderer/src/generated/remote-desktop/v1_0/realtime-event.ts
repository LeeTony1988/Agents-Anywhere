/* eslint-disable */
/**
 * Generated from contract 1.0 artifact realtime-event.
 * Do not edit by hand; run `yarn protocol:generate`.
 */

/**
 * Server -> client control-plane event on WS /api/v2/remote-desktop/sessions/{sessionId}/ws. Every event is {type,payload}; every payload carries sessionId. Payload objects intentionally allow unknown fields so clients can tolerate forward-compatible additions.
 */
export type RemoteDesktopRealtimeEvent = {
  type: string
  payload: {
    [k: string]: unknown
  }
} & (
  | {
      type: "remote_desktop.subscribed"
      payload: {
        sessionId: string
        /**
         * Decimal string with the highest event sequence already delivered; an empty string means no history is being replayed.
         */
        cursor: string
        [k: string]: unknown
      }
    }
  | {
      type: "remote_desktop.session.updated"
      payload: {
        sessionId: string
        session: RemoteDesktopSession
        [k: string]: unknown
      }
    }
  | {
      type: "remote_desktop.stats.updated"
      payload: {
        sessionId: string
        stats: {
          fps: number
          bitrateKbps: number
          rttMs: number
          jitterMs: number
          packetLossPct: number
          codec: "vp8"
          width: number
          height: number
          encoder: string
          subscribers: number
        }
        at: string
        [k: string]: unknown
      }
    }
  | {
      type: "remote_desktop.control.revoked"
      payload: {
        sessionId: string
        reason: "taken_over" | "lease_expired" | "policy_revoked"
        [k: string]: unknown
      }
    }
  | {
      type: "remote_desktop.ended"
      payload: {
        sessionId: string
        /**
         * Terminal endReason set from docs/remote-desktop-decisions.md section 3, including duration_limit. session.schema.json must be updated to carry the same set.
         */
        endReason:
          | "client_ended"
          | "host_ended"
          | "consent_denied"
          | "consent_timeout"
          | "idle_timeout"
          | "duration_limit"
          | "connector_offline"
          | "media_error"
          | "policy_revoked"
          | "connector_deleted"
        endedAt: string
        [k: string]: unknown
      }
    }
  | {
      type: "remote_desktop.error"
      payload: {
        sessionId: string
        code: string
        message: string
        retryable: boolean
        details?: {
          [k: string]: unknown
        } | null
        [k: string]: unknown
      }
    }
)

export interface RemoteDesktopSession {
  id: string
  connectorId: string
  mode: "view" | "control"
  displayId?: string | null
  state: "starting" | "awaiting_consent" | "live" | "degraded" | "interrupted" | "ended"
  endReason?:
    | (
        | "client_ended"
        | "host_ended"
        | "consent_denied"
        | "consent_timeout"
        | "idle_timeout"
        | "duration_limit"
        | "connector_deleted"
        | "connector_offline"
        | "media_error"
        | "policy_revoked"
      )
    | null
  controller?: string | null
  controllerLeaseExpiresAt?: string | null
  linkedSessionId?: string | null
  media?: {
    provider: "livekit"
    room: string
    url: string
    [k: string]: unknown
  } | null
  quality: {
    maxFps?: number
    maxBitrateKbps?: number
    maxWidth?: number
    preferCodec?: "vp8"
    [k: string]: unknown
  }
  stats?: {
    fps: number
    bitrateKbps: number
    rttMs: number
    jitterMs: number
    packetLossPct: number
    codec: "vp8"
    width: number
    height: number
    encoder: string
    subscribers: number
    [k: string]: unknown
  } | null
  error?: {
    code: string
    message: string
    retryable?: boolean
    [k: string]: unknown
  } | null
  viewerCount: number
  createdAt: string
  startedAt?: string | null
  endedAt?: string | null
  [k: string]: unknown
}

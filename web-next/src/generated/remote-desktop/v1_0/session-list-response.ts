/* eslint-disable */
/**
 * Generated from contract 1.0 artifact session-list-response.
 * Do not edit by hand; run `yarn protocol:generate`.
 */

export interface RemoteDesktopSessionListResponse {
  sessions: RemoteDesktopSession[]
  nextCursor: string | null
  [k: string]: unknown
}
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

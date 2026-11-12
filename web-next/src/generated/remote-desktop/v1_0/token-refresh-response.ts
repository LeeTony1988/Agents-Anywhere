/* eslint-disable */
/**
 * Generated from contract 1.0 artifact token-refresh-response.
 * Do not edit by hand; run `yarn protocol:generate`.
 */

/**
 * Short-lived LiveKit join grant. The token is never persisted server-side.
 */
export interface RemoteDesktopTokenRefreshResponse {
  provider: "livekit"
  url: string
  room: string
  identity: string
  token: string
  tokenExpiresAt: string
  canPublishData: boolean
  iceServers?: {
    urls: string[]
    username?: string
    credential?: string
    [k: string]: unknown
  }[]
  [k: string]: unknown
}

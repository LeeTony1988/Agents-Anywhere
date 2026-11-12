/* eslint-disable */
/**
 * Generated from contract 1.0 artifact session-create-request.
 * Do not edit by hand; run `yarn protocol:generate`.
 */

export interface RemoteDesktopSessionCreateRequest {
  connectorId: string
  displayId?: string | null
  mode: "view" | "control"
  quality?: {
    maxFps?: number
    maxBitrateKbps?: number
    maxWidth?: number
    preferCodec?: "vp8"
  }
  client?: {
    platform: "web" | "desktop" | "android" | "ios"
    appVersion?: string
    viewport?: {
      width: number
      height: number
      dpr?: number
    }
  }
  link?: {
    sessionId: string
  }
}

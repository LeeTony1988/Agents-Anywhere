/* eslint-disable */
/**
 * Generated from contract 1.0 artifact capability.
 * Do not edit by hand; run `yarn protocol:generate`.
 */

export interface RemoteDesktopCapability {
  capabilityId:
    "connector.remote_desktop.view" | "connector.remote_desktop.control" | "connector.remote_desktop.clipboard"
  scope: "connector"
  connectorId: string
  supported: boolean
  available: boolean
  allowed: boolean
  unavailableReason:
    | (
        | "connector_offline"
        | "host_not_running"
        | "host_not_installed"
        | "connector_outdated"
        | "unsupported_platform"
        | "screen_permission_denied"
        | "accessibility_permission_denied"
        | "device_policy_disabled"
        | "session_limit_reached"
        | "media_not_configured"
      )
    | null
  revision: number
  metadata: {
    [k: string]: unknown
  }
  [k: string]: unknown
}

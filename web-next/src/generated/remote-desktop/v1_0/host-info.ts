/* eslint-disable */
/**
 * Generated from contract 1.0 artifact host-info.
 * Do not edit by hand; run `yarn protocol:generate`.
 */

/**
 * GET /api/v2/connectors/{connectorId}/remote-desktop. Capabilities are Server policy; host facts are the Connector's report.
 */
export interface RemoteDesktopHostInfo {
  connectorId: string
  /**
   * @minItems 1
   * @maxItems 3
   */
  capabilities:
    | [RemoteDesktopCapability]
    | [RemoteDesktopCapability, RemoteDesktopCapability]
    | [RemoteDesktopCapability, RemoteDesktopCapability, RemoteDesktopCapability]
  host: RemoteDesktopHostFacts | null
  serverTime: string
  [k: string]: unknown
}
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
/**
 * Facts reported by aa-screen-host through the Connector. `allowed` is Server policy and never comes from the host.
 */
export interface RemoteDesktopHostFacts {
  supported: boolean
  available: boolean
  unavailableReason?:
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
  platform: "windows" | "macos" | "linux" | "unknown"
  arch: "x86_64" | "aarch64" | "unknown"
  hostVersion: string | null
  /**
   * @maxItems 16
   */
  displays:
    | []
    | [
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
      ]
    | [
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
      ]
    | [
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
      ]
    | [
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
      ]
    | [
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
      ]
    | [
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
      ]
    | [
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
      ]
    | [
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
      ]
    | [
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
      ]
    | [
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
      ]
    | [
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
      ]
    | [
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
      ]
    | [
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
      ]
    | [
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
      ]
    | [
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
      ]
    | [
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
        {
          id: string
          name: string
          width: number
          height: number
          scale: number
          primary: boolean
          refreshHz?: number | null
          x?: number
          y?: number
          [k: string]: unknown
        },
      ]
  /**
   * @maxItems 4
   */
  codecs: [] | ["vp8"] | ["vp8", "vp8"] | ["vp8", "vp8", "vp8"] | ["vp8", "vp8", "vp8", "vp8"]
  maxFps: number
  input: {
    pointer: boolean
    keyboard: boolean
    touch: boolean
    clipboard: boolean
    [k: string]: unknown
  }
  permissions: {
    screenRecording: "granted" | "denied" | "not_determined" | "unsupported"
    accessibility: "granted" | "denied" | "not_determined" | "unsupported"
    inputMonitoring: "granted" | "denied" | "not_determined" | "unsupported"
    [k: string]: unknown
  }
  activeSessions: number
  [k: string]: unknown
}

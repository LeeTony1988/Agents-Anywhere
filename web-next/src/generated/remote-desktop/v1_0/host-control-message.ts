/* eslint-disable */
/**
 * Generated from contract 1.0 artifact host-control-message.
 * Do not edit by hand; run `yarn protocol:generate`.
 */

/**
 * One NDJSON frame on the bidirectional stdio control channel between the Connector and aa-screen-host. Encoding is UTF-8; every line is exactly one JSON object; stdout carries protocol frames only (logs go to stderr); a single frame must not exceed 4 MiB (4194304 bytes).
 */
export type RemoteDesktopHostControlMessage =
  | {
      v: 1
      type: "hello"
      protocolVersion: 1
      hostVersion: string
      platform: "windows" | "macos" | "linux" | "unknown"
      arch: "x86_64" | "aarch64" | "unknown"
      pid: number
      capabilities: {
        [k: string]: unknown
      }
    }
  | {
      v: 1
      type: "request"
      id: number
      method:
        | "host.info"
        | "host.start"
        | "host.stop"
        | "host.updateQuality"
        | "host.controlGrant"
        | "host.requestKeyframe"
        | "host.releaseAll"
        | "host.screenshot"
        | "host.input"
        | "host.ping"
      params: {
        [k: string]: unknown
      }
      deadlineMs?: number
    }
  | {
      v: 1
      type: "response"
      id: number
      ok: boolean
      result?: {
        [k: string]: unknown
      }
      error?: {
        code: string
        message: string
      }
    }
  | {
      v: 1
      type: "event"
      event: "state" | "stats" | "displays" | "consent" | "clipboard" | "error"
      params: {
        [k: string]: unknown
      }
    }
  | {
      v: 1
      type: "error"
      error: {
        code: string
        message: string
        retryable?: boolean
      }
    }
  | {
      v: 1
      type: "shutdown"
      reason?: string
    }

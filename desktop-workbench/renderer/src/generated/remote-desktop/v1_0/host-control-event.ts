/* eslint-disable */
/**
 * Generated from contract 1.0 artifact host-control-event.
 * Do not edit by hand; run `yarn protocol:generate`.
 */

/**
 * Host -> client control event. Acknowledgements and rejects are sent on the reliable LiveKit topic aa.input.ack; clipboard offers and denials are sent on aa.host.control. Every object is closed (additionalProperties:false).
 */
export type RemoteDesktopHostControlEvent =
  | {
      v: 1
      t: "ack"
      ack: number
    }
  | {
      v: 1
      t: "reject"
      code: "not_controller" | "rate_limited" | "invalid_event" | "unsupported" | "policy"
      seq?: number
    }
  | (
      | {
          v: 1
          t: "clipboard"
          a: "offer"
          mime: "text/plain"
          size: number
          dataBase64: string
        }
      | {
          v: 1
          t: "clipboard"
          a: "denied"
          reason: "policy" | "too_large" | "unsupported"
        }
    )
  | RemoteDesktopClipboardTransfer
/**
 * Chunked text clipboard transfer used when the payload exceeds one reliable LiveKit data packet. A 64 KiB UTF-8 text is split into at most 8 chunks of at most 8 KiB raw bytes; each chunk is base64 encoded and stays under the 15 KiB reliable packet budget.
 */
export type RemoteDesktopClipboardTransfer = {
  [k: string]: unknown
} & {
  v: 1
  t: "clipboard_transfer"
  a: "begin" | "chunk" | "end" | "ack" | "cancel"
  transferId: string
  mime?: "text/plain"
  size?: number
  totalChunks?: number
  index?: number
  dataBase64?: string
  sha256?: string
  reason?: "timeout" | "cancelled" | "policy"
}

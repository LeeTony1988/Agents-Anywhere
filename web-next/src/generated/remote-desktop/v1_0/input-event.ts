/* eslint-disable */
/**
 * Generated from contract 1.0 artifact input-event.
 * Do not edit by hand; run `yarn protocol:generate`.
 */

/**
 * Client -> host input event carried as JSON on the reliable LiveKit data topic aa.input. High-rate pointer motion uses the 12-byte binary aa.pointer frame documented in pointer-frame.md instead. Host -> client acknowledgements, rejects and clipboard responses are defined by host-control-event.schema.json; this schema has no ack or denied branch.
 */
export type RemoteDesktopInputEvent =
  | {
      [k: string]: unknown
    }
  | {
      v: 1
      seq: number
      ts?: number
      t: "wheel"
      dx: number
      dy: number
      unit?: "pixel" | "line"
      d?: number
    }
  | {
      v: 1
      seq: number
      ts?: number
      t: "key"
      a: "down" | "up"
      /**
       * W3C UI Events KeyboardEvent.code, e.g. KeyA, Digit1, Escape, ControlLeft.
       */
      code: string
      mods?: ("ctrl" | "alt" | "shift" | "meta")[]
      repeat?: boolean
    }
  | {
      v: 1
      seq: number
      ts?: number
      t: "text"
      s: string
    }
  | {
      v: 1
      t: "ping"
      at: number
    }
  | {
      v: 1
      seq?: number
      t: "release_all"
      reason: "blur" | "pagehide" | "handoff" | "manual"
    }
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

/* eslint-disable */
/**
 * Generated from contract 1.0 artifact clipboard-transfer.
 * Do not edit by hand; run `yarn protocol:generate`.
 */

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

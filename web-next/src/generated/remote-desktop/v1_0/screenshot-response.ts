/* eslint-disable */
/**
 * Generated from contract 1.0 artifact screenshot-response.
 * Do not edit by hand; run `yarn protocol:generate`.
 */

export interface RemoteDesktopScreenshotResponse {
  width: number
  height: number
  capturedAt: string
  dataBase64: string
  /**
   * Actual image type of dataBase64. The request's `format` is only a hint: the host may fall back to PNG.
   */
  format?: "jpeg" | "png"
  [k: string]: unknown
}

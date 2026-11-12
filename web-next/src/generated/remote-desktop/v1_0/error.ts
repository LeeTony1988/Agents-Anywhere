/* eslint-disable */
/**
 * Generated from contract 1.0 artifact error.
 * Do not edit by hand; run `yarn protocol:generate`.
 */

/**
 * HTTP error envelope. `rd_*` codes are Connector-internal and must never reach clients.
 */
export interface RemoteDesktopErrorResponse {
  detail: {
    code: string
    message: string
    details?: {
      [k: string]: unknown
    } | null
    [k: string]: unknown
  }
  [k: string]: unknown
}

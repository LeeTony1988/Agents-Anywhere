/* eslint-disable */
/**
 * Generated from contract 1.0 artifact client-ws-message.
 * Do not edit by hand; run `yarn protocol:generate`.
 */

/**
 * Client -> Server control-plane frame on the remote-desktop WebSocket. Media and input never travel on this socket.
 */
export type RemoteDesktopClientWsMessage =
  | {
      type: "ping"
      at: number
    }
  | {
      type: "request_keyframe"
    }
  | {
      type: "update_quality"
      maxFps?: number
      maxBitrateKbps?: number
      maxWidth?: number
    }

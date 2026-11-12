import { ApiClient, apiClient, apiPath } from "@/lib/api/client";
import { isApiError } from "@/lib/api/errors";
import type {
  RemoteDesktopHostInfo,
} from "@/generated/remote-desktop/v1_0/host-info";
import type {
  RemoteDesktopSessionCreateRequest,
} from "@/generated/remote-desktop/v1_0/session-create-request";
import type {
  RemoteDesktopSessionCreateResponse,
} from "@/generated/remote-desktop/v1_0/session-create-response";
import type {
  RemoteDesktopSessionListResponse,
} from "@/generated/remote-desktop/v1_0/session-list-response";
import type {
  RemoteDesktopSessionResponse,
} from "@/generated/remote-desktop/v1_0/session-response";
import type { RemoteDesktopSession } from "@/generated/remote-desktop/v1_0/session";
import type {
  RemoteDesktopTokenRefreshRequest,
} from "@/generated/remote-desktop/v1_0/token-refresh-request";
import type {
  RemoteDesktopTokenRefreshResponse,
} from "@/generated/remote-desktop/v1_0/token-refresh-response";
import type {
  RemoteDesktopTakeControlRequest,
} from "@/generated/remote-desktop/v1_0/take-control-request";
import type {
  RemoteDesktopTakeControlResponse,
} from "@/generated/remote-desktop/v1_0/take-control-response";
import type {
  RemoteDesktopReleaseControlRequest,
} from "@/generated/remote-desktop/v1_0/release-control-request";
import type {
  RemoteDesktopHeartbeatRequest,
} from "@/generated/remote-desktop/v1_0/heartbeat-request";
import type {
  RemoteDesktopEndRequest,
} from "@/generated/remote-desktop/v1_0/end-request";
import type {
  RemoteDesktopTicketRequest,
} from "@/generated/remote-desktop/v1_0/ticket-request";
import type {
  RemoteDesktopTicketResponse,
} from "@/generated/remote-desktop/v1_0/ticket-response";
import type {
  RemoteDesktopScreenshotRequest,
} from "@/generated/remote-desktop/v1_0/screenshot-request";
import type {
  RemoteDesktopScreenshotResponse,
} from "@/generated/remote-desktop/v1_0/screenshot-response";

/**
 * Remote-desktop HTTP surface (`/api/v2/remote-desktop/*` plus the per-connector
 * capability endpoint). Wire shapes come from `src/generated/remote-desktop/v1_0`,
 * generated from `contracts/remote-desktop/1.0`; never hand-edit those files.
 *
 * All calls go through `apiClient` so auth headers and the error envelope stay
 * consistent with the rest of the app — do not `fetch` from this module.
 */

export type RemoteDesktopListQuery = {
  connectorId?: string;
  state?: RemoteDesktopSession["state"];
  limit?: number;
  cursor?: string;
};

/**
 * The generated request shapes are `interface`s (no implicit index signature),
 * while the ApiClient body accepts plain JSON records; spreading produces a
 * fresh object literal type that is assignable. The wire JSON is unchanged.
 */
function jsonBody<T extends object>(body: T): Record<string, unknown> {
  return Object.fromEntries(Object.entries(body));
}

export class RemoteDesktopApi {
  constructor(private readonly client: ApiClient = apiClient) {}

  /** `GET /connectors/{connectorId}/remote-desktop` — three capabilities + host facts. */
  getHostInfo(token: string, connectorId: string): Promise<RemoteDesktopHostInfo> {
    return this.client.get<RemoteDesktopHostInfo>(
      `/connectors/${encodeURIComponent(connectorId)}/remote-desktop`,
      { token },
    );
  }

  /** `POST /remote-desktop/sessions` — create-or-join; `mode=control` may await consent. */
  createSession(
    token: string,
    body: RemoteDesktopSessionCreateRequest,
  ): Promise<RemoteDesktopSessionCreateResponse> {
    return this.client.post<RemoteDesktopSessionCreateResponse>(
      "/remote-desktop/sessions",
      jsonBody(body),
      { token },
    );
  }

  getSession(token: string, sessionId: string): Promise<RemoteDesktopSessionResponse> {
    return this.client.get<RemoteDesktopSessionResponse>(
      `/remote-desktop/sessions/${encodeURIComponent(sessionId)}`,
      { token },
    );
  }

  listSessions(token: string, query: RemoteDesktopListQuery = {}): Promise<RemoteDesktopSessionListResponse> {
    return this.client.get<RemoteDesktopSessionListResponse>(
      "/remote-desktop/sessions",
      {
        token,
        query: {
          connectorId: query.connectorId,
          state: query.state,
          limit: query.limit,
          cursor: query.cursor,
        },
      },
    );
  }

  /** `POST /sessions/{id}/token` — short-TTL LiveKit grant (viewer 600 s, controller 120 s). */
  refreshMediaToken(
    token: string,
    sessionId: string,
    body: RemoteDesktopTokenRefreshRequest,
  ): Promise<RemoteDesktopTokenRefreshResponse> {
    return this.client.post<RemoteDesktopTokenRefreshResponse>(
      `/remote-desktop/sessions/${encodeURIComponent(sessionId)}/token`,
      jsonBody(body),
      { token },
    );
  }

  /** `POST /sessions/{id}/take-control` — 202 while awaiting consent, 200 on takeover. */
  takeControl(
    token: string,
    sessionId: string,
    body: RemoteDesktopTakeControlRequest,
  ): Promise<RemoteDesktopTakeControlResponse> {
    return this.client.post<RemoteDesktopTakeControlResponse>(
      `/remote-desktop/sessions/${encodeURIComponent(sessionId)}/take-control`,
      jsonBody(body),
      { token },
    );
  }

  releaseControl(
    token: string,
    sessionId: string,
    body: RemoteDesktopReleaseControlRequest = {},
  ): Promise<RemoteDesktopSessionResponse> {
    return this.client.post<RemoteDesktopSessionResponse>(
      `/remote-desktop/sessions/${encodeURIComponent(sessionId)}/release-control`,
      jsonBody(body),
      { token },
    );
  }

  /** Controller lease renewal (60 s lease; call every 20 s while holding control). */
  heartbeat(
    token: string,
    sessionId: string,
    body: RemoteDesktopHeartbeatRequest,
  ): Promise<RemoteDesktopSessionResponse> {
    return this.client.post<RemoteDesktopSessionResponse>(
      `/remote-desktop/sessions/${encodeURIComponent(sessionId)}/heartbeat`,
      jsonBody(body),
      { token },
    );
  }

  /** Idempotent; fills `state="ended"`, `endReason`, `endedAt`. */
  endSession(
    token: string,
    sessionId: string,
    body: RemoteDesktopEndRequest = { reason: "client_ended" },
  ): Promise<RemoteDesktopSessionResponse> {
    return this.client.post<RemoteDesktopSessionResponse>(
      `/remote-desktop/sessions/${encodeURIComponent(sessionId)}/end`,
      jsonBody(body),
      { token },
    );
  }

  /** One-shot WS ticket (60 s TTL); reconnects must request a fresh one. */
  createTicket(
    token: string,
    sessionId: string,
    body: RemoteDesktopTicketRequest,
  ): Promise<RemoteDesktopTicketResponse> {
    return this.client.post<RemoteDesktopTicketResponse>(
      `/remote-desktop/sessions/${encodeURIComponent(sessionId)}/ticket`,
      jsonBody(body),
      { token },
    );
  }

  screenshot(
    token: string,
    sessionId: string,
    body: RemoteDesktopScreenshotRequest,
  ): Promise<RemoteDesktopScreenshotResponse> {
    return this.client.post<RemoteDesktopScreenshotResponse>(
      `/remote-desktop/sessions/${encodeURIComponent(sessionId)}/screenshot`,
      jsonBody(body),
      { token },
    );
  }

  /**
   * Session WebSocket URL: `WS /remote-desktop/sessions/{id}/ws?ticket=…[&cursor=…]`.
   * The cursor resumes the event stream after a drop (server replays up to
   * 256 events / 90 s, otherwise a fresh snapshot).
   */
  sessionWsUrl(sessionId: string, ticket: string, cursor?: string | null): string {
    const params = new URLSearchParams({ ticket });
    if (cursor) params.set("cursor", cursor);
    const path = `${apiPath(`/remote-desktop/sessions/${encodeURIComponent(sessionId)}/ws`)}?${params.toString()}`;
    if (typeof window === "undefined") return path;
    const url = new URL(path, window.location.origin);
    url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
    return url.toString();
  }
}

export const remoteDesktopApi = new RemoteDesktopApi();

// ─── Error presentation ─────────────────────────────────────────────────────
//
// The server error envelope is `{detail: {code, message, details?}}`; `ApiError`
// already extracts `code` and the message. This table maps every stable
// `remote_desktop_*` / `media_*` code (docs/api/remote-desktop.md §错误格式) to the
// copy shown in the UI, so the component layer never has to guess.

export const REMOTE_DESKTOP_ERROR_MESSAGES: Record<string, string> = {
  remote_desktop_not_found: "远程桌面会话不存在或已结束。",
  connector_not_found: "找不到这台设备，或它不属于当前账号。",
  remote_desktop_unavailable: "这台设备暂时无法使用远程桌面。",
  remote_desktop_busy: "控制权正被其他窗口占用，请稍后再试。",
  remote_desktop_limit_reached: "远程桌面会话数已达上限，请稍后再试。",
  remote_desktop_consent_pending: "还在等待设备确认，暂时无法获取控制凭据。",
  remote_desktop_rate_limited: "操作太频繁，请稍后再试。",
  media_unavailable: "媒体服务暂时不可用，请稍后再试。",
};

/** Finer copy for `remote_desktop_unavailable` via `details.reason`. */
const UNAVAILABLE_REASON_MESSAGES: Record<string, string> = {
  connector_offline: "设备离线，暂时无法使用远程桌面。",
  host_not_running: "设备上的远程桌面组件未运行，请在设备上启动后重试。",
  host_not_installed: "设备上没有安装远程桌面组件。",
  connector_outdated: "设备上的连接器版本过旧，请升级后再试。",
  unsupported_platform: "设备的系统平台暂不支持远程桌面。",
  screen_permission_denied: "设备缺少屏幕录制权限，请在设备上授权后重试。",
  accessibility_permission_denied: "设备缺少辅助功能权限，请在设备上授权后重试。",
  device_policy_disabled: "设备策略禁用了远程桌面。",
  session_limit_reached: "这台设备的远程桌面会话数已达上限。",
  media_not_configured: "服务端未配置媒体服务，无法建立画面连接。",
};

function unavailableReason(payload: unknown): string | undefined {
  if (!payload || typeof payload !== "object") return undefined;
  const detail = (payload as { detail?: unknown }).detail;
  if (!detail || typeof detail !== "object") return undefined;
  const details = (detail as { details?: unknown }).details;
  if (!details || typeof details !== "object") return undefined;
  const reason = (details as { reason?: unknown }).reason;
  return typeof reason === "string" ? reason : undefined;
}

/**
 * UI copy for a failed remote-desktop call. Known `ApiError` codes use the
 * table above (with `details.reason` refinement where the server provides one);
 * anything else falls back to the server message, then to `fallback`.
 */
export function remoteDesktopErrorMessage(
  error: unknown,
  fallback = "远程桌面请求失败，请稍后再试。",
): string {
  if (!isApiError(error)) {
    if (error instanceof Error && error.message) return error.message;
    return fallback;
  }
  const code = error.code;
  if (code === "remote_desktop_unavailable") {
    const reason = unavailableReason(error.payload);
    if (reason && UNAVAILABLE_REASON_MESSAGES[reason]) return UNAVAILABLE_REASON_MESSAGES[reason];
  }
  if (code && REMOTE_DESKTOP_ERROR_MESSAGES[code]) return REMOTE_DESKTOP_ERROR_MESSAGES[code];
  return error.detail || fallback;
}

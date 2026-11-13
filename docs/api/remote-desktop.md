# 远程桌面 API

远程桌面是 **设备能力**，不是 Runtime 能力。接口挂在 `/api/v2` 下，wire 契约以
[`contracts/remote-desktop/1.0`](../../contracts/remote-desktop/1.0/README.md) 的 schema 为准；
本文只写语义、状态与调用顺序。架构与选型见[远程桌面架构方案](../remote-desktop.md)。

## 能力与开关

远程桌面默认关闭。设备设置（见[设备设置](device-settings.md)）里的 7 个字段决定能力：

| 字段 | 默认 | 含义 |
| --- | --- | --- |
| `remoteDesktopEnabled` | `false` | 是否允许该设备被远程查看 |
| `remoteDesktopAllowControl` | `false` | 是否允许远程控制 |
| `remoteDesktopRequireConsent` | `true` | 控制会话是否必须在本机确认 |
| `remoteDesktopAllowUnattended` | `false` | 无人值守时是否允许控制 |
| `remoteDesktopClipboard` | `false` | 是否允许剪贴板同步 |
| `remoteDesktopMaxFps` | `30` | 帧率上限（5–60） |
| `remoteDesktopMaxBitrateKbps` | `4000` | 码率上限（500–20000） |

读取设备能力：

```text
GET /api/v2/connectors/{connectorId}/remote-desktop
```

响应里的 `capabilities` 是 Server 组合后的策略结果，`host` 是 Connector 上报的 HostFacts
（设备离线或 host 未安装时为 `null`）。三个能力 id：

```text
connector.remote_desktop.view
connector.remote_desktop.control
connector.remote_desktop.clipboard
```

`supported=false` 表示实现/平台不支持；`available=false` 表示设备离线、host 未运行或系统权限缺失；
`allowed=false` 表示设备设置或账号策略禁止。`revision` 单调递增，客户端用它丢弃过期缓存。
入口按能力隐藏：任一为 false 时不显示远程桌面按钮。

## 创建或加入会话

```text
POST /api/v2/remote-desktop/sessions
```

```json
{
  "connectorId": "conn_x",
  "displayId": "0",
  "mode": "control",
  "quality": {"maxFps": 30, "maxBitrateKbps": 4000, "maxWidth": 1920},
  "client": {"platform": "web", "appVersion": "2.3.0", "viewport": {"width": 1440, "height": 900}},
  "link": {"sessionId": "sess_abc"}
}
```

- 每个 Connector 同时最多一个未结束会话。已有活跃会话时，本接口是 **加入**：返回同一个
  `session.id`、新的 `participantId`；`mode=control` 按同一账号的 takeover 语义抢占控制权。
- `mode=view`：只订阅画面。`mode=control`：申请控制；若设备要求确认，返回
  `state=awaiting_consent`、`media=null`，此时**不会下发媒体 token**。
- 成功返回 `201`（新建）或 `200`（加入）：

```json
{
  "session": {"id": "rds_01JABCDEF", "state": "starting", "...": "..."},
  "participantId": "pt_9f2c1a7b3d4e5f60718293a4b5c6d7e8",
  "media": null,
  "ws": {"url": "/api/v2/remote-desktop/sessions/rds_01JABCDEF/ws", "ticket": "wst_...", "expiresAt": "2026-11-13T00:10:00Z"}
}
```

`media` 非空时包含 `provider/url/room/identity/token/tokenExpiresAt/canPublishData`。客户端用
`participantId` 关联本客户端的后续请求；服务端不接受客户端自报其它身份。

错误：

| HTTP | code | 场景 |
| --- | --- | --- |
| 404 | `remote_desktop_not_found` | 会话不存在、不属于当前用户 |
| 404 | `connector_not_found` | 设备不存在或不属于当前用户 |
| 409 | `remote_desktop_unavailable` | 能力不可用，`details.reason` 带原因 |
| 409 | `remote_desktop_busy` | 控制权被同账号其他 participant 持有且无法抢占 |
| 429 | `remote_desktop_limit_reached` | 单会话观看者上限或每用户会话上限 |
| 503 | `media_unavailable` | LiveKit 未配置或不可达 |

## 查询与列表

```text
GET /api/v2/remote-desktop/sessions/{sessionId}
GET /api/v2/remote-desktop/sessions?connectorId=conn_x&state=live&limit=50&cursor=rds_...
```

返回 `{session}` 或 `{sessions, nextCursor}`。列表按创建时间倒序，`cursor` 是不透明字符串。
响应里的 `media` 只是 `{provider, room, url}` 引用，**永远不含 token**。

## 媒体 token 续签

```text
POST /api/v2/remote-desktop/sessions/{sessionId}/token
{"participantId": "pt_...", "role": "viewer"}
```

```json
{
  "provider": "livekit",
  "url": "wss://rtc.example.com",
  "room": "rd_rds_01JABCDEF",
  "identity": "viewer:pt_9f2c...",
  "token": "eyJ...",
  "tokenExpiresAt": "2026-11-13T00:10:00Z",
  "canPublishData": false
}
```

- `role=viewer`：`canPublish=false`、`canSubscribe=true`、`canPublishData=false`。
- `role=controller`：只有当前 controller 可取；`canPublishData=true`。会话还在
  `awaiting_consent` 时返回 `409 remote_desktop_consent_pending`。
- viewer token TTL 600 s，controller token TTL 120 s；客户端在剩余 120 s 时续签。LiveKit 只在
  join 时校验 token，过期后重连需要新 token。

## 控制权

```text
POST /api/v2/remote-desktop/sessions/{sessionId}/take-control
{"participantId": "pt_..."}
```

- 会话在 `awaiting_consent`：返回 `202 {session}`，不签发 token；同一 participant 重复调用幂等，
  不会重复弹窗。
- 会话 `live/degraded`：抢占成功返回 `200 {session}`。Server 会：
  1. 把旧 controller 的 LiveKit 权限改为 `canPublishData=false`（`UpdateParticipant`）；
  2. 让 host 把输入白名单切到新 identity（`remoteDesktop.control.grant`，epoch+1）；
  3. 向旧 controller 发送 `remote_desktop.control.revoked {reason:"taken_over"}`。
- LiveKit 不支持 JWT 吊销，旧 token 在 TTL 内仍可订阅，但发不出数据包；host 也会丢弃旧 epoch 输入。

```text
POST /api/v2/remote-desktop/sessions/{sessionId}/release-control
{}
```

当前 controller 主动释放，会话继续；controller 变为 `null`。

```text
POST /api/v2/remote-desktop/sessions/{sessionId}/heartbeat
{"participantId": "pt_..."}
```

只有当前 controller 可以续租；租约 60 s，客户端每 20 s 调用一次（或由 WS 心跳隐式续租）。

## 结束

```text
POST /api/v2/remote-desktop/sessions/{sessionId}/end
{"reason": "client_ended"}
```

幂等。返回 `{session}`，其中 `state="ended"`、`endReason`、`endedAt` 已填写。

## 会话 WebSocket

```text
WS /api/v2/remote-desktop/sessions/{sessionId}/ws?ticket=wst_...
```

ticket 由创建响应的 `ws.ticket` 或 `POST /sessions/{id}/ticket` 签发，一次性、60 s。续签：

```text
POST /api/v2/remote-desktop/sessions/{sessionId}/ticket
{"participantId": "pt_..."}
```

连接后服务端先发 `remote_desktop.subscribed {sessionId, cursor}`，然后推送六类事件：

| type | payload |
| --- | --- |
| `remote_desktop.subscribed` | `{sessionId, cursor}` |
| `remote_desktop.session.updated` | `{sessionId, session}` |
| `remote_desktop.stats.updated` | `{sessionId, stats, at}` |
| `remote_desktop.control.revoked` | `{sessionId, reason}` |
| `remote_desktop.ended` | `{sessionId, endReason, endedAt}` |
| `remote_desktop.error` | `{sessionId, code, message, retryable, details?}` |

重连带 `cursor`：命中服务端最近 256 条 / 90 s 环形缓冲时补发缺口，否则重发
`subscribed` + 当前 `session.updated` 快照。客户端必须容忍重复事件并按 cursor 去重。

客户端只允许发送控制面帧（`client-ws-message`）：

```json
{"type": "ping", "at": 1762944000123}
{"type": "request_keyframe"}
{"type": "update_quality", "maxFps": 15, "maxBitrateKbps": 1500}
```

媒体和输入不经过这条 WS。

## 截图

```text
POST /api/v2/remote-desktop/sessions/{sessionId}/screenshot
{"displayId": "0", "maxWidth": 1280, "format": "jpeg", "quality": 80}
```

只在已建立的会话里可用；每设备 2 s 一次，超出返回 `429 remote_desktop_rate_limited`。
响应 `{width, height, capturedAt, dataBase64, format}`。

`format` 是 `dataBase64` 的**实际**图片类型（`jpeg` 或 `png`，当前两者都是 8 位灰度）：请求里的
`format` 只是提示，host 可能回退（未知值一律 PNG），所以客户端必须按响应里的 `format` 解码。
未建会话的预览接口不存在（v1 不做）。

## 状态机

```text
starting → awaiting_consent → live → degraded ⇄ live
   │              │              │        │
   └──────────────┴──────────────┴────────┴→ interrupted → live | ended
                                              └→ ended（90 s 超时）
```

| 状态 | 含义 |
| --- | --- |
| `starting` | 已下发 host 启动 RPC，尚未发布 |
| `awaiting_consent` | 等待设备本机确认（仅控制会话） |
| `live` | 已发布并由至少一个观看者订阅 |
| `degraded` | 连续 10 s fps<10 或丢包>8%；仅展示，不结束会话 |
| `interrupted` | host 或全部观看者掉线，保留 90 s 重连 |
| `ended` | 终态，记录 `endReason` 与 `endedAt` |

`endReason`：`client_ended`、`host_ended`、`consent_denied`、`consent_timeout`、`idle_timeout`、
`duration_limit`、`connector_deleted`、`connector_offline`、`media_error`、`policy_revoked`。

计时器：consent 60 s、controller lease 60 s、interrupted 90 s、idle 15 min、单会话最长 8 h。
PG 是会话事实源，多 worker 用条件更新推进；Redis 只做缓存与事件扇出。

## LiveKit webhook

```text
POST /api/v2/internal/livekit/webhook
Authorization: Bearer <LiveKit 签发的 JWT>
```

由自托管 LiveKit 回调，用来确认 `track_published` / `participant_left` 等事件：Server 用它把会话从
`starting` 推进到 `live`，并在 host 掉线时进入 `interrupted`。请求体不重写（反代需原样透传），
校验 `Authorization` 里的签名与 `sha256` 声明；属于内部端点，不需要用户令牌。

## Connector → Server 通知

Connector 通过 `connector/ws` 发 `{"type":"notification","method":…}`，Server 侧处理器见
`services/remote_desktop_notifications.py`。

| 通知 | 时机 | Server 的处理 |
| --- | --- | --- |
| `remoteDesktop.hostInfo` | host 就绪/显示器变化后 | 刷新 host facts 缓存与能力 |
| `remoteDesktop.state` | host 状态机变化 | 推进会话状态（`starting`/`live`/`interrupted`…）并广播 WS 事件 |
| `remoteDesktop.consent` | 本机确认结果 | `granted` → 会话转 `live`；`denied` → 结束并记 `consent_denied` |
| `remoteDesktop.stats` | host 每 2 s 上报 | 合并最新一份，转成 `remote_desktop.stats.updated` |
| `remoteDesktop.displays` | 显示器插拔 | 更新 `host-info` 的 `displays` |
| `remoteDesktop.error` | host 报错 | 记 `error` 字段并广播 `remote_desktop.error` |
| `remoteDesktop.start/stop/updateQuality/control.grant/requestKeyframe/screenshot` | RPC 结果 | 由 RPC 通道处理，不是通知 |

未知的 `remoteDesktop.*` 通知返回 `unsupported_notification`，旧 Server 不会因此报错退出。

## 错误格式

```json
{"detail": {"code": "remote_desktop_unavailable", "message": "Remote desktop is not available.", "details": {"reason": "connector_offline"}}}
```

`code` 稳定、可本地化；`details` 可选。`rd_*` 是 Connector 内部错误码，不会出现在 HTTP 响应里。

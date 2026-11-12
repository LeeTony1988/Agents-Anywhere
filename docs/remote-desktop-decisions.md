# 远程桌面 v1 决策记录与契约不变量

状态：冻结候选，2026-11-13。本文把 [实现计划](remote-desktop-implementation-plan.md) §0.3 的推荐值
落成可执行约束，并记录对抗性评审后对计划原文的修正。实现、契约和文档冲突时以本文 +
`contracts/remote-desktop/1.0/schemas` 为准；本文与 schema 冲突时以 schema 为准，并立即修正本文。

## 1. 最终决策

| ID | 决策 | 最终值 |
| --- | --- | --- |
| D1 | 媒体拓扑 | **一个 Connector 同时只有 1 个活跃会话、1 个 room `rd_{sessionId}`**；多个客户端是同一会话的 participants，一个 controller lease。`maxSessionsPerConnector` 改为 `maxViewersPerSession`，`activeSessions` 只取 0/1。 |
| D2 | 平台范围 | **Windows-first**。P2 交付 Windows 采集/输入；macOS 在 P2 后半或 P3；Linux/Wayland 在 P6。 |
| D3 | consent 与 token | `mode=control` 且 `requireConsent=true` 且 `allowUnattended=false` → 创建后 `state=awaiting_consent`、`media=null`，**不下发任何媒体 token**。设备确认后客户端调 `POST /sessions/{id}/token {role:"controller"}` 取短 TTL controller token。`take-control` 在未确认时返回 `202 {session}`。 |
| D4 | 身份模型 | Server 生成不可猜测的 `participantId`（`pt_` + 32 位随机），绑定 session + user + client。LiveKit identity = `viewer:{participantId}`；host identity = `host:{connectorId}`；`session.controller` 存完整 identity（`viewer:pt_...`）。host 用 LiveKit `DataReceived.participant.identity` 校验，不接受客户端自报 identity。 |
| D5 | 安全默认值 | `remoteDesktopEnabled=false`、`remoteDesktopAllowControl=false`、`remoteDesktopRequireConsent=true`、`remoteDesktopAllowUnattended=false`、`remoteDesktopClipboard=false`、`remoteDesktopMaxFps=30`、`remoteDesktopMaxBitrateKbps=4000`。开启 Enabled / AllowControl / AllowUnattended 必须在本机确认。 |
| D6 | v1 功能边界 | 不做：未建会话的截图预览、无人值守、多显示器切换、E2EE、录制、音频、多 room、文件/图片剪贴板。 |
| D7 | 契约事实源 | `contracts/remote-desktop/1.0/schemas` 是唯一 wire 事实源；`docs/remote-desktop.md` 只写语义与示例；示例必须能在 `validate.py` 通过。 |
| D8 | Desktop 渲染层 | 复制 Web 实现到 `desktop-workbench/renderer`，两边同组测试 + `protocol:check`；不先抽共享包。 |

### 1.1 对计划原文的修正（评审后）

1. **观看不弹确认**：`remoteDesktopRequireConsent` 只约束控制会话。观看权限由
   `remoteDesktopEnabled` 显式开启（本机确认）承担，host 从开始发布起必须显示常驻悬浮条。
   这样「人不在工位时看 Agent 在做什么」的核心场景不需要有人在电脑前点确认。
2. **8 小时上限需要独立结束原因**：`endReason` 增加 `duration_limit`。
3. **抢占不承诺 token 立即失效**：自托管 LiveKit 不支持 JWT 吊销。抢占时 Server
   `UpdateParticipant` 把旧 controller 的 `canPublishData` 置 false、`RemoveParticipant` 只在
   策略吊销/结束时使用，host 同时用 controller identity epoch 拒绝旧输入。文档不得再写「立即失效」。
4. **`request_keyframe` 是 best-effort**：LiveKit Rust SDK / 浏览器订阅端不保证暴露关键帧请求；
   `remoteDesktop.requestKeyframe` 返回 `{requested:boolean}`，客户端不得依赖。
5. **创建会话是 create-or-join**：同一 Connector 已有活跃会话时，`POST /sessions` 加入该会话并
   返回同一个 `session.id` 与新 `participantId`；`mode=control` 在已有 controller 时按 takeover
   语义抢占（同账号），否则 `409 remote_desktop_busy`。数据库用部分唯一索引保证每 Connector
   至多一个未结束会话。

## 2. 契约不变量

### 2.1 命名

- 时间：RFC 3339 UTC 字符串，字段名以 `At` 结尾。
- 控制权租约到期：`controllerLeaseExpiresAt`（session 与 take-control/heartbeat 共用）。
- token 到期：`tokenExpiresAt`（创建、续签共用）。
- Base64 内容：`dataBase64`（HTTP、RPC、剪贴板统一）。禁止 `jpegBase64`、`leaseExpiresAt`、`expiresAt`。
- 布尔能力字段：`supported` / `available` / `allowed`，语义与
  [capabilities](api/capabilities.md) 一致。

### 2.2 实时事件 envelope

六类事件统一为 `{type, payload}`，payload 必带 `sessionId`：

| type | payload |
| --- | --- |
| `remote_desktop.subscribed` | `{sessionId, cursor}`；首帧。cursor 是十进制字符串，表示已投递的最大事件序号；空串表示没有历史。 |
| `remote_desktop.session.updated` | `{sessionId, session}` |
| `remote_desktop.stats.updated` | `{sessionId, stats:{fps,bitrateKbps,rttMs,jitterMs,packetLossPct,codec,width,height,encoder,subscribers}, at}` |
| `remote_desktop.control.revoked` | `{sessionId, reason:"taken_over"|"lease_expired"|"policy_revoked"}` |
| `remote_desktop.ended` | `{sessionId, endReason, endedAt}`（`endedAt` 必填） |
| `remote_desktop.error` | `{sessionId, code, message, retryable}` |

断线重连：`GET ws?ticket=&cursor=`。cursor 命中服务端每会话环形缓冲（最近 256 条、90 s）时补发
`cursor` 之后的事件；未命中时只发 `subscribed` + 当前 `session.updated` 快照（snapshot 语义）。
客户端必须容忍重复事件（按 cursor 去重）和乱序以外的一切未知字段。

### 2.3 输入 / 控制事件方向

- `input-event.schema.json`：**只允许 client → host**。分支：pointer / wheel / key / text /
  clipboard(offer|request) / ping。`t:"ack"` 与 host 侧的 `denied` 移出本 schema。
- `host-control-event.schema.json`：**host → client**。分支：`ack{ack}`、`reject{code,seq?}`、
  `clipboard{offer|denied}`。`reject.code ∈ {not_controller, rate_limited, invalid_event, unsupported, policy}`；
  `clipboard.denied.reason ∈ {policy, too_large, unsupported}`。
- `aa.pointer` 帧固定 12 字节（小端）：type u8、flags u8、buttons u16、x u16、y u16、seq u32。
  seq 是 u32，`0xFFFFFFFF` 后回绕到 0；越界坐标按 [0,65535] clamp。十六进制测试向量见
  `contracts/remote-desktop/1.0/pointer-frame.md`。

### 2.4 错误

HTTP 错误统一为：

```json
{"detail": {"code": "remote_desktop_unavailable", "message": "…", "details": {"reason": "connector_offline"}}}
```

- 对外 code 使用 `remote_desktop_*` / `media_*`；`rd_*` 只作为 Connector → Server 的内部码，
  Server 必须映射后返回。
- `details` 可选；已知错误码见契约 `error.schema.json`。未知错误码允许客户端按 `message` 展示。
- 现有 Web `normalizeError` 与 Android `ApiClient` 已能解析 `detail` 对象，不新增兼容层。

### 2.5 媒体与身份

- room：`rd_{sessionId}`，会话结束即删；v1 一个 room 只有一个 host。
- host token grants：`roomJoin=true`、`canPublish=true`、`canSubscribe=false`、`canPublishData=true`。
- viewer token grants：`roomJoin=true`、`canPublish=false`、`canSubscribe=true`、`canPublishData=<is controller>`。
- controller token TTL 120 s；viewer token TTL 600 s；token 不落库、不写日志。
- 抢占：`UpdateParticipant(canPublishData=false)` 旧 controller + host `control.grant` 新 identity；
  旧 token 仍在 TTL 内可订阅，但发不出数据包。
- `codecs` 与 `preferCodec` 在 1.0 中只有 `vp8`；增加编码必须升契约次版本。

### 2.6 能力与设备设置

- 能力 id：`connector.remote_desktop.view` / `.control` / `.clipboard`，独立于
  `ProtocolCapabilitySet`，每个对象带单调递增 `revision`。
- 设备设置 7 项与默认值见 §1 D5；`remoteDesktopMaxFps` 5–60，`remoteDesktopMaxBitrateKbps`
  500–20000；未知字段 PATCH 仍 422。

## 3. 会话状态机与计时器

```text
starting → awaiting_consent → live → degraded ⇄ live
   │              │              │        │
   └──────────────┴──────────────┴────────┴→ interrupted → live | ended
                                              └→ ended（90 s 超时）
```

- 终态 `ended` 不可逆；`endReason`：
  `client_ended | host_ended | consent_denied | consent_timeout | idle_timeout | duration_limit |
  connector_deleted | connector_offline | media_error | policy_revoked`。
- 计时器（PG 为事实源，条件 UPDATE 保证多 worker 只推进一次）：
  | 计时器 | 时长 | 归属 | 动作 |
  | --- | --- | --- | --- |
  | consent | 60 s | `consent_deadline_at` | `ended(consent_timeout)` |
  | controller lease | 60 s | `controller_lease_expires_at` | 清 controller，发 `control.revoked(lease_expired)`，会话保持 live |
  | interrupted | 90 s | `interrupted_at` | `ended(connector_offline)`（host 掉线）或 `ended(idle_timeout)`（无观看者） |
  | idle | 15 min | `last_activity_at` | `ended(idle_timeout)` |
  | max duration | 8 h | `started_at` | `ended(duration_limit)` |
- `degraded` 仅展示层状态：连续 10 s fps<10 或丢包>8% 进入，恢复即回 `live`，不结束会话。
- Server 重启：所有未结束会话标记 `interrupted` 并广播；客户端 90 s 内重连恢复，否则 sweeper 结束。

## 4. 参与者与权限

- `participantId` 由 Server 生成，保存在会话的 `participants` JSON 列（最多 16 个），
  记录 `participantId / userId / platform / appVersion / createdAt / lastSeenAt`。
- token、take-control、heartbeat、ticket 请求都必须带 `participantId`；Server 校验它属于该
  session 且 `userId` 匹配。WS ticket 的 `client_id` 存 participantId，连接建立后不得再自报身份。
- `controller` 只能由同一 `user_id` 的 participant 持有；跨账号一律 404。
- host 只接受 `DataReceived.participant.identity == controllerIdentity` 的输入；Server 的
  controller 字段与 host `control.grant` 使用同一个完整 identity。

## 5. 契约文件清单（P0 冻结范围）

`schema` 目录下必须有：`session`、`media-grant`、`host-facts`、`capability`、`host-info`、
`device-settings`、`error`、`session-create-request/response`、`session-list-response`、
`session-response`、`token-refresh-request/response`、`take-control-request/response`、
`release-control-request`、`heartbeat-request`、`end-request`、`ticket-request/response`、
`screenshot-request/response`、`realtime-event`、`input-event`、`host-control-event`、
`host-control-message`。

每个 schema 至少一份 valid fixture；每个 `oneOf` 分支至少 valid + invalid 各一份；manifest 的
`slug/path/sha256/direction` 与 schema 的 `x-direction` 必须一致；`validate.py` 必须做
manifest 哈希校验、FormatChecker、fixture 目录 slug 匹配和 schema 元数据校验。


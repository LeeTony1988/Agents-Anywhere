# 远程桌面契约 1.0

本目录是 Agents Anywhere 远程桌面的 **wire 事实源**：Server、Connector、`aa-screen-host` 与
Web/Desktop/Android/iOS 客户端对字段、类型和取值有分歧时，以 `schemas/` 为准。
`docs/remote-desktop.md` 只描述语义、时序与示例；示例必须能通过本目录的 `validate.py`。

状态：1.0 冻结候选（2026-11-13）。冻结后只允许向后兼容的新增（升 1.1），不允许改字段语义。

## 范围

| Schema | 用途 | 方向 |
| --- | --- | --- |
| `host-info` | `GET /api/v2/connectors/{connectorId}/remote-desktop` 响应：三个能力对象 + host facts | egress |
| `capability` | `connector.remote_desktop.view/control/clipboard` 能力对象 | egress |
| `host-facts` | Connector 通过 `remoteDesktop.hostInfo` 上报的主机事实 | egress |
| `session` | 会话对象（创建响应、查询、实时事件复用） | egress |
| `media-grant` | 短期 LiveKit 加入凭据（创建响应、token 续签复用） | egress |
| `session-create-request` | `POST /remote-desktop/sessions`（create-or-join） | ingress |
| `session-create-response` | 创建/加入成功响应（含 participantId、media、ws ticket） | egress |
| `session-list-response` | `GET /remote-desktop/sessions` | egress |
| `session-response` | 查询/结束/释放控制/心跳的响应 | egress |
| `token-refresh-request/response` | `POST /sessions/{id}/token` | ingress / egress |
| `take-control-request/response` | `POST /sessions/{id}/take-control` | ingress / egress |
| `release-control-request` | `POST /sessions/{id}/release-control` | ingress |
| `heartbeat-request` | `POST /sessions/{id}/heartbeat` | ingress |
| `end-request` | `POST /sessions/{id}/end` | ingress |
| `ticket-request/response` | `POST /sessions/{id}/ticket` | ingress / egress |
| `screenshot-request/response` | `POST /sessions/{id}/screenshot` | ingress / egress |
| `error` | 统一 HTTP 错误 envelope | egress |
| `device-settings` | 7 个远程桌面设备设置的类型、默认值与范围 | ingress |
| `realtime-event` | 会话 WebSocket 服务端事件（六类） | egress |
| `client-ws-message` | 会话 WebSocket 客户端控制帧（ping/keyframe/quality） | ingress |
| `input-event` | 客户端 → 主机的输入事件（`aa.input`） | ingress |
| `host-control-event` | 主机 → 客户端的 ack/reject/剪贴板事件 | egress |
| `clipboard-transfer` | 剪贴板分片传输（两个方向） | bidirectional |
| `host-control-message` | Connector ↔ `aa-screen-host` 的 NDJSON stdio 协议 | bidirectional |

## 传输

- HTTP：`/api/v2/remote-desktop/*`，JSON camelCase，时间 RFC 3339 UTC。
- 会话 WS：`WS /api/v2/remote-desktop/sessions/{sessionId}/ws?ticket=...`，只承载控制面事件。
- 媒体：LiveKit WebRTC。视频 track 名 `screen`，`source=SCREEN_SHARE`，v1 软件编码 VP8，
  `simulcast=false`，`contentHint=text`。
- 输入：LiveKit Data Channel。`aa.input`（reliable/ordered，JSON，`input-event`）、
  `aa.input.ack`（reliable，`host-control-event`）、`aa.pointer`（lossy/unordered，12 字节二进制，
  见 `pointer-frame.md`）。
- 剪贴板：小块走 `input-event`/`host-control-event` 的 `clipboard` 分支；超过单包预算走
  `clipboard-transfer`（≤8 KiB/片，≤64 KiB/会话）。

连接串与 token 不写日志、不落库。`media-grant` 只在创建响应和 token 续签响应里出现。

## 身份

- `participantId`：Server 生成，`pt_` + 至少 16 位 URL-safe 随机串，绑定 session + user + client。
- LiveKit identity：viewer 为 `viewer:{participantId}`，host 为 `host:{connectorId}`。
- `session.controller` 存完整的 viewer identity；host 收到数据包时用
  `DataReceived.participant.identity` 与 `controlGrant` 下发的 identity 比对，不信任客户端自报。
- 同一账号的多个 participant 可以抢占控制权；跨账号永远 404。抢占不会吊销旧 JWT（LiveKit
  不支持），只通过 `UpdateParticipant(canPublishData=false)` + host epoch 阻断输入。

## 命名

- `controllerLeaseExpiresAt`（会话与心跳共用）、`tokenExpiresAt`（创建与续签共用）、
  `dataBase64`（HTTP/RPC/剪贴板统一）。旧的 `leaseExpiresAt`、`expiresAt`、`jpegBase64` 已删除。
- 时间字段统一以 `At` 结尾。
- 能力字段统一 `supported` / `available` / `allowed` + `unavailableReason` + `revision`。

## 版本与兼容

- 本契约版本 `1.0` 独立于 `protocol/*` 与产品版本。
- **ingress 严格**：请求对象（以及 `input-event`、`client-ws-message`、`host-control-message`）
  `additionalProperties:false`；客户端多发的字段会被拒绝。
- **egress 开放**：响应和事件允许未知字段，客户端必须忽略；Server/Connector 新增可选字段属于
  次版本（1.1），不需要客户端同步升级。新增**枚举值**（状态、endReason、codec）属于有意的契约
  演进，必须升次版本并在发布说明中列出。
- `state` 只允许追加终态；终态 `ended` 不可逆。
- `endReason`：`client_ended | host_ended | consent_denied | consent_timeout | idle_timeout |
  duration_limit | connector_deleted | connector_offline | media_error | policy_revoked`。
- 输入事件 `v` 固定 1；`aa.input` 的 JSON `seq` 与 `aa.pointer` 的 `seq` 是**两个独立计数器**，
  都在 `0..2^32-1` 回绕。
- `codecs` / `preferCodec` / `stats.codec` 在 1.0 只允许 `vp8`；增加编码必须升 1.1。

## 错误

HTTP 错误统一：

```json
{"detail": {"code": "remote_desktop_unavailable", "message": "…", "details": {"reason": "connector_offline"}}}
```

客户端只依赖 `code` 和 `message`；`details` 是可选补充。`rd_*` 是 Connector → Server 的内部码，
不得出现在 HTTP 响应里。

## 目录里除 schema 之外还有什么

| 文件 | 作用 | 谁在读 |
| --- | --- | --- |
| `schemas/*.schema.json` | wire 事实源 | Server、Connector、各客户端、`validate.py` |
| `manifest.json` | 每个 schema 的 slug/path/sha256/direction | `validate.py`、`web-next`/`desktop-workbench` 的 `generate-protocol-types.mjs` |
| `fixtures/valid|invalid/<slug>/` | 每个 schema 的正/反例 | `validate.py`、契约测试 |
| `pointer-frame.md` | `aa.pointer` 数据通道的 12 字节小端帧布局与 5 组十六进制向量 | screen-host 的 `RemotePointerFrame` 与其单测 |
| `screenshot-naming.json` | 截图保存到本地的文件名规则与用例（见下） | Web、Desktop、Android 的截图测试 |
| `validate.py` | 契约自检脚本（CI `contracts` job） | CI |

`pointer-frame.md` 与 `screenshot-naming.json` 不是 wire 契约，但和契约一起版本化：改帧格式或改命名
规则时，要同时更新这里的文档/用例和三端实现。

## 校验

```bash
uv run --with jsonschema --with referencing python contracts/remote-desktop/1.0/validate.py
```

`validate.py` 会检查：schema 本身是合法 Draft 2020-12、`$id`/版本/方向元数据、manifest 的
slug/path/sha256/direction、每个 schema 至少一份 valid fixture、valid 必须通过、invalid 必须被拒、
fixtures 必须按 `<kind>/<slug>/` 组织。修改任何 schema 后必须重新生成 manifest 哈希，否则 CI 红。


## 截图文件命名（`screenshot-naming.json`）

截图不是 wire 契约的一部分，但客户端保存文件时要用同一套命名，否则同一个画面在 Web、Desktop 和
Android 上会得到不同文件名。`screenshot-naming.json` 记录了规则与用例：

```text
remote-<connectorId>-<yyyyMMddHHmmss>.<jpg|png>
```

- 标签只保留 `[A-Za-z0-9._-]`，其它连续字符替换成 `-`，去掉首尾 `-`，最多 48 个字符；
- 时间取 `capturedAt` 里前 14 位数字（UTC），缺失时用 `unknown`；
- 扩展名跟随**服务端实际返回**的 `format`，缺失时按 PNG。

三端各自的测试都读取这份文件（`web-next/test`、`desktop-workbench/renderer/test`、
`android/app/src/test`），改动实现会让另外两端的测试一起失败。`validate.py` 不校验该文件。


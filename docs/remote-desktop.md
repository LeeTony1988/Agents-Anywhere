# 远程桌面（Remote Desktop）架构方案

状态：实现中。P0 契约、P1 Server 控制面、P2 屏幕主机/Connector 主体、P3 Web/Desktop、
P4 Android 观看与控制已落地；iOS（P5）与真机/真实桌面采集的端到端验收待完成。本文描述目标架构与
接口语义；**wire 契约以 `contracts/remote-desktop/1.0/schemas` 为唯一事实源**，HTTP/WS 端点细节见
[远程桌面 API](api/remote-desktop.md)，决策记录见
[远程桌面 v1 决策记录](remote-desktop-decisions.md)。
审查结论、阻塞项与分阶段实现清单见 [远程桌面实现计划](remote-desktop-implementation-plan.md)。

适用范围：产品 2.3.0（跨端接口新增），Server 迁移 `v2_56`，契约 `remote-desktop/1.0`。

---

## 0. 结论

一句话：**媒体面用 WebRTC，自托管 LiveKit 作为 SFU/TURN；控制面复用现有 Server + Connector
RPC；工作设备上新增一个原生 `aa-screen-host` 子进程负责采集、编码、注入输入；手机/Web 客户端用
LiveKit 官方 SDK 订阅画面，用 LiveKit Data Packet 回传输入事件。**

关键取舍：

| 决策 | 选择 | 理由 |
| --- | --- | --- |
| 传输协议 | WebRTC（SRTP + SCTP） | 唯一在 Android/iOS/Web 都有官方成熟 SDK 的低延迟方案；自带拥塞控制、NACK/FEC、屏幕内容编码优化 |
| 拓扑 | SFU（LiveKit），不做自研 P2P 信令 | P2P 需要自己实现 SDP/ICE/重协商/多观众扇出，正是最容易出错的部分；SFU 多一跳换来「零信令代码 + 多端同时观看 + 内建 TURN」 |
| 媒体服务 | LiveKit（Apache-2.0，自托管） | 官方 Android/iOS/Swift/Kotlin/Flutter/JS/Rust SDK；内建 TURN/TLS；支持 simulcast、数据包、webhook |
| 主机侧采集/编码 | Rust `aa-screen-host`（LiveKit Rust SDK + 平台采集） | 单一二进制、无 GStreamer 运行时依赖；libwebrtc 负责软件编码与发布（默认 VP8），H.264 硬件编码放到 P4 的 GStreamer/WHIP 路径 |
| 输入通道 | WebRTC Data Packet（`aa.input`） | 与画面同路径、同 NAT 穿透，延迟最低；LiveKit token 可按角色禁用发数据，天然实现「只看不能控」 |
| 信令 | 不新增信令协议 | 加入房间由 LiveKit 完成；Server 只签发短期 JWT + 管理会话生命周期 |
| 与现有系统关系 | 新增 `remote-desktop` 资源域，不改 runtime 协议 | 远程桌面是 **设备能力**，不是 Runtime 能力；与 terminal/files 同级 |

明确不做（v1）：文件传输、声音回传、多人同时操作、远程开机/无人值守密码、录制、E2EE。

---

## 1. 目标与非目标

### 1.1 目标

1. 手机（Android/iOS）、Web、Desktop 能实时看到工作设备的桌面，延迟目标 **80–200 ms**（同城/4G/5G）。
2. 手机能操作鼠标、键盘、滚轮、软键盘输入，手势可完成点击、右键、滚轮、缩放、拖拽。
3. 一个设备可被多个客户端同时观看；同一时刻只有一个客户端持有**控制权**（controller lease）。
4. 复用现有账号、设备归属、设备设置、审计与实时通道；不引入第二套身份体系。
5. 网络自适应：1080p 文本桌面 1.5–4 Mbps；弱网自动降分辨率/帧率而不是卡死。
6. 失败可诊断：会话状态、码率、RTT、丢包、编码器都能在客户端看到并上报。

### 1.2 非目标（v1 不做，架构上留出口）

- 文件双向传输（走现有 `fs.*` RPC，不在本特性内）。
- 音频回传、语音对讲。
- 多控制器同时操作（只做「一个控制者 + N 个观看者」）。
- 无人值守访问（设备锁屏/无人确认时的永久密码），v1 要求设备侧有确认或预授权设置。
- 浏览器端「免安装被控」。
- 端到端加密（SFU 可见媒体；需要时按 §9.4 加 SFrame）。

---

## 2. 现状与可复用资产

远程桌面几乎不需要新的基础设施，现有代码已经提供了 80% 的骨架：

| 现有能力 | 位置 | 复用方式 |
| --- | --- | --- |
| Connector 长连接 + 双向 RPC | `WS /api/v2/connector/ws`、`connector/server/dispatch.py`、`server/agent_server/infra/connector_rpc.py` | 新增 `remoteDesktop.*` RPC/通知方法，走同一分发器 |
| Connector 通知入库与扇出 | `server/agent_server/api/connector_ingress.py` + `services/connector_ingest.py` | `remoteDesktop.state/stats/error` 作为新通知方法接入 lane |
| 一次性 WS ticket | `infra/ws_tickets.py`（`scope` 可任意字符串） | 新增 scope `remote-desktop:{sessionId}`；由远程桌面路由直接签发，不改 `POST /ws-ticket` 的请求 schema |
| 设备设置（Server 下发、离线生效） | `docs/api/device-settings.md`、`services/connector_device_settings.py` | 新增 `remoteDesktop.*` 设置项 |
| 设备归属与在线状态 | `services/connector_presence.py`、dashboard snapshot | 决定 `available/allowed` |
| 终端 relay 的「RPC 建链 + 专用 socket 传数据」模式 | `services/terminal_relay.py`、`infra/terminal_stream_hub.py` | 结构参考；远程桌面把「专用 socket」换成 WebRTC |
| 进程监管与日志 | `connector/launch.py`、`core/runtime_logs.py` | 监管 `aa-screen-host` 子进程 |
| 使用量与审计事件 | `services/usage_events.py` | 记录会话时长、流量估算 |

**不需要**复用的：`ProtocolCapabilitySet`（runtime/session 两种 scope），远程桌面用独立的能力对象，见 §7.8。

---

## 3. 开源方案调研

### 3.1 候选总览

| 项目 | 许可证 | 协议/形态 | 手机端 | 延迟 | 结论 |
| --- | --- | --- | --- | --- | --- |
| **LiveKit** | Apache-2.0 | WebRTC SFU（Go），内建 TURN | 官方 Android/iOS/Flutter/RN/JS/Rust SDK | 100–250 ms | ✅ 选为媒体面 |
| coturn | BSD-3 | TURN/STUN | 与客户端无关 | — | 备选（LiveKit 内建 TURN 已够用） |
| RustDesk | AGPL-3.0 | 自研 TCP/UDP 协议 + 自建 relay | Flutter（自绘） | 30–120 ms | ❌ 协议私有、AGPL 传染；只借鉴设计 |
| Sunshine + Moonlight | GPL-3.0 | NVIDIA GameStream 协议 | Moonlight 原生客户端 | 20–80 ms | ❌ 面向游戏、协议非标准、GPL；借鉴编码器选择 |
| Apache Guacamole | Apache-2.0 | 服务端 guacd 代理 RDP/VNC，WebSocket 文本协议 | 只有浏览器 | 150–400 ms | ❌ 依赖目标机开 RDP/VNC，画质/延迟不适合手机 |
| noVNC | MPL-2.0 | 浏览器 VNC 客户端 | 只有浏览器 | 200–500 ms | ❌ 帧缓冲差分，无视频编码 |
| mediasoup | ISC | WebRTC SFU（C++/Node） | 无一方移动 SDK（需 libmediasoupclient） | 100–250 ms | 备选，移动端集成成本高 |
| Janus | GPL-3.0 | WebRTC 网关（C） | 无官方移动 SDK | 100–250 ms | ❌ GPL + 集成复杂 |
| Pion | MIT | WebRTC 库（Go） | 用标准 WebRTC 客户端即可 | 取决于自研 | 备选：要自己写 SFU |
| GStreamer | LGPL-2.1 | 采集/编码/`webrtcbin`/`whipclientsink` | — | — | 备选：主机侧硬编路径（§13.2） |
| OpenH264 | BSD-3 | H.264 软编 | — | — | ✅ 软编回退（注意 `x264enc` 是 GPL，不要打包） |

### 3.2 逐个评估

**RustDesk（AGPL-3.0）** — 最接近「我们要做的事」的开源实现：Rust 核心、自研协议、Flutter 客户端、
自带中继（hbbs/hbbr）。它证明了「采集 → 硬件编码 → 自有传输 → 输入注入」这条链路在消费级网络可用。
但不能直接用：

- 协议是私有的，客户端必须用它的 Rust 库 + Flutter 壳，无法融入现有 Kotlin/Swift/Next.js 客户端；
- AGPL-3.0 对「通过网络提供服务」有源码开放义务，与闭源分发的 Desktop/移动端冲突；
- 它的 relay 只做字节转发，媒体路由、拥塞控制和多观众订阅都要自己实现。

可借鉴：输入事件归一化（scancode → 平台键码）、脏区域/光标叠加、被控端权限弹窗流程。

**Sunshine + Moonlight（GPL-3.0）** — 延迟标杆（局域网 20–40 ms），因为它跳过通用 WebRTC 栈、
用 NVENC 直接出帧、UDP 裸传。代价是：绑定 NVIDIA/AMD/Intel 硬件编码器、协议非标准、GPL。
可借鉴：编码器探测顺序（NVENC → AMF → QSV → 软件）、「按内容类型选 tune」（文本用
`zerolatency + 高 QP 下限`，视频用码率优先）。

**Apache Guacamole（Apache-2.0）** — 服务端 `guacd` 用 libfreerdp/libvncclient 连目标机，浏览器通过
WebSocket 跑 Guacamole 自有协议（文本指令 + PNG/JPEG 图层）。优点是「目标机零安装」，缺点是：

- 必须目标机开启 RDP/VNC（Windows 家庭版没有 RDP Server，macOS 要开「屏幕共享」）；
- 图形走 PNG/JPEG 图层合成，滚动和视频场景会明显掉帧；
- 手机端只有浏览器，没有原生手势/键盘体验。

结论：不作为主路径，但可以作为 P4 的「Linux 服务器/遗留系统适配器」——用 guacd 把 RDP/VNC 目标
转成一路媒体源，代价是要实现 Guacamole 客户端协议，收益有限，暂不做。

**noVNC（MPL-2.0）** — 纯浏览器 VNC，RFB 帧缓冲差分 + WebSocket。适合「看一眼」，不适合交互。
同样需要目标机跑 VNC Server。

**LiveKit（Apache-2.0）** — Go 写的 SFU，单二进制，内建 TURN（TLS 443 / UDP 443），提供
Android（`io.livekit:livekit-android`）、iOS（`LiveKitClient` SwiftPM）、Web（`livekit-client`）、
Rust（`livekit`）官方 SDK；支持 simulcast、自适应流、Data Packet（reliable/lossy 两种）、
webhook、Prometheus 指标。它把「加入房间 / ICE / DTLS / 重连 / 拥塞控制」全部封装掉，
我们只需要签发 JWT 和调用 REST/Webhook。

**mediasoup / Janus / Pion** — 都是成熟 SFU/WebRTC 栈，但对本项目：

- mediasoup 的移动端要自己编 libmediasoupclient（C++），Android/iOS 接入成本高；
- Janus 是 GPL-3.0，插件模型偏「网关」，Data Channel 支持一般；
- Pion 是库不是服务，选它等于自己写 SFU（房间、转发、拥塞、TURN 都要自己做）。

三者都不如 LiveKit「开箱即用 + 官方移动 SDK」划算。

### 3.3 选型结论

```text
采集/编码：aa-screen-host（Rust，LiveKit Rust SDK / libwebrtc）
媒体转发：LiveKit SFU（自托管，内建 TURN）
信令：    无自研信令，LiveKit 加入房间 + Server 签发 JWT
控制面：  现有 Server HTTP/WS + Connector RPC
输入：    LiveKit Data Packet（topic: aa.input / aa.pointer）
```

---

## 4. 总体架构

### 4.1 分层

```text
┌────────────────────────────── 客户端（Web / Desktop / Android / iOS）──────────────────────────────┐
│  RemoteDesktopScreen                                                                              │
│   ├─ SessionController   生命周期：创建 / 取 token / 观看 / 申请控制 / 结束（HTTPS + WS）           │
│   ├─ MediaSession        LiveKit SDK：订阅 host 的 screen track，渲染 SurfaceView/VideoView/Video │
│   └─ InputEncoder        触摸/键盘 → InputEvent（Data Packet），本地预测与节流                      │
└───────────────┬───────────────────────────────────────────────┬───────────────────────────────────┘
                │ HTTPS / WSS（会话、token、状态）               │ WebRTC（SRTP 视频 + SCTP 数据）
                ▼                                               ▼
┌───────────────────────────────┐                    ┌──────────────────────────────┐
│ Agents Anywhere Server        │   REST/Webhook     │ LiveKit SFU（自托管）         │
│  api/remote_desktop.py        │◄──────────────────►│  room: rd_{sessionId}        │
│  services/remote_desktop.py   │                    │  host 发布 1 路 screen track  │
│  infra/remote_desktop_broker  │                    │  内建 TURN / TLS 443          │
└───────────────┬───────────────┘                    └──────────────▲───────────────┘
                │ WS /api/v2/connector/ws（RPC + 通知）               │ WebRTC
                ▼                                                    │
┌───────────────────────────────┐                                    │
│ Connector（Python）            │  stdio JSON-RPC                    │
│  local/remote_desktop/host.py ├──────────────► aa-screen-host（Rust）┘
│  server/remote_desktop_rpc.py │                 ├─ capture（DXGI/SCK/PipeWire）
└───────────────────────────────┘                 ├─ encode（libwebrtc VP8/H264）
                                                  ├─ publish（LiveKit Rust SDK）
                                                  └─ input（SendInput/CGEvent/uinput）
```

### 4.2 一次会话的完整时序

```text
Client                     Server                     Connector             aa-screen-host        LiveKit
  │                          │                          │                       │                  │
  │ POST /remote-desktop/sessions                        │                       │                  │
  ├─────────────────────────►│ 校验归属 / 能力 / 并发    │                       │                  │
  │                          ├ remoteDesktop.start ─────►│                       │                  │
  │                          │                          ├ 启动或复用 host ──────►│                  │
  │◄ 201 {session, media} ───┤                          │                       ├ 采集 + 编码       │
  │                          │                          │                       ├ join(room) ──────►│
  │ join(room, viewerToken) ─┼──────────────────────────┼───────────────────────┼─────────────────►│
  │                          │◄ remoteDesktop.state ────┤◄ state: publishing ───┤                  │
  │                          │◄──── LiveKit webhook: track_published / participant_joined ────────│
  │◄ WS session.updated ─────┤                          │                       │                  │
  │     (state=live)         │                          │                       │                  │
  │                          │                          │                       │                  │
  │ Data Packet aa.input ────┼──────────────────────────┼───────────────────────┼─────────────────►│
  │                          │                          │                       │◄ data received ──┤
  │                          │                          │                       ├ 注入鼠标 / 键盘   │
  │                          │◄ remoteDesktop.stats ────┤◄ stats(2s) ───────────┤                  │
  │◄ WS stats.updated ───────┤                          │                       │                  │
  │                          │                          │                       │                  │
  │ POST .../end             │                          │                       │                  │
  ├─────────────────────────►├ remoteDesktop.stop ─────►│                       ├ leave ───────────►│
  │◄ 200 {state:"ended"} ────┤                          │                       │                  │
```

### 4.3 控制面 / 媒体面职责

| 平面 | 承载 | 谁负责 | 是否持久化 |
| --- | --- | --- | --- |
| 控制面 | 会话创建、鉴权、能力、状态、控制权租约、审计 | Server + Connector | 会话记录入库；实时状态在 Redis |
| 媒体面 | 屏幕视频（SRTP）、输入事件（SCTP Data Packet） | LiveKit + 两端 SDK | 不持久化，进程/房间结束即消失 |
| 主机面 | 采集、编码、注入、光标/显示器变化 | `aa-screen-host` | 仅本地日志 |

**边界原则**：Server 永远不解析媒体，也不做 SDP 交换；LiveKit 不认识 Agents Anywhere 的账号，
只认 Server 签发的短期 JWT。两侧通过 webhook + RPC 对齐状态。

---

## 5. 关键设计决策（ADR）

### ADR-1：为什么用 SFU 而不是 P2P

P2P 在 1:1 时确实少一跳，但需要自研：SDP offer/answer、trickle ICE、重协商（换显示器/改分辨率）、
多观众扇出、TURN 凭据、连接迁移。这些正是 WebRTC 集成里 80% 的 bug 来源。LiveKit 把这些全部
封装，并且：

- 手机在 4G/5G、企业网、对称 NAT 下都能连（内建 TURN/TLS 443）；
- 多端同时观看天然支持（同一 room 多 subscriber）；
- 观众数增加时，主机上行不变（只发一路到 SFU）。

代价：多一跳（同城 +20–40 ms）、服务器要转发带宽。对「看 Agent 在干什么」的场景可以接受。

### ADR-2：为什么不复用 RustDesk 协议

见 §3.2。核心是「客户端必须换成它的栈」和 AGPL。我们已经有四个端的原生客户端，只有 WebRTC
能让每端都用官方 SDK。

### ADR-3：为什么主机侧用 Rust 而不是 Python

Connector 是 Python，但采集/编码/注入对时序敏感（16 ms 一帧），Python 的 GIL 和调用开销会让
帧率抖动。Rust 子进程 + stdio JSON-RPC 的好处：

- 单文件分发（Windows `.exe` / macOS universal binary / Linux AppImage 式目录），无 Python 依赖；
- LiveKit Rust SDK 直接封装 libwebrtc，省掉自己实现 SDP/ICE；
- 崩溃隔离：host 挂了不影响 Connector 的 WS 和终端。

Connector 只负责「拉起、传参、转发 RPC、监管重启」，保持薄。

### ADR-4：为什么输入走 Data Packet 而不是 Server WS

- 与视频同一路径：NAT/代理只打通一次，弱网下两者一起降级而不是互相甩锅；
- 延迟：不再多一次 Server 中转（Server 转发还要经过 JSON 编解码和事件循环）；
- 权限：LiveKit token 里 `canPublishData=false` 的观众**在服务端**就发不出输入，比应用层判断可靠；
- 代价：SFU 能看到输入内容（自托管，可接受；需要时上 E2EE）。

Server WS 仍然保留一条 `remoteDesktop.input` 兜底通道，用于：Data Packet 不可用（极端网络）、
或未来主机侧换成 WHIP 发布（WHIP 发布者收不到数据包）。

### ADR-5：为什么把远程桌面做成「设备能力」而不是 Runtime 能力

它和 Codex/Claude/DSH 无关：不看会话也能用，任何 runtime 都能用。因此：

- 不进入 `ProtocolCapabilitySet`（其 scope 只有 runtime/session）；
- 用独立的 `RemoteDesktopCapability` 对象，字段语义与 `ProtocolCapability` 对齐
  （`supported / available / allowed / unavailableReason / metadata`）；
- 入口放在设备详情页，与「终端」「文件」并列；会话页可以提供「看这台设备的屏幕」的快捷入口。

### ADR-6：为什么 v1 不做 E2EE

LiveKit 的 E2EE（SFrame + insertable streams）要求所有端实现密钥分发，Web 端可用，
Android/iOS SDK 支持程度不一，且会挡住「服务端录制/审计」的未来选项。v1 依赖
DTLS-SRTP + 自托管 + 短期 token；E2EE 作为 P4 可选项，见 §9.4。

---

## 6. 新增模块

总览：

| 层 | 新增内容 | 语言/技术 |
| --- | --- | --- |
| Server | 1 个 API 路由文件、1 组领域模型、3 个服务、2 个 infra 适配器、1 个仓储、1 个迁移 | Python / FastAPI |
| Connector | host 监管、RPC 分发、策略、配置项（4 个模块） | Python |
| Screen Host | 采集、编码/发布、输入注入、stdio 协议（新顶层项目） | Rust |
| 客户端 | 会话控制 + 媒体渲染 + 输入编码（Web/Android/iOS 各一套） | TS / Kotlin / Swift |
| 部署 | LiveKit 服务、TURN 端口、Server 环境变量 | Docker Compose |

### 6.1 Server（`server/agent_server/`）

| 模块 | 职责 |
| --- | --- |
| `api/remote_desktop.py` | HTTP 路由 + 会话 WS + LiveKit webhook 入口 |
| `core/remote_desktop.py` | 领域模型：`RemoteDesktopSession`、`DisplayInfo`、`RemoteDesktopState`、`InputEvent` 校验、错误码 |
| `services/remote_desktop.py` | 用例编排：创建 → 能力校验 → Connector RPC → 媒体授权 → 状态推进 → 结束/清理 |
| `services/remote_desktop_media.py` | 媒体面端口：`LiveKitMediaProvider`（签 token、建 room、查参与者）与 `NoopMediaProvider`（测试/关闭特性） |
| `services/remote_desktop_policy.py` | 并发上限、控制权租约、设备设置合并、审计 |
| `infra/remote_desktop_broker.py` | 跨 worker 的临时状态：Redis hash `rd:session:{id}` + Pub/Sub 扇出（对齐 `terminal_stream_hub.py`） |
| `infra/livekit_webhook.py` | 校验 LiveKit webhook JWT，映射 `participant_joined/left`、`track_published`、`room_finished` |
| `infra/repositories/remote_desktop.py` | 会话记录读写 |
| `migrations/versions/v2_56.py` | `remote_desktop_sessions` 表 |
| `core/capabilities.py` | 增加 `REMOTE_DESKTOP_VIEW/CONTROL/CLIPBOARD` 常量（仅用于策略判定，不进 ProtocolCapabilitySet） |

依赖规则不变：`api → services → core`，`services` 只依赖 `LiveKitMediaProvider` 端口，不 import LiveKit SDK；
真实实现在 `infra/`，由 `app.py` 组装。

### 6.2 Connector（`connector/connector/`）

| 模块 | 职责 |
| --- | --- |
| `local/remote_desktop/host.py` | `ScreenHostSupervisor`：定位/校验/启动 `aa-screen-host`，stdio JSON-RPC，心跳，崩溃重启（指数退避，最多 3 次/分钟） |
| `local/remote_desktop/discovery.py` | host 不可用时的降级探测（平台、显示服务器类型、权限状态） |
| `local/remote_desktop/policy.py` | 设备设置映射（是否允许控制、是否需要确认、是否允许无人值守）、本机确认弹窗回调 |
| `server/remote_desktop_rpc.py` | `remoteDesktop.*` RPC 分发；把 host 的进度/统计转成 Connector 通知 |
| `server/dispatch.py` | 注册 `RemoteDesktopRpcHandler`（与 `LocalRpcHandler`、`RuntimeRpcHandler` 并列） |
| `core/config.py` | `remoteDesktop.enabled`、`hostPath`、`maxSessions`、`preferredEncoder` |

### 6.3 Screen Host（新顶层目录 `screen-host/`，Rust）

```text
screen-host/
  Cargo.toml
  src/main.rs            stdio JSON-RPC 循环（与 Connector 的 HostControl 协议）
  src/config.rs          质量参数、显示器选择、编码器优先级
  src/capture/
    mod.rs               CaptureSource trait：start/stop/next_frame/display_info
    windows.rs           DXGI Desktop Duplication（windows-capture）
    macos.rs             ScreenCaptureKit（macOS 12.3+）
    linux.rs             PipeWire portal（Wayland）/ X11（XShm）
  src/encode/
    mod.rs               VideoEncoder trait
    libwebrtc.rs         通过 LiveKit Rust SDK 的 NativeVideoSource 发布原始 I420（v1 软件编码，默认 VP8）
    hw/                  可选：Media Foundation / VideoToolbox / VA-API（P4，需自管编码与打包）
  src/publish.rs         LiveKit Room 加入、发布 screen track、Data Packet 收发
  src/input/
    mod.rs               InputEvent → 平台事件
    windows.rs           SendInput（含 Unicode 文本、多显示器坐标）
    macos.rs             CGEvent + Accessibility 权限
    linux.rs             uinput / XTest / libei
  src/protocol.rs        InputEvent / Stats / State 的 serde 模型（与 contracts 对齐）
  packaging/             Windows zip、macOS universal .app、Linux tar.gz
```

Host 与 Connector 之间的 stdio 协议（内部，不对外）：

```json
{"id": 7, "method": "host.start", "params": {"sessionId": "rds_1", "displayId": "0", "mode": "control",
  "quality": {"maxFps": 30, "maxBitrateKbps": 4000, "maxWidth": 1920},
  "media": {"url": "wss://rtc.example.com", "room": "rd_rds_1", "token": "eyJ...", "identity": "host:conn_1"}}}
{"id": 7, "result": {"accepted": true}}
{"event": "state", "params": {"sessionId": "rds_1", "state": "publishing"}}
{"event": "stats", "params": {"sessionId": "rds_1", "fps": 29.4, "bitrateKbps": 2600, "rttMs": 41,
  "packetLossPct": 0.3, "codec": "vp8", "width": 1920, "height": 1080, "encoder": "libwebrtc-sw"}}
```

### 6.4 客户端

| 端 | 新增模块 | 依赖 | 状态 |
| --- | --- | --- | --- |
| Web (`web-next/`) | `lib/remote-desktop/{api,input,use-remote-desktop}.ts`、`components/remote-desktop/*`、路由 `#/device/:connectorId/remote` | `livekit-client` | 已实现 |
| Desktop (`desktop-workbench/`) | 复用 Web 的渲染层；本机「被控中」提示条 + always-on-top 悬浮窗 + macOS 菜单栏项 | 同 Web | 已实现 |
| Android (`android/`) | `api/RemoteDesktop{Api,Dtos}.kt`、`feature/remotedesktop/{RemoteDesktopSessionController,MediaSession,RemoteDesktopEventStream}`、`feature/remotedesktop/input/*`、`livekit/LiveKitMediaSession`、`ui/screens/remotedesktop/*` | `io.livekit:livekit-android` | 已实现，仅编译/单测验证 |
| iOS (`ios/`) | `API/V2/V2RemoteDesktopAPI.swift`、`Business/RemoteDesktopService.swift`、`Models/RemoteDesktopModel.swift`、`Views/Remote/RemoteDesktopView.swift` | `LiveKitClient`（SwiftPM） | 未开始（需要 macOS + Xcode） |

### 6.5 部署（`docker/`）

新增 `livekit` 服务与 `livekit.yaml`，见 §11。Server 通过环境变量接入；未配置 LiveKit 时
`remote-desktop` 能力为 `supported=false`，UI 自动隐藏（能力驱动）。

---

## 7. 接口定义

**wire 契约以 [`contracts/remote-desktop/1.0`](../contracts/remote-desktop/1.0/README.md) 的 schema 为准**；
HTTP 端点、状态、事件与错误语义见 [远程桌面 API](api/remote-desktop.md)。本节只记录架构层面的
约定，不再重复字段定义，避免文档与 schema 分叉。

### 7.1 资源与状态机

```text
RemoteDesktopSession
  id                     rds_<random>
  connectorId            conn_x
  mode                   view | control
  state                  starting | awaiting_consent | live | degraded | interrupted | ended
  controller             viewer:pt_<participantId> | null
  controllerLeaseExpiresAt
```

```text
                    ┌───────────┐
   create ─────────►│ starting  │
                    └─────┬─────┘
        ┌─────────────────┼──────────────────────┐
        │ 需要确认         │ host 直接发布          │ 拒绝/超时
        ▼                 ▼                      ▼
 ┌────────────────┐    ┌──────┐             ┌───────┐
 │awaiting_consent│───►│ live │             │ ended │
 └────────────────┘    └──┬───┘             └───────┘
        granted            │  ▲
                           ▼  │ 恢复
                      ┌──────────┐
                      │ degraded │
                      └────┬─────┘
                           │ host/全部观看者掉线
                           ▼
                    ┌─────────────┐  90 s 内重连
                    │ interrupted │──────────────► live
                    └──────┬──────┘
                           │ 超时 / 主动结束
                           ▼
                        ┌───────┐
                        │ ended │  终态，记录 endReason
                        └───────┘
```

- `starting`：已下发 RPC，host 尚未确认发布。
- `awaiting_consent`：设备侧需要人工确认（控制会话且 `requireConsent=true`、未开启无人值守）。
- `live`：host 已发布 track，且至少一个观看者已订阅（webhook 判定）。
- `degraded`：连续 10 s `fps < 10` 或 `packetLoss > 8%`，仅影响展示，不改变可用性。
- `interrupted`：host 或全部观看者掉线，保留 90 s 等待重连；超时转 `ended`。
- `ended` 的 `endReason` 见契约的 `session.schema.json`。

### 7.2 HTTP API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/connectors/{id}/remote-desktop` | 能力 + host facts |
| POST | `/remote-desktop/sessions` | 创建或加入会话（每个 Connector 同时只有一个活跃会话） |
| GET | `/remote-desktop/sessions` | 列表 |
| GET | `/remote-desktop/sessions/{id}` | 查询 |
| POST | `/remote-desktop/sessions/{id}/token` | 续签 viewer/controller token |
| POST | `/remote-desktop/sessions/{id}/take-control` | 抢占控制权 |
| POST | `/remote-desktop/sessions/{id}/release-control` | 释放控制权 |
| POST | `/remote-desktop/sessions/{id}/heartbeat` | 续租 |
| POST | `/remote-desktop/sessions/{id}/end` | 结束（幂等） |
| POST | `/remote-desktop/sessions/{id}/ticket` | 续签 WS ticket |
| POST | `/remote-desktop/sessions/{id}/screenshot` | 已建会话的单帧截图（2 s/设备） |
| WS | `/remote-desktop/sessions/{id}/ws` | 控制面事件 + 客户端控制帧 |
| POST | `/internal/livekit/webhook` | LiveKit 服务端回调 |

创建会话在已有活跃会话时是 **加入**：返回同一个 `session.id` 与新的 `participantId`；控制模式按
同账号 takeover 语义抢占。`mode=control` 且需要确认时返回 `state=awaiting_consent`、`media=null`，
**确认前不下发任何媒体 token**；确认后客户端调 `/token` 取短 TTL controller token。

`participantId` 由 Server 生成并绑定 session + user；LiveKit identity 为 `viewer:{participantId}`，
`session.controller` 存完整 identity。host 用 `DataReceived.participant.identity` 校验输入，不接受
客户端自报身份。

### 7.3 实时事件（WebSocket）

六类事件统一 `{type, payload}`，payload 必带 `sessionId`：`subscribed`、`session.updated`、
`stats.updated`、`control.revoked`、`ended`、`error`。客户端只发 `ping` / `request_keyframe` /
`update_quality` 三种控制帧，媒体与输入不经过这条 WS。

断线重连带 `cursor`：服务端保留每会话最近 256 条事件（90 s），命中则补发缺口，未命中则重发
`subscribed` + 当前 `session.updated` 快照。

### 7.4 Connector RPC 与通知

| 方法 | 参数要点 | 返回 |
| --- | --- | --- |
| `remoteDesktop.hostInfo` | `{}` | `HostFacts` |
| `remoteDesktop.start` | `{sessionId, displayId, mode, quality, media, consent:{required,timeoutMs}, controllerIdentity, participantId}` | `{accepted, state}` |
| `remoteDesktop.stop` | `{sessionId, reason}` | `{stopped}` |
| `remoteDesktop.updateQuality` | `{sessionId, maxFps?, maxBitrateKbps?, maxWidth?}` | `{applied}` |
| `remoteDesktop.control.grant` | `{sessionId, controllerIdentity, epoch, controllerLeaseExpiresAt}` | `{applied}` |
| `remoteDesktop.requestKeyframe` | `{sessionId}` | `{requested}` |
| `remoteDesktop.releaseAll` | `{sessionId?}` | `{releasedKeys, releasedButtons}` |
| `remoteDesktop.screenshot` | `{displayId?, maxWidth?, format, quality}` | `{dataBase64, width, height, capturedAt}` |
| `remoteDesktop.input` | `{sessionId, events}` | `{appliedSeq}`（兜底通道） |

Connector → Server 通知：`remoteDesktop.state/stats/displays/consent/clipboard/error`，经现有
notification 管道入库；**未知的 `remoteDesktop.*` 必须返回 `unsupported_notification`**，不能静默
accepted。`rd_*` 是 Connector 内部错误码，Server 映射为 `remote_desktop_*` 后返回客户端。

### 7.5 媒体面（LiveKit）

| 项 | 规则 |
| --- | --- |
| room 名 | `rd_{sessionId}`，一次性，会话结束即删除 |
| 身份 | 主机 `host:{connectorId}`；观看者 `viewer:{participantId}`；一个 room 只有一个 host |
| host token grants | `roomJoin=true, canPublish=true, canSubscribe=false, canPublishData=true` |
| viewer token grants | `roomJoin=true, canPublish=false, canSubscribe=true, canPublishData=<role==controller>` |
| track | 1 路 video，`name="screen"`，`source=SCREEN_SHARE`，`simulcast=false`，`contentHint=text`；v1 软件编码 VP8 |
| 数据包 topic | `aa.input`（reliable）、`aa.pointer`（lossy）、`aa.input.ack`（reliable） |
| token TTL | viewer 600 s，controller 120 s |
| webhook | `POST /api/v2/internal/livekit/webhook`，校验 JWT + raw body sha256 + `event.id` 去重 |

自托管 LiveKit 不支持 JWT 吊销。抢占时 Server 用 `UpdateParticipant(canPublishData=false)` 把旧
controller 降权，host 用 controller identity epoch 丢弃旧输入；`RemoveParticipant` 只用于策略吊销
与会话结束。

开发/测试允许 `provider=noop`：创建响应 `media=null`，只验证控制面；生产未配置 LiveKit 时能力
`supported=false`，功能整体隐藏。

### 7.6 输入协议（Data Packet）

统一版本号 `v=1`。客户端 → 主机：`aa.input`（reliable/ordered，JSON）承载 key/text/clipboard/
ping/低频 pointer；`aa.pointer`（lossy/unordered，12 字节二进制）承载高频移动。主机 → 客户端：
`aa.input.ack` 承载 ack/reject，`aa.host.control` 承载剪贴板 offer/denied。

`aa.pointer` 布局（小端，12 字节）：type u8=0x01、flags u8、buttons u16、x u16、y u16、seq u32；
x/y 归一化 `0..65535`，seq 按 u32 回绕。十六进制测试向量见
[`contracts/remote-desktop/1.0/pointer-frame.md`](../contracts/remote-desktop/1.0/pointer-frame.md)。
`aa.input` 的 JSON `seq` 与 `aa.pointer` 的 `seq` 是两个独立计数器。

### 7.7 剪贴板

v1 只支持 `text/plain`，≤64 KiB，双向，默认关闭。≤8 KiB 原文可直接放进单条 `clipboard` 事件；
更大的内容用 `clipboard-transfer` 分片（≤8 KiB/片、最多 8 片、每方向单飞、10 s 超时），不在
HTTP/RPC 上传输，避免 Server 看到内容。

### 7.8 能力与设备设置

能力 id：`connector.remote_desktop.view/control/clipboard`，独立于 `ProtocolCapabilitySet`，
每个对象带单调递增 `revision`。设备设置 7 项及默认值：

```text
remoteDesktopEnabled=false、remoteDesktopAllowControl=false、remoteDesktopRequireConsent=true、
remoteDesktopAllowUnattended=false、remoteDesktopClipboard=false、
remoteDesktopMaxFps=30（5–60）、remoteDesktopMaxBitrateKbps=4000（500–20000）
```

`remoteDesktopRequireConsent` 只约束控制会话；观看由 `remoteDesktopEnabled`（本机显式开启）承担，
host 从开始发布起必须显示常驻悬浮条。

### 7.9 数据库与 Redis

PostgreSQL 表 `remote_desktop_sessions` 是会话事实源（TEXT + JSON 字符串，迁移 `v2_56`）。不存
媒体 token、输入事件、剪贴板内容。`connector_id`/`user_id` 不加外键，删除设备/用户时保留审计行。

Redis 只做可重建状态：

```text
rd:session:{id}:eventSeq         事件序号（TTL ≥ 8h + grace）
rd:session:{id}:events           最近 256 条事件环形缓冲
rd:session:{id}:sub:{pid}        participant 在线（TTL 90s）
rd:session:{id}:stats            最近 30 个 stats 样本
rd:cache:hostinfo:{connectorId}  host facts（TTL 5s）
```

跨 worker 扇出用 `RedisCoordinator.channel("rd-events", sessionId)`，与 `TerminalStreamHub` 同模式。

### 7.10 错误码

HTTP 错误统一 `{"detail": {"code", "message", "details"}}`。对客户端的 code：

```text
remote_desktop_unavailable    409  能力不可用，看 details.reason
remote_desktop_busy           409  控制权被占
remote_desktop_limit_reached  429  观看者/会话上限
remote_desktop_consent_pending 409 等待设备确认
remote_desktop_consent_denied 403  设备侧拒绝
remote_desktop_consent_timeout 408 60s 未确认
remote_desktop_not_controller 409  不是当前控制者
remote_desktop_rate_limited   429  限流，带 Retry-After
media_unavailable             503  LiveKit 不可达/未配置
media_token_expired           401  重新取 token
remote_desktop_not_found      404  会话不存在或不属于当前用户
```

`rd_host_not_running`、`rd_permission_denied`、`rd_display_not_found`、`rd_unsupported_platform`
等 `rd_*` 只作为 Connector 内部码，Server 必须映射后返回。

## 8. 手机端设计要点

### 8.1 触摸 → 鼠标映射

| 手势 | 行为 |
| --- | --- |
| 单指轻点 | 左键单击（在指针位置） |
| 单指拖动 | 指针移动（相对模式，灵敏度可调） |
| 长按 500 ms | 右键单击 |
| 双指上下滑 | 滚轮 |
| 双指捏合 | 画面缩放（本地渲染层，不改变远端分辨率） |
| 双击 | 进入「拖拽锁定」：按下 → 移动 → 再次双击释放 |
| 三指下滑 | 呼出键盘 |
| 悬浮按钮 | Ctrl / Alt / Shift / Win / Esc / Tab 修饰键锁 |

渲染层用 `AspectFit` + 可平移缩放，输入坐标要经过「视图变换逆矩阵」再归一化——这是最容易出现
「点不准」的地方，需要单元测试覆盖缩放/偏移组合。

### 8.2 键盘

- 软键盘：`text` 事件发送 IME 提交的整段文本；退格/回车/方向键映射为 `key` 事件（code: `Backspace`/`Enter`/`ArrowUp`…）。
- 硬件键盘：Android 走 `onKeyDown/onKeyUp` + `KeyEvent.keyCode → W3C code` 映射表；iOS 走
  `pressesBegan/pressesEnded` + `UIKey.keyCode`。
- 修饰键用「粘滞」方式：点一次 Ctrl 生效一次，避免手机上没有组合键。

### 8.3 网络与生命周期

- Wi-Fi ↔ 蜂窝切换：LiveKit SDK 自带 ICE restart / 重连；客户端监听 `Reconnecting` 状态并显示浮层，
  超过 15 s 提示「网络不稳定」。
- 后台：Android 进入 `onStop` 后停止渲染但保持 30 s 重连窗口；iOS 后台立即断开（系统限制），
  回前台重新 join（用现有 token 或续签）。
- 画中画：Android `PictureInPictureParams`、iOS `AVPictureInPictureController`（基于 `AVSampleBufferDisplayLayer`），P3 再做。
- 锁屏/来电：暂停输入，恢复后重新订阅。

### 8.4 质量策略

| 场景 | 参数 |
| --- | --- |
| 默认（手机竖屏） | 30 fps，maxWidth 1600，maxBitrate 3000 kbps |
| 文本/代码阅读 | 30 fps，`contentHint=text`，优先清晰度（`degradationPreference=maintain-resolution`） |
| 快速滚动/视频 | 45–60 fps，`maintain-framerate` |
| 弱网（RTT>200ms 或丢包>5%） | 自动降到 15 fps / 1200 kbps，客户端可手动「流畅/清晰」 |
| 只观看（预览） | 5 fps，800 kbps |

`update_quality` 由客户端发起、Server 转发 `remoteDesktop.updateQuality`，host 通过
LiveKit `setParameters` 调整编码目标码率。

---

## 9. 安全与隐私

### 9.1 身份与授权

- 复用现有 Bearer token / WS ticket；远程桌面不引入新账号体系。
- 设备归属校验：只有设备 owner（或同账号被授权成员）能创建会话；跨账号一律 404。
- 每设备并发：同一会话最多 1 个控制者 + 3 个观看者（`maxViewersPerSession`）；每用户默认 4 个活动 participant。
- 设备被删除/解绑时，Server 立即 `remoteDesktop.stop` 并吊销 token。

### 9.2 媒体与输入权限

- 观众 token `canPublish=false`：不能推流；
- 观众 token `canPublishData=false`：在 SFU 层就发不出输入；
- 控制者 token 由 `take-control` 单独签发，且 host 端还会用 Server 下发的 `controllerIdentity`
  二次校验 `from` 身份——**双重闸门**，避免只依赖 token；
- host 拒绝任何 `d` 越界、坐标越界、频率超限（>120/s）的事件。

### 9.3 被控端可见性

- 开始共享时在本机显示常驻悬浮条「Agents Anywhere 正在共享屏幕 · 结束共享」；
- macOS 菜单栏录制指示、Windows 托盘图标同步显示；
- `requireConsent=true` 时弹原生确认框（默认允许 30 s 内确认）。Windows CLI Connector 可设置
  `AGENT_CONNECTOR_REMOTE_DESKTOP_CONSENT_PROMPT=windows` 使用原生 MessageBox 确认；Desktop
  当前在窗口内显示共享提示条，always-on-top 悬浮窗/菜单栏项仍是后续工作；
- 控制权变更时在悬浮条上提示「viewer:pt_… 已接管控制」。

### 9.4 传输与加密

- 媒体：DTLS-SRTP（WebRTC 强制），SFU 可见明文媒体；
- 控制面：HTTPS/WSS；
- token：JWT，HS256，600 s，room 级最小权限；
- 可选 E2EE（P4）：LiveKit E2EE（SFrame），密钥由 Server 生成并通过控制面分发给 host 与已授权观众；
  开启后 SFU 与录制不可用，需在设备设置中显式开启。

### 9.5 审计

- 会话记录：谁、何时、哪台设备、哪个显示器、观看还是控制、时长、结束原因；
- 可选写入关联会话的时间线（`remote.desktop.session` 条目，只含元数据，不含画面）；
- 输入事件不落库（隐私 + 体积），只保留统计。

---

## 10. 性能与容量

### 10.1 延迟预算（同城、4G/5G）

| 阶段 | 典型 | 说明 |
| --- | --- | --- |
| 采集 | 5–16 ms | DXGI/SCK 一帧 |
| 编码 | 5–20 ms | 硬件编码；软编 15–35 ms |
| 网络（单程） | 15–60 ms | SFU 中转，含 TURN |
| 抖动缓冲 | 10–40 ms | 屏幕内容可调低 |
| 解码 | 5–15 ms | 手机硬解 H.264 |
| 渲染 | 8–16 ms | 60 Hz 屏 |
| **合计** | **~60–170 ms** | 目标 P95 < 200 ms |

### 10.2 带宽

| 场景 | 码率 |
| --- | --- |
| 1080p 静态文档 | 0.3–1 Mbps |
| 1080p 滚动/打字 | 1.5–3 Mbps |
| 1440p 视频播放 | 4–8 Mbps |
| 手机竖屏 720p | 0.8–2 Mbps |

主机上行 = 单路（发到 SFU）；服务器下行 = 观看者数 × 码率。10 个并发观看 3 Mbps ≈ 30 Mbps 出向。

### 10.3 容量与限制

- 单 SFU 节点（4 vCPU / 8 GB）可承载约 50–80 路 1080p30 转发（受带宽限制更大）；
- 单 Connector 同时 1 路采集（v1），多会话共享同一路编码（同一显示器）；
- 每会话最长 8 h，空闲 15 min（无输入且无观看者）自动结束。

---

## 11. 部署与运维

### 11.1 LiveKit 配置（`docker/livekit.yaml`）

```yaml
port: 7880
rtc:
  tcp_port: 7881
  port_range_start: 50000
  port_range_end: 50100
  use_external_ip: false   # Docker bridge/NAT 下改 rtc.ips 或 host network
redis:
  address: redis-next:6379 # compose 里的实际服务名
keys:
  aa_remote_desktop: ${LIVEKIT_API_SECRET}
webhook:
  api_key: aa_remote_desktop
  urls:
    - http://server:8000/api/v2/internal/livekit/webhook
turn:
  enabled: true
  domain: ${LIVEKIT_TURN_DOMAIN}
  tls_port: 5349           # 前置 4 层负载均衡时对外暴露 443
  udp_port: 3478
  cert_file: /etc/livekit/turn.crt
  key_file: /etc/livekit/turn.key
prometheus_port: 6789
logging:
  level: info
```

完整文件见 [`docker/livekit.yaml`](../docker/livekit.yaml)；`docker/docker-compose.postgres.yml`
提供 `remote-desktop` profile 的 `livekit` 服务。

防火墙：`7880/tcp`（信令）、`7881/tcp`（WebRTC over TCP 回退）、`50000-50100/udp`（媒体）、
`5349/tcp` + `3478/udp`（TURN）。企业网只放行 443 时，把 TURN/TLS 放在 443。

### 11.2 Server 环境变量

```text
AGENT_SERVER_REMOTE_DESKTOP_ENABLED=true
AGENT_SERVER_LIVEKIT_URL=wss://rtc.example.com
AGENT_SERVER_LIVEKIT_API_KEY=aa_remote_desktop
AGENT_SERVER_LIVEKIT_API_SECRET=<secret>
AGENT_SERVER_REMOTE_DESKTOP_TOKEN_TTL_SECONDS=600
AGENT_SERVER_REMOTE_DESKTOP_CONTROLLER_TOKEN_TTL_SECONDS=120
AGENT_SERVER_REMOTE_DESKTOP_MAX_VIEWERS_PER_SESSION=3
AGENT_SERVER_REMOTE_DESKTOP_IDLE_TIMEOUT_SECONDS=900
AGENT_SERVER_REMOTE_DESKTOP_SWEEP_SECONDS=5
```

未配置 `LIVEKIT_URL` 时特性整体关闭，`remote-desktop` 能力返回 `supported=false`，不影响现有部署。

### 11.3 监控

Server 暴露 Prometheus 文本指标：`GET /api/v2/metrics`。默认关闭，需要：

```text
AGENT_SERVER_METRICS_ENABLED=true
AGENT_SERVER_METRICS_TOKEN=<scrape token>   # 可选；设置后要求 Authorization: Bearer <token>
```

| 指标 | 类型 | 来源 |
| --- | --- | --- |
| `rd_sessions_active{instance}` | gauge | 抓取时读 PostgreSQL，跨 worker 准确 |
| `rd_sessions_total{mode,instance}` | counter | 本进程创建的会话 |
| `rd_sessions_ended_total{reason,instance}` | counter | 本进程结束的会话 |
| `rd_session_duration_seconds{instance}` | histogram | 结束时记录，桶 30s…8h |
| `rd_errors_total{code,instance}` | counter | 推送给客户端的错误 |
| `rd_fps` / `rd_bitrate_kbps` / `rd_packet_loss_pct` / `rd_input_rtt_ms` | gauge | host 最近一次 stats 样本 |
| `livekit_room_participants`, `livekit_participant_*` | gauge | LiveKit 自身 Prometheus（`prometheus_port: 6789`） |

计数器是每进程的（带 `instance` 标签），多 worker 部署用 `sum by (...)` 聚合；`rd_sessions_active`
是抓取时从数据库算出来的，无需聚合。

告警建议：`rd_host_not_running` 连续 3 次、`media_unavailable` 持续 2 min、单节点转发带宽 > 70%。

---

### 11.4 端到端验证脚本

真实进程级的验证不依赖 Server 的数据库，可以单独跑：

| 脚本 | 覆盖 | 前置 |
| --- | --- | --- |
| `screen-host/scripts/e2e-livekit.py` | host 的 NDJSON 协议 + 真实 LiveKit 发布：`hello`（`arch=x86_64`）、`host.start`、`starting→running→stopping→stopped`、退出码 0 | `livekit-server --dev --bind 127.0.0.1`；`screen-host/scripts/build-livekit.ps1` 构建 |
| `server/scripts/e2e_media_plane.py` | Server 签发的 host grant 被真实 host 接受，SFU 侧看到 `host:<connectorId>` 与名为 `screen` 的 `SCREEN_SHARE` VP8 轨（`simulcast=false`），会话结束后 `delete_room` 成功 | 同上；用 `server/.venv` 的 Python 运行 |
| `server/scripts/e2e_full_chain.py` | **整链**：真实 Server（PostgreSQL + 迁移）↔ 真实 Connector 进程 ↔ 真实 host ↔ 真实 LiveKit。走完配对 → 连接上线 → 设备设置 → 创建 control 会话（`starting→live`）→ SFU 看到 host 的 `screen` 轨 → 结束会话 → 房间清空；第二阶段把设备改成「必须本机确认」，验证没有交互界面时创建请求返回 `permission_denied`、会话记为 `consent_denied`、房间无参与者；第三阶段开一个 view 会话并连真控制面 WebSocket，确认收到 `remote_desktop.subscribed` 与 `remote_desktop.session.updated`；第四阶段让第二个客户端加入、夺取控制权、被抢回、再释放，确认 `controller` 字段随之变化且被顶掉的客户端收到 `remote_desktop.control.revoked` | `livekit-server --dev`；`AGENT_E2E_DB_URL` 指向名字以 `_e2e` 结尾的库（脚本拒绝其他名字）；`--fresh` 会先删库重建，用来确认可复现 |

两个脚本都在 2026-11-13 本地通过。整链脚本还支持把 Web 客户端一起验证：

```bash
cd web-next && NEXT_OUTPUT=export yarn build
AGENT_E2E_STATIC_DIR=$PWD/web-next/out python -X utf8 server/scripts/e2e_full_chain.py --fresh --browser
```

`--browser` 会启动本机 Chrome/Edge 无头实例（CDP 直连，不依赖 Playwright），预置登录态后打开
`#/device/<connectorId>/remote`，断言页面渲染出「远程桌面」、点「申请控制」后出现「控制中」、浏览器里的
`<video>` 解出真实画面（合成采集下为 640×360、`readyState=4`），并检查 LiveKit 房间里多出 `viewer:`
参与者。合成采集由 `AA_SCREEN_HOST_SYNTHETIC_CAPTURE` 打开（Connector 侧是
`AGENT_CONNECTOR_REMOTE_DESKTOP_SYNTHETIC_CAPTURE`），所以没有交互式桌面也能验证整条媒体链路。`--web-hold-seconds N` 则只是把会话挂着，方便人手点一遍。
这条路径暴露过一个只在真浏览器里才出现的问题（`device-remote` 不在页面白名单里，路由被回退成
首页），已在 Web 与 Desktop 两端修复并补了回归测试。注意 `cargo test`/`cargo build`（不带 `--features livekit`）会覆盖
`target/debug` 里的二进制，跑之前重新执行一次 `build-livekit.ps1`。

## 12. 实施计划

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| **P0（1 周）** | `contracts/remote-desktop/1.0`、能力对象、`GET /connectors/{id}/remote-desktop`、特性开关 | 未装 host 时返回 `supported=false` 且不影响现有功能；契约 fixture 校验通过 |
| **P1（3–4 周）** | Server 会话域 + Connector RPC + host（Windows/macOS 采集编码发布）+ Web 观看 | Web 能看 Windows/macOS 桌面，P95 延迟 < 250 ms，断线 30 s 内恢复 |
| **P2（2–3 周）** | 输入注入 + 控制权租约 + 手势/键盘（Web + Android） | Android 手机可完成点击、滚动、输入中文；控制权抢占生效 |
| **P3（2 周）** | iOS 客户端、剪贴板、多显示器、质量档位、PiP | iOS 实机 30 min 会话稳定；剪贴板双向可用 |
| **P4（按需）** | 硬件编码（MF/VideoToolbox/VA-API）、Linux Wayland（libei）、E2EE、录制 | 1080p60 时 CPU < 15%；Wayland 下输入可用 |

每阶段都要补：契约 fixture、Server 单测（假媒体 provider）、Connector 单测（假 host）、
Web Playwright 用例（假 publisher），以及真机验证清单（Windows 11 / macOS 14 / Android 14 / iOS 17）。

---

## 13. 风险与备选

### 13.1 主要风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| LiveKit 多一个服务，部署门槛上升 | 自托管用户不愿开 UDP 端口 | 默认关闭特性；提供 TURN/TLS 443；文档给最小防火墙清单；后续可接 LiveKit Cloud |
| 软编 CPU 高（1080p30 占 0.5–1 核） | 工作机变慢 | P1 限制 1600 宽/24 fps；P4 上硬件编码；提供「流畅模式」 |
| macOS 权限（屏幕录制/辅助功能） | 首次使用失败 | host 提供 `permissions` 状态 + 一键跳转系统设置；Connector 首次启动引导 |
| Wayland 输入注入受限 | Linux 桌面不可控 | 优先 libei/portal；不支持时降级为「只看」并在能力里说明 |
| 移动端后台断流 | 体验中断 | 明示「切后台会断开」；P3 做 PiP |
| 企业代理只放行 HTTP(S) | host/客户端连不上 LiveKit 的 UDP | 部署 TURN/TLS 443；host 支持 `forceRelay` 配置（只走 TURN）；代理配置沿用 Connector 的 `NetworkEgress` |
| SFU 带宽成本 | 自托管流量大 | 码率上限、单会话观看者上限、`maxBitrate` 自适应 |

### 13.2 备选路径（不改变客户端契约）

1. **P2P 直连 + coturn**：主机与观看者在同一局域网时省掉 SFU 一跳。保留 `media.provider` 字段，
   增加 `webrtc-direct` provider 即可；Server 需要新增一个极小的 SDP/ICE 中转（约 300 行）。
2. **GStreamer + WHIP 发布**：主机侧换成 `gst-launch` 管道（`d3d11screencapturesrc ! mfh264enc ! whipclientsink`），
   用硬件编码换 CPU。代价是输入不能再走 Data Packet，需要启用 `remoteDesktop.input` 兜底 RPC。
   `media.provider=livekit-whip`，客户端无感。
3. **Guacamole 适配器**：对必须用 RDP/VNC 的目标，用 guacd 转成媒体源；只在有明确客户需求时做。

---

## 14. 版本与兼容性

| 项 | 变化 |
| --- | --- |
| 产品版本 | 2.3.0（新增跨端接口，MINOR） |
| API | `/api/v2` 下新增 `/remote-desktop/*`；现有接口不变 |
| 契约 | 新增 `contracts/remote-desktop/1.0`，与 `protocol/1.1` 独立演进 |
| 数据库 | `v2_56` 新增 `remote_desktop_sessions` |
| Connector | 新增可选 host 子进程；旧 Connector 不实现 `remoteDesktop.*`，Server 返回 `supported=false` |
| 客户端 | Web/Desktop/Android/iOS 需同步升级到 2.3.0 才能使用；未升级端不影响现有功能 |
| 部署 | 新增可选 LiveKit 服务；不配置则特性关闭 |

兼容策略：`remoteDesktop.hostInfo` 在旧 Connector 上返回 `method not found`，Server 捕获后映射为
`supported=false`，不报 500；这与 `connector.deviceSettings.apply` 的兼容处理一致。

---

## 15. 参考资料

- LiveKit 自托管与 TURN：<https://docs.livekit.io/transport/self-hosting/deployment/>
- LiveKit Ingress / WHIP：<https://docs.livekit.io/transport/media/ingress-egress/ingress/>
- LiveKit Data Packet：<https://docs.livekit.io/transport/data/packets/>
- LiveKit Android SDK：<https://github.com/livekit/client-sdk-android>
- RustDesk：<https://github.com/rustdesk/rustdesk>
- Sunshine：<https://github.com/LizardByte/Sunshine>
- Apache Guacamole：<https://guacamole.apache.org/>
- GStreamer 1.24 release notes（`whipclientsink`）：<https://gstreamer.freedesktop.org/releases/1.24/>
- GStreamer `d3d11screencapturesrc`：<https://gstreamer.freedesktop.org/documentation/d3d11/d3d11screencapturesrc.html>
- libei（Wayland 输入模拟）：<https://gitlab.freedesktop.org/libinput/libei>
- enigo（Rust 输入模拟）：<https://github.com/enigo-rs/enigo>
- WHIP RFC 9725：<https://datatracker.ietf.org/doc/rfc9725/>


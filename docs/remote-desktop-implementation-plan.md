# 远程桌面实现计划（Checklist）

状态：审查后修订版，2026-11-13。
依据：[远程桌面架构方案](remote-desktop.md)、[`contracts/remote-desktop/1.0`](../contracts/remote-desktop/1.0/README.md) 与仓库现有实现。
审查方式：契约、Server、Connector/Screen Host、客户端/部署、安全五个方向独立只读审查；契约校验脚本实测通过 `OK 12 fixtures`。

## 交付状态一览

| 阶段 | 状态 | 主要证据 | 阻塞原因 |
| --- | --- | --- | --- |
| P0 契约 1.0 | ✅ 冻结 | `validate.py` → 28 schema / 62 valid / 25 invalid；CI `contracts` job | — |
| P1 Server 控制面 | ✅ 实现 + 真实联调 | **143** 个 RD/指标/设置/架构用例；`e2e_full_chain.py` 用真实 Connector 跑通配对→创建→consent→WS→控制权转移→结束 | 多 worker + Redis 的真实部署未验证 |
| P2 Connector / Screen Host | 🟡 主体完成 | **36 lib + 16** Rust 测试 + clippy；host↔LiveKit 与整链发布均实测（合成采集下浏览器解出 640×360 画面）；截图支持 JPEG/PNG；打包脚本 + sha256 校验；`acceptance.py` 真机检查清单 | **真实 WGC 采集**需要交互式桌面；签名/公证需要证书 |
| P3 Web / Desktop | ✅ 实现 + 脚本化浏览器验证 | Web **507**、Desktop renderer **593**、Electron main 172、typecheck、静态构建；`e2e_full_chain.py --browser` 用无头 Chrome（CDP，无需 Playwright）验证：远程桌面页渲染、申请控制→控制中+修饰键条、`<video>` 640×360/`readyState=4`、释放控制回退、点「截图」下载真实 JPEG（38 KB）；Desktop 悬浮窗/菜单栏项；两端修掉过「远程桌面路由回退首页」的缺陷 | Desktop 客户端本身的真机 e2e 未跑；浏览器画面来自合成采集 |
| P4 Android | 🟡 除真机 | **388** 单测、`:app:lintDebug` 0 error、`assembleDebug`、`androidTest` 编译、三个 gate；LiveKit 渲染、手势/键盘、控制权、截图（FileProvider 分享）已接 | **真机观看/手势/中文输入/分享面板**验收需要设备 |
| P5 iOS | ⛔ 未开始 | — | 需要 macOS + Xcode，当前环境无法编译 |
| P6 部署 | 🟡 文档与样例 | LiveKit compose/yaml、Prometheus 采集与告警样例（`monitoring` profile）、nginx/Caddy 反代样例、版本与发布说明 | 真实 `docker compose up`、TURN、4G/企业网 ICE 未验证 |
复现全部 headless 检查与端到端验证：

```bash
python contracts/remote-desktop/1.0/validate.py
cd server && uv run pytest -q -k "remote_desktop or metrics or device_settings or architecture"
cd connector && uv run pytest -q -k "remote_desktop or device_settings or config"
cd screen-host && cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test
cd web-next && yarn test && yarn typecheck
cd desktop-workbench && yarn test:main && cd renderer && yarn test && yarn typecheck
cd android && ./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
# 端到端（需要 livekit-server --dev）；加 --browser 会驱动无头 Chrome 走一遍 Web 客户端
python -X utf8 server/scripts/e2e_full_chain.py --fresh --browser
```

## 实现进度

按本文后面各阶段的条目逐项记录状态。**勾选表示条目与验收标准都已满足**；仅部分满足的条目保持
未勾选，并在下面「部分完成」表里写清缺口，避免把半成品读成已完成。

### 已完成

- **P0 契约冻结**：`contracts/remote-desktop/1.0` 有 28 个 schema、62 份 valid fixture、25 份
  invalid fixture；`validate.py` 校验 schema 元数据、manifest sha256/slug/direction 与 fixture
  目录布局，已接入 `.github/workflows/ci.yml` 的 `contracts` job。决策记录见
  [远程桌面 v1 决策记录](remote-desktop-decisions.md)。
- **P1 Server 控制面（RD-P1-01…15）**：`v2_56` 迁移、领域模型/状态机、仓储、7 项设备设置、
  媒体 provider、LiveKit token 与 webhook、HTTP 路由、WS ticket 与双工 WS、通知 handler、broker、
  维护 sweeper、删除清理、限流与审计均已落地。
- **P2-08 工程骨架、P2-09 握手与协议（除 `host.cancel`）**：`screen-host/` 的 NDJSON 循环、
  `protocol.rs`、握手失败退出码 78、坏帧 `rd_host_protocol_error`、重复 start 幂等都有测试。
- **P2-15 host CI**：`.github/workflows/ci.yml` 增加 `screen-host/*` 触发与 Windows/macOS/Linux
  的 fmt/clippy/test job，Windows 额外编译 `--features wgc`。
- **P3-01…P3-06、P3-08**：两边 `generate-protocol-types.mjs` 覆盖契约（兼容 `contractVersion`、
  为 ingress 单独生成），API 客户端与错误码映射、观看页面、手势/键盘/剪贴板入口、控制权 UI、
  能力降级都已实现并有单测；Web `typecheck`/`test`/静态导出构建通过。
- **P6-05 监控告警**：Server 暴露 `GET /api/v2/metrics`（`AGENT_SERVER_METRICS_ENABLED` /
  `AGENT_SERVER_METRICS_TOKEN` 控制），提供 `rd_sessions_active`、`rd_sessions_total`、
  `rd_sessions_ended_total`、`rd_session_duration_seconds`、`rd_errors_total` 与
  `rd_fps`/`rd_bitrate_kbps`/`rd_packet_loss_pct`/`rd_input_rtt_ms`。告警规则本身由部署方
  在 Prometheus 里配置，本文档给出建议阈值。
- **真实进程级验证（2026-11-13）**：`screen-host/scripts/e2e-livekit.py` 与
  `server/scripts/e2e_media_plane.py` 用真实 host 二进制 + 真实 `livekit-server` 跑通，并因此修掉三个
  真实缺陷：host `hello.arch` 输出 `x8664`（契约为 `x86_64`）、`displayId="0"` 找不到显示器（现在解析到
  首个显示器）、LiveKit 管理令牌缺少 `roomCreate` 导致 `DeleteRoom`/`RemoveParticipant` 恒 401（房间
  残留）以及 httpx 继承环境代理导致 RoomService 502。
- **P6-06 版本与发布、P6-07 文档**：各端版本统一 2.3.0，`docs/releases/2.3.0.md`、`CHANGELOG.md`
  未发布节、`docs/api/remote-desktop.md` 与 `docs/remote-desktop.md` 已更新。
- **P4-01 依赖与 Manifest、P4-02 API/DTO**：`io.livekit:livekit-android:2.30.0`（官方 Maven Central）；
  manifest 用 `tools:node="remove"` 去掉 `RECORD_AUDIO`/`MODIFY_AUDIO_SETTINGS`/`BLUETOOTH`/
  `FOREGROUND_SERVICE_MEDIA_PROJECTION` 与 LiveKit 的 `ScreenCaptureService`（Android 只观看）；
  `api/RemoteDesktopApi.kt` + `RemoteDesktopDtos.kt` 覆盖全部远程桌面端点，未知枚举值降级保留原文，
  `ApiException` 透传 `detail.details`；token 续签时机与入口可见性是纯函数并有单测。
  `:app:compileDebugKotlin`、`:app:testDebugUnitTest`（239 用例）、`:app:assembleDebug` 全部通过。
- **P4-04 输入编码、P4-05 生命周期规则**：`feature/remotedesktop/input/` 里有 12 字节指针帧（`pointer-frame.md`
  5 组向量逐字节断言）、视图变换/缩放平移、`KeyEvent` → W3C code 映射、`input-event` 编码器与 120/s
  限流；`RemoteDesktopEventStream` 解析六类事件并带 cursor 重连；`RemoteDesktopSessionController`
  覆盖 consent/heartbeat/token 续签/抢占/结束/失败分支。Android 单测从 239 增至 **324 全绿**。
- **P4-03 观看 UI、P4-06 控制权与生命周期接线**：`livekit/LiveKitMediaSession` 用官方 SDK 连接房间、
  订阅 `screen` 轨并发布 `aa.input`/`aa.pointer`；`ui/screens/remotedesktop/` 有观看页、状态浮层、
  顶部/控制/修饰键/键盘条；手势状态机覆盖 §8.1 表格；设备详情页在能力可用时显示入口，
  路由 `RemoteDesktop` 已加入导航。Android 单测 **382 全绿**，`compileDebugAndroidTestKotlin`、
  `assembleDebug`（APK 108.3 MB）与三个仓库 gate 全部通过。

### 部分完成（不能按已完成计）

| 条目 | 已做到 | 缺口 |
| --- | --- | --- |
| RD-P1-16 集成测试 | 单 worker + 真实 PostgreSQL 的远程桌面/设备设置/架构边界 130+ 用例通过；`test_remote_desktop_maintenance_db.py` 用两个独立的 maintenance loop 服务实例跑同一个 PG，证明过期会话只被推进一次、结束原因不被覆盖；`server/scripts/e2e_full_chain.py` 跑通真实 Server + 真实 Connector + 真实 host + 真实 LiveKit 的整链（配对 → 创建 → live → SFU 见 `screen` 轨 → 结束 → 房间清空），并额外验证「必须本机确认」时的拒绝路径、真实控制面 WebSocket 事件、以及两个客户端之间的控制权转移（`take-control` → `control.revoked` → 抢回 → `release-control`） | 仍是单 Server 进程（没有 Redis 多 worker 真实部署），也没有浏览器/手机客户端参与 |
| RD-P2-03 host 定位/校验 | 按 `screen-host/<platform>-<arch>/` 解析并对开发目录回退；`scripts/package.ps1` 写出 `manifest.json`（platform/arch/version/sha256/features），Connector 校验 sha256，篡改或不匹配时以 `rd_host_integrity` 拒绝启动；`hostPath` 只来自本地配置 | 没有 Authenticode / codesign / teamID 校验（只有哈希完整性） |
| RD-P2-04 Supervisor | 三路 PIPE、4 MiB 限制、env allowlist、Windows Job Object、POSIX guardian、ping 3 次重启、退避限流都有实现与单测 | 「kill host / kill Connector / 注销用户」三场景未在真机矩阵里验证；stderr 轮转未做 |
| RD-P2-05 退出清理 | `run_forever` finally 调用 `aclose()` → release-all/stop/terminate；stdin EOF 与 shutdown 帧已覆盖 | Ctrl-C/SIGTERM 信号路径未接（依赖 Connector 进程退出时的 finally） |
| RD-P2-06 RPC 方法 | `hostInfo/start/stop/updateQuality/control.grant/requestKeyframe/releaseAll/screenshot/input` 全部实现，参数校验与错误码映射有测试 | `deadlineMs` 只记录未执行；旧 Connector 兼容分支没有专门测试 |
| RD-P2-09 `host.cancel` | 其余协议能力已完成；`hello.arch` 现在直接输出契约值 `x86_64`（此前是 `x8664`，靠 Connector 归一）；`displayId` 为 `"0"` 或空时解析到首个显示器；shutdown 不再重复发送 `stopped` | `host.cancel` 方法未实现（契约里也没有该方法） |
| RD-P2-10 Windows 采集 | WGC（优先）+ DXGI 回退、显示器枚举、screenshot 为真实实现，`--features wgc` 可编译；另有 `AA_SCREEN_HOST_SYNTHETIC_CAPTURE` 的合成源（同一 `CaptureSource` 契约），用于无桌面环境验证下游链路 | 本机是 Session 0，WGC/DXGI 都返回 Access Denied；1080p/4K 稳定性与显示器热插拔需真机验收 |
| RD-P2-11 发布 | `NativeVideoSource` + VP8 + Screenshare + `simulcast=false` 真实实现，采集线程持续送帧；本地 `livekit-server` 验证入房与 `screen` track；合成采集下整条链路已端到端验证：I420 → VP8 → LiveKit → 浏览器 `<video>` 解出 640×360/`readyState=4` | 真实桌面采集的 P95 延迟、`get_stats` 的 bitrate/rtt/loss 字段（当前 fps/分辨率来自本地计数）未验证 |
| RD-P2-12 Windows 输入 | `SendInput` 鼠标/键盘/Unicode、VK 映射、pressed 集合 + release-all 有实现与单测；`host.input` 有 120 事件/秒滑动窗口限流（超出返回 `rd_rate_limited`）；指针坐标按会话捕获的显示器换算到虚拟桌面绝对坐标（`MOUSEEVENTF_VIRTUALDESK`）；`SendInput` 被系统拒绝时会记一条警告（锁屏/安全桌面/断开的 RDP），`scripts/probe-input.ps1` 可手动验证输入与截图 | 真机 stuck-key/拖拽释放、双屏实机点选精度未验收（合成采集下 `SendInput` 仍返回成功，输入落到不可见桌面） |
| RD-P2-13 本机确认与可见性 | host 常驻 topmost 提示窗（点击结束共享，主循环优雅退出）；Connector 可选原生 MessageBox 确认；Desktop 窗口内提示条 + always-on-top 悬浮窗 + macOS 菜单栏项 | 确认/拒绝/超时/无人值守组合矩阵未在真机跑过；macOS 菜单栏项未在 macOS 上验证 |
| RD-P2-14 打包与签名 | `scripts/package.ps1` 产出 `dist/<platform>-<arch>/aa-screen-host[.exe]` + `manifest.json`；`scripts/build-livekit.ps1` 与 `.cargo/config.toml` 固定 libwebrtc/CRT 构建方式；`desktop-workbench/scripts/prepare-screen-host.mjs` 把 `screen-host/dist` 复制成 `resources/screen-host/`（缺失时写占位 README，打包不失败），已接入 `pack` 与 `dist` | 未签名/公证，DSH 插件 bundle 与 CLI 下载器未接，也没有干净 VM 安装验收 |
| 截图格式与入口 | `host.screenshot` 支持 `jpeg`（灰度，默认质量 80，可用 `quality` 覆盖）与 `png`；未知值回退 PNG；`screenshot-response` schema 把 `format` 定义为可选枚举（`jpeg`/`png`），Server 透传，客户端用生成的类型直接读取。Web/Desktop 工具栏有「截图」按钮（下载），Android 控制条也有「截图」（写入 `cache/remote-screenshots/` 后走 FileProvider 分享）；三端共用 `contracts/remote-desktop/1.0/screenshot-naming.json` 的命名用例，改一处会让三端测试同时失败；Android 另有 `androidTest` 覆盖按钮可见性与点击回调；`--browser` 端到端会点按钮并校验下载到的 JPEG 字节 | 真机截图清晰度与 4K 降采样效果未验收；Android 分享面板与相册保存未在真机验证 |
| RD-P2-16 host 端到端 | 真实二进制连真实 LiveKit 通过（`scripts/e2e-livekit.py`）；`server/scripts/e2e_full_chain.py` 进一步由真实 Connector 拉起 host、由 Server 下发 grant，SFU 确认 `screen` 轨，结束后房间清空 | 采集是 Session 0 的 stub，不是真实桌面；kill host 后 Connector 上报与 stuck-key 的真机验收未做 |；`scripts/acceptance.py` 提供真机验收的自动部分（显示器枚举、每屏截图、指针网格/滚轮/修饰键/中文输入、releaseAll、退出码）
| RD-P3-07 Desktop 同步与原生层 | Web 实现已复制到 renderer；Connector local state → Electron `DesktopConnectorState` → 悬浮窗/菜单栏项/窗口内提示条；提示条可直接结束共享 | macOS status item 未在 macOS 验证；preload 只暴露「打开工作台」，没有「从悬浮窗直接结束共享」的 IPC |
| RD-P3-09 端到端 | 各端 headless 检查通过 | Web/Desktop + 真 Server + 真 host + 真 LiveKit 的 Playwright/手动清单未执行 |
| RD-P4 Android | P4-01…P4-06 已完成：编译、382 单测、`compileDebugAndroidTestKotlin`、`assembleDebug`（APK 108.3 MiB）、`:app:lintDebug`（0 error）与 `checkLegacyRoutes`/`checkRealtimeLifecycle`/`checkAndroidV2ReleaseGate` 全通过 | RD-P4-07 真机验收未做（观看 30 分钟、延迟/码率/耗电、手势与中文输入），`androidTest` 只编译未运行；剪贴板、PiP、截图未实现 |
| RD-P5 iOS | 无 | Windows 环境没有 Xcode，无法编译验证，未开始 |
| RD-P6-01 Docker LiveKit | `docker/livekit.yaml` + compose `remote-desktop` profile（webhook、prometheus、TURN 证书挂载、Redis 服务名）；`server/scripts/e2e_media_plane.py` 用真实 `livekit-server` 验证了 Server 签发的 host grant、`screen` 轨与 `delete_room` | 未真实 `docker compose up` 验证（本机无 Docker daemon）；TURN 与 webhook 回调未验证 |
| RD-P6-02 端口与 TLS | 文档写明端口/`use_external_ip`/TURN 443 方案 | 真实 4G/企业网 ICE 未验证 |
| RD-P6-03 Server 环境变量 | 完整命名、缺配置时能力 `supported=false` 且不崩溃 | `AGENT_SERVER_SECRET` 只在保存密钥/Tokens 链接时强制 32+（`secret_box`）与签发 OAuth 客户端时检查，远程桌面本身不依赖它，因此没有额外的启动校验 |
| RD-P6-04 反代 | `docker/README.md` 写明 WebSocket upgrade、webhook 不重写 body、请求体上限；新增 `docker/nginx-remote-desktop.conf` 与 `docker/Caddyfile` 两份可直接使用的样例 | 两份样例未经真实反代集成测试（本机没有 nginx/caddy） |

### 未开始

- **RD-P4-07 Android 真机验收**：需要一台 Android 14 真机 + Windows host + 真 LiveKit，本机没有设备。
- **P5 iOS（RD-P5-01…07）**：当前是 Windows 环境，没有 Xcode，既不能编译也不能真机验收。

## 0. 审查结论

方向成立：WebRTC + 自托管 LiveKit 作为媒体面、控制面复用 Server + Connector RPC、主机侧用原生
`aa-screen-host`，这三项选型与现有架构匹配，**不需要推翻**。

但现稿不能直接开工。审查发现 6 个阻塞项和一批必须在实现前修正的设计/契约问题；其中多数不是
「实现时注意」，而是会导致两个端实现出不同协议、会话卡死或安全承诺落空。建议先把本文 §1 的
决策落成文字、把 §2 的契约冻结，再进入编码。

### 0.1 阻塞项

| ID | 级别 | 问题 | 证据 | 计划处理 |
| --- | --- | --- | --- | --- |
| B1 | P0 | **媒体拓扑自相矛盾**：文档同时规定「单 Connector 1 路采集」「每设备 1 控制 + 3 观看」「每会话独立 room 且一个 room 只有一个 host」。四个会话无法共享一路编码又各自占一个 room。 | `docs/remote-desktop.md:950`、`:888`、`:658-659`、`:347`、`:631` | 采用 D1：v1 每 Connector 同时只有 1 个媒体会话、1 个 room；多客户端作为同一会话的多个 viewer/control 参与者。 |
| B2 | P0 | **生命周期无人驱动**：consent 60s、lease 60s、interrupted 90s、idle 15min、8h 上限都只有规则，没有 sweeper；Redis TTL 只删键不触发动作，且 `rd:session` TTL 2h 小于 8h 会话上限。 | `docs/remote-desktop.md:404-419`、`:559`、`:807-809`、`:951`；`server/agent_server/app.py:88-166` | 新增 `RemoteDesktopMaintenanceLoop`（Redis 锁或 PG deadline 幂等推进），PG 为会话事实源，Redis 只做缓存；补假时钟测试。 |
| B3 | P0 | **consent 前就签发可发布 token**：创建响应在 `starting/awaiting_consent` 时返回 `canPublishData=true`；`take-control` 又可直接拿 token，绕过设备确认。 | `docs/remote-desktop.md:480-503`、`:547-562`、`:903`；`session-create-response.schema.json:31` | 采用 D3：consent 前不下发媒体 token（或只发不可发布 token）；确认后通过 `UpdateParticipant`/短 TTL controller token 提升权限；`take-control` 未确认时返回 `202 awaiting_consent`。 |
| B4 | P0 | **clientId / controller 身份不可信**：`clientId` 由请求方自报，ws ticket 只存不验；`controllerClientId`（`cl_9`）与 LiveKit identity（`viewer:cl_9`）命名空间不一致，host 无法可靠校验「双重闸门」。 | `docs/remote-desktop.md:490`、`:631`、`:659-661`、`:895-896`；`server/agent_server/infra/ws_tickets.py:17-21` | Server 生成不可猜测的 participant instance id，token identity 与 `controllerClientId` 都使用它；host 用 LiveKit `DataReceived.participant.identity` 校验，拒绝 None/未知。 |
| B5 | P0 | **Connector 通知会被静默吞掉**：`remoteDesktop.state/stats/consent/error` 未注册 handler 时 `ConnectorNotificationService.apply` 返回空 `IngestEffect`，HTTP ingest 仍返回 accepted；状态机永远不会推进。 | `server/agent_server/services/connector_notifications.py:91-143`、`services/connector_ingest.py:161-176` | 新增显式 `RemoteDesktopNotificationHandler` + broker 发布；未知 `remoteDesktop.*` 返回 `unsupported_notification`；两条路径（WS/HTTP ingest）都覆盖测试。 |
| B6 | P0 | **契约与文档不是同一协议**：`realtime-event` 四类 payload 与文档示例冲突（stats 嵌套 vs 扁平、是否带 sessionId）；`input-event` 缺 `reject`、host→client clipboard 被 `additionalProperties` 拒绝；token/take-control/ticket/screenshot/error/list 等接口无 schema。 | `contracts/remote-desktop/1.0/schemas/realtime-event.schema.json:57-141`、`input-event.schema.json:6-108`、`docs/remote-desktop.md:609-612`、`:713`、`:722-724` | 先完成 §2 的契约修订与冻结，再写 Server/客户端代码；文档示例必须能被 schema 校验。 |

### 0.2 高优先级问题（不阻塞契约冻结，但阻塞对应阶段）

| ID | 问题 | 处理阶段 |
| --- | --- | --- |
| H1 | 设备设置模型两端都只支持 bool：`remoteDesktopMaxFps/MaxBitrateKbps` 是 int，会被 Server/Connector 丢弃；PATCH 未知字段 422。 | P1 |
| H2 | 安全默认值：`remoteDesktopEnabled=true`、`AllowControl=true`、观看不确认、截图预览无会话/无悬浮条，存在静默看屏风险。 | P1（默认值）、P3（本机可见性） |
| H3 | 自托管 LiveKit 不支持 JWT 吊销；「抢占后 token 立即失效」不成立，只能靠短 TTL + `RemoveParticipant` + host epoch。 | P1 |
| H4 | host 子进程监管没有现成实现：`connector/launch.py` 只拼 argv，`core/runtime_logs.py` 是 Runtime 事件日志；需新建 supervisor，并绑定 Connector 退出/崩溃清理。 | P2 |
| H5 | host 崩溃不释放按下的键/鼠标键，会留下 Ctrl 卡住或拖拽卡死；协议缺少 release-all。 | P2 |
| H6 | 剪贴板 64 KiB 原文 base64 约 87 KiB，超过 LiveKit reliable 单包 15 KiB 上限；没有分片协议。 | P2/P3 |
| H7 | `codecs`/`preferCodec`/stats 示例宣称 h264，但 v1 实际只有软件 VP8；契约还允许 vp9/av1。 | P0/P2 |
| H8 | Desktop 不是复用 Web 渲染层：`desktop-workbench/renderer` 是独立 Next.js 副本，需两处实现/同步并各加 `livekit-client`。 | P3 |
| H9 | 迁移使用 `JSONB/TIMESTAMPTZ`，仓库全库用 TEXT + JSON 字符串；且新增迁移要同步 `CURRENT_SCHEMA_REVISION`、`classify_database` 和迁移测试断言。 | P1 |
| H10 | LiveKit 部署配置缺 `webhook`、TURN `cert_file/key_file`、`prometheus_port`；Redis 服务名与 compose 不一致；无 `screen-host` CI/打包。 | P6 |
| H11 | 现有 push WebSocket 会丢弃所有入站帧，不能承载 `ping/request_keyframe/update_quality`；需自写双工循环和 client→connection 订阅表。 | P1 |
| H12 | 删除设备/用户时没有 RD 清理钩子；`ON DELETE CASCADE` 还会把审计会话记录一起删掉。 | P1 |
| H13 | Web 是 `output: export` + `#/device/{id}` 哈希路由，文档的 `app/(app)/devices/[connectorId]/remote/page.tsx` 动态段无法静态导出。 | P3 |
| H14 | Desktop 没有本机被控状态 IPC/悬浮窗/macOS 菜单栏项；「复用 Web 渲染层」实际是手工复制 renderer。 | P3 |
| H15 | iOS 工程最低 26.5（不是验收写的 17），SwiftPM 产品名是 `LiveKit`（不是 `LiveKitClient`），且当前 Xcode 工程只引本地 Textual 包。 | P5 |
| H16 | Android 分层规定 `feature/` 不放 Compose；`livekit-android` 可能合并 `RECORD_AUDIO` 权限，APK 体积也会明显增加。 | P4 |

### 0.3 推荐决策（实现前确认，未另开讨论则按此执行）

| ID | 决策 | 推荐值 | 理由 |
| --- | --- | --- | --- |
| D1 | v1 媒体拓扑 | **一个 Connector 同时 1 个媒体会话、1 个 room**；多个客户端作为同一会话的 viewers 加入；控制权是会话内租约 | 与「单路采集/单路上行」一致；避免多 room 重复发布。`maxSessionsPerConnector` 改为 `maxViewersPerSession`，`activeSessions` 只返回 0/1。 |
| D2 | 平台范围 | **Windows-first**：P2 先交付 Windows 采集/输入；macOS 作为 P2 后半或 P3；Linux/Wayland 放 P6 | 三平台同时做会把 host 工期翻倍；Windows 是主要工作设备。 |
| D3 | consent 与 token | 创建时 `mode=control` 且需要确认 → `state=awaiting_consent`、`media=null`；确认后通过 `POST /sessions/{id}/token` 签发短 TTL controller token；`take-control` 未确认时返回 `202` | 不在确认前给出可发布凭据；避免「先拿 token 后弹窗」的隐私窗口。 |
| D4 | 身份模型 | Server 生成 `participantId`（随机、绑定 session+user+client 记录）；LiveKit identity = `viewer:{participantId}`；controller 字段存完整 identity | 不允许客户端自报 `clientId` 作为安全身份；host 可直接用 SDK 校验。 |
| D5 | 设备安全默认值 | `remoteDesktopEnabled=false`、`remoteDesktopAllowControl=false`、`remoteDesktopRequireConsent=true`、`remoteDesktopAllowUnattended=false`、`remoteDesktopClipboard=false`；开启/关闭安全项需本机确认 | 默认关闭避免升级后静默看屏；无人值守必须本机显式开启。 |
| D6 | v1 功能边界 | 不做：截图预览（未建会话的 GET）、无人值守、多显示器切换、E2EE、录制、音频、多 room | 这些功能各自带独立安全/协议成本；先把「看 + 控 + 一台显示器」做稳。 |
| D7 | 契约事实源 | `contracts/remote-desktop/1.0/schemas` 为唯一 wire 事实源；文档只写语义与示例；示例必须进 `validate.py` | 现稿文档与 schema 已分叉，必须单向收敛。 |
| D8 | Desktop 渲染层 | v1 复制 Web 实现到 `desktop-workbench/renderer`，在两边加同一组测试；不先抽共享包 | 抽共享包会扩大本次改动；用同步清单和 `protocol:check` 控制漂移。 |

## 1. 契约冻结（P0，1–2 周）

目标：让 `docs/remote-desktop.md` 第 7 节与 `contracts/remote-desktop/1.0` 成为同一份可执行协议，
并让 CI 能证明这一点。

- [x] **RD-P0-01 统一事实源与版本**：在契约 README 写明「schema 为 wire 事实源，文档为语义说明」；删除文档中与 schema 冲突的示例；把 `contracts/remote-desktop/1.0` 标记为冻结候选，冻结前不再加功能。验收：README 无「以本文第 7 节为准」的相反表述。
- [x] **RD-P0-02 统一实时事件 envelope**：确定六类事件统一为 `{type, payload}`；payload 统一携带 `sessionId`（即使 WS 已按 session 路由）；`stats.updated` 使用嵌套 `stats` 对象；`subscribed.cursor` 必填；`ended` 必填 `endedAt`。验收：六类 valid fixture 各一份，文档示例逐条通过 schema。
- [x] **RD-P0-03 拆分输入/控制事件方向**：`input-event.schema.json` 只保留 client→host；新增 `host-control-event.schema.json` 承载 `ack`、`reject`、`clipboard.offer/denied`；补 `reject.code`、`clipboard.denied.reason`；`mime` 仅对 `offer` 必填；`aa.pointer` 12 字节帧补十六进制测试向量（含 seq 回绕/越界）。验收：manifest direction、README、schema 三者一致；每个 `oneOf` 分支有 valid/invalid fixture。
- [x] **RD-P0-04 补齐 HTTP 响应/请求 schema**：`token-refresh-{request,response}`、`take-control-{request,response}`、`release-control`、`heartbeat`、`end`、`ticket`、`session-list`、`screenshot-{request,response}`、统一 `error` envelope（`detail: {code, message, details}`，兼容现有 Web/Android/iOS 错误解析；`rd_*` 只作 Connector 内部码，HTTP 对外用 `remote_desktop_*`）。验收：§7.2 每个端点都有 request/response schema 与至少一个 fixture。
- [x] **RD-P0-05 统一命名**：`controllerLeaseExpiresAt`（session 与 take-control 共用）、`tokenExpiresAt`（创建与续签共用）、`dataBase64`（HTTP/RPC/剪贴板统一）；删除 `jpegBase64`、`leaseExpiresAt`、`expiresAt` 等别名。验收：全仓 grep 无旧名（除迁移兼容说明）。
- [x] **RD-P0-06 修正 host-info 降级语义**：`platform` 增加 `unknown`（或 nullable）；`unavailableReason` 增加 `host_not_installed`、`connector_outdated`、`unsupported_platform` 与 `available/allowed` 的归属说明；`permissions` 的三种状态设为 required；明确 `supported=false` 时 Server 合成对象规则。验收：旧 Connector / host 未安装 / 权限缺失三份 fixture。
- [x] **RD-P0-07 能力与设置 schema**：新增 `RemoteDesktopCapability` schema（`connector.remote_desktop.view/control/clipboard`），字段对齐现有 `ProtocolCapability` 的 `supported/available/allowed/unavailableReason`，并增加 `revision`（用于客户端缓存/去重）；7 个设备设置字段写成机器可读定义（类型、默认值、范围）；与 host-info 的 `metadata` 交叉引用。验收：能力对象与设置对象各一份 fixture；Server Pydantic 模型由 schema 生成或逐字段对齐；明确 `revision` 的推送方式（见 P1-05）。
- [x] **RD-P0-08 host-control 协议契约**：在 `contracts/remote-desktop/1.0/` 下新增 host↔Connector 的 stdio 消息 schema（`hello`、`request/response`、`event`、`error`、`shutdown`），固定 NDJSON、UTF-8、stdout 仅协议、stderr 仅日志、最大帧 4 MiB、请求 id/deadline/cancel。验收：schema + fixture 通过；Rust `serde` 模型与 Python 模型共用同一组 fixtures。
- [x] **RD-P0-09 兼容策略落地**：ingress 请求 `additionalProperties:false`；egress 响应/事件改为允许未知字段（或明确每次新增字段必须发新 schema 版本）；README 删除与实现矛盾的「客户端忽略未知字段」承诺，改成可执行的版本规则。验收：`session`/`host-info` 加一个未知字段的 valid fixture（egress）和 invalid fixture（ingress）。
- [x] **RD-P0-10 校验器对齐 runtime-control**：把 `contracts/runtime-control/2.1/validate.py` 的 `check_schema`、manifest 路径/sha256、按 fixture 目录 slug 匹配、`FormatChecker` 移植到 remote-desktop 的 `validate.py`；增加「文档示例必须通过」的 fixture。验收：`uv run --with jsonschema --with referencing python contracts/remote-desktop/1.0/validate.py` 全绿；故意改坏 manifest 哈希时失败。
- [x] **RD-P0-11 契约 CI**：在 `.github/workflows/ci.yml` 增加 `contracts/*` 触发的 contract job，运行 remote-desktop validator 与 manifest 校验；`web-next`/`desktop-workbench` 的 `protocol:check` 接入 remote-desktop 生成（见 P3）。验收：PR 改 schema 未改 fixture/hash 时 CI 红。
- [x] **RD-P0-12 文档收尾**：把 §6.3 的 `state=publishing` 明确映射为 API `starting|live`；把 §7.2.1 的 hostInfo 字段拆成「Connector 返回的 HostFacts」与「Server 组合的 capability」；删除 §7.2.7 的未建会话 GET 预览（D6）；更新 `docs/api/README.md`、`docs/api/namespace.md`、`docs/api/device-settings.md`。验收：文档与契约交叉引用完整，无未定义状态/字段。

**P0 出口条件**：上述 12 项全部完成；`validate.py` 通过；六类实时事件、每个 HTTP 端点、host-control 协议均有 fixture；审查发现的 B6/H7 类契约矛盾清零。

## 2. Server 控制面（P1，2–3 周）

目标：用 `provider=noop` 和假 host 跑通创建 → 等待确认 → 观看 → 结束的完整状态机；不依赖
LiveKit 与真实屏幕。

- [x] **RD-P1-01 迁移 `v2_56`**：按仓库风格使用 TEXT（RFC3339 UTC）和 JSON 字符串列，不使用 `JSONB/TIMESTAMPTZ`；表 `remote_desktop_sessions` 增加 `controller_lease_expires_at`、`last_input_at`、`worker_id`、`end_reason`；索引使用 `idx_rd_sessions_*`；`connector_id` 外键先评估 `ON DELETE RESTRICT` 或独立审计表，避免删除设备时丢失审计。同步 `CURRENT_SCHEMA_REVISION/VERSION`、`classify_database` 版本链和 `tests/test_database_migrations.py` 的 v2_55 断言。
- [x] **RD-P1-02 Core 模型与状态机**：`RemoteDesktopSession`、`DisplayInfo`、`InputEvent` 校验、错误码；状态迁移白名单（`starting→awaiting_consent→live→degraded→interrupted→ended`）；终态不可逆；`endReason` 枚举与契约一致。验收：非法迁移全部拒绝，单测覆盖每个边。
- [x] **RD-P1-03 Repository**：`infra/repositories/remote_desktop.py` 提供 create/get/list/update/end/stats_summary；所有读接口按 `user_id` + `connector.userId` 过滤；不存 token、输入、剪贴板内容。验收：CRUD、跨用户 404、分页/list 测试。
- [x] **RD-P1-04 设备设置 7 项**：扩展 `core/device_settings.py` 支持 bool + 有范围 int（`remoteDesktopMaxFps` 5–60、`remoteDesktopMaxBitrateKbps` 500–20000），`device_settings_from_stored` 不再只保留 bool；`ConnectorDeviceSettingsPatch` 对未知字段仍 422；Server/Connector 两侧同时改并更新 `docs/api/device-settings.md`。验收：PATCH 往返、离线改设置后上线生效、旧 Connector 不误报 `delivered=true`。
- [x] **RD-P1-05 媒体 provider 端口与能力投影**：`services/remote_desktop_media.py` 定义 `LiveKitMediaProvider`/`NoopMediaProvider`；服务层不 import LiveKit SDK；`app.state` 组装。独立 `RemoteDesktopCapability` 带 `revision`；设备详情页通过 REST 读取，host 启停/权限变化时由 `remote_desktop.capability.updated`（或设备页轮询）刷新，不能复用 `ProtocolCapabilitySet`。验收：未配置 LiveKit 时 `supported=false`、`media=null`，现有功能不受影响；能力 revision 单调递增。
- [x] **RD-P1-06 LiveKit token 与房间**：HS256 JWT（`roomJoin`/`canPublish`/`canSubscribe`/`canPublishData` 按角色），room `rd_{sessionId}`，TTL 600s（controller 建议 120s，见 D3）；token 不落库、不写日志；Noop 测试。验收：解析 token 断言 grants、identity、room、TTL；跨 room 拒绝。
- [x] **RD-P1-07 HTTP 路由**：`GET /connectors/{id}/remote-desktop`、`POST/GET /remote-desktop/sessions`、`GET /remote-desktop/sessions/{id}`、`POST .../token`、`take-control`、`release-control`、`heartbeat`、`end`；统一 `HTTPException(detail={"code","message"})`；`require_owned_connector`；`link.sessionId` 必须 `get_session(user_id=...)`。验收：§7.10 每个错误码各一个用例；跨账号 404。
- [x] **RD-P1-08 WS ticket 与双工会话 WS**：ticket scope `remote-desktop:{sessionId}`，一次性、60s；自写双工循环（不能复用 `run_server_push_until_disconnect`，它丢弃入站帧）；订阅表 `participantId→connection`；首帧 `subscribed` + snapshot + cursor；入站 `ping/request_keyframe/update_quality`。验收：ticket 重放/跨 session 拒绝；WS 断线重连按 cursor 补发或 snapshot。
- [x] **RD-P1-09 通知 handler**：`remoteDesktop.state/stats/displays/consent/error` 显式注册到 `ConnectorNotificationService` 和 `connector_realtime` 两条路径；RD 事件走 `RemoteDesktopBroker`，不要放进 Agent session 的 `IngestEffect`；未知 `remoteDesktop.*` 返回 `unsupported_notification`。验收：两条 ingest 路径都推进状态；不会出现「accepted 但状态不动」。
- [x] **RD-P1-10 Broker 与 Redis**：`rd:session:{id}`（TTL ≥ 8h + grace）、`rd:connector:{id}:live`、每 participant 一个 `rd:session:{id}:sub:{participantId}`（TTL 90s）；Pub/Sub channel `rd-events`；原子计数/去重；订阅者不因整把 hash 过期而误判离线。验收：两个 app 实例互达；fake redis 2h/8h/90s 边界。
- [x] **RD-P1-11 生命周期 sweeper**：consent 超时、lease 过期、interrupted 90s、idle 15min、8h 上限；每会话 `SETNX` 或 PG 条件更新保证多 worker 只推进一次；启动时扫描 `ended_at IS NULL` 做恢复。验收：假时钟 + 双 worker 只结束一次；重启后无幽灵 `live`。
- [x] **RD-P1-12 断连与删除清理**：Connector WS 关闭、presence watchdog、`delete_connector_data`、`delete_user_account` 都调用 RD `stop`（best effort + 重试）；删除前停止 host/删 room/撤销 participant；审计记录不随 cascade 丢失。验收：删设备后 host 停止、room 删除、会话 `ended`；删除用户同样。
- [x] **RD-P1-13 LiveKit webhook**：`POST /api/v2/internal/livekit/webhook`；用 API secret 校验 JWT + raw body `sha256` + `iss`/`exp`；`event.id` 去重（Redis，24h）；`room.name` 必须映射到 `media_room` 且会话未结束；未知 room 返回 200；限制 body/速率。验收：伪造签名、重放、重复 `room_finished` 各一个测试。
- [x] **RD-P1-14 错误、限流、审计**：`rd_*` 独立异常类映射，不被通用 handler 压成 502；截图 2s/设备、take-control 冷却、token 端点限流；审计写 `user/connector/display/mode/consent/controller/duration/endReason/error`，不写输入/剪贴板/画面。验收：429/409/403 测试；日志脱敏测试。
- [x] **RD-P1-15 统计与用量**：host stats 2s 进 Redis 滑动聚合，结束时写 `stats_summary`；usage 事件增加 `duration`，流量可选；崩溃时用最后快照兜底。验收：fake host 上报 10 次后平均值正确；报表可查。
- [ ] **RD-P1-16 P1 集成测试**：两个 Server worker + Redis + Noop media + fake connector/host；创建、consent、token、WS、end、断线、删除全链路。验收：`cd server && uv run ruff check . --exclude .venv && uv run pytest -q` 通过（需 `AGENT_SERVER_TEST_DB_URL`）。

**P1 出口条件**：无 LiveKit/真实 host 时能完整跑通控制面；状态机、租约、consent、断线、删除、重启测试通过；设备设置 7 项往返成功。

## 3. Connector 与 Screen Host（P2，Windows-first，4–6 周）

目标：Windows 上真实采集、编码、发布、注入输入；Connector 能可靠拉起/监管/清理 host。

### 3.1 Connector

- [ ] **RD-P2-01 Handler 进程级化**：`RemoteDesktopRpcHandler` 注入 `ConnectorRequestDispatcher`，所有 `ConnectorRequestSession` 共享同一实例；不要放进每连接的 `new_session()`；WS 重连不重建 supervisor。验收：重连后 host 仍在；不会重复启动。
- [ ] **RD-P2-02 配置扁平化**：`remoteDesktop.enabled/hostPath/maxViewers/preferredEncoder` 改成 `ConnectorConfig` 的扁平字段（`from_mapping`/`save`/env 白名单），不写嵌套对象；开发模式才允许自定义 `hostPath`。验收：配置往返、默认值、旧配置文件兼容。
- [ ] **RD-P2-03 host 定位/校验**：只从打包资源目录解析 `screen-host/<platform>-<arch>/aa-screen-host[.exe]`；内置 manifest（platform/arch/version/sha256）；Windows Authenticode、macOS codesign/teamID 校验；`hostPath` 不接受 Server 下发。验收：篡改二进制/错误哈希被拒绝。
- [ ] **RD-P2-04 Supervisor**：`create_subprocess_exec` + 三路 PIPE、UTF-8、`limit=4MiB`、env allowlist（不继承 `AGENT_*`/`AA_*`）；Windows Job Object `KILL_ON_JOB_CLOSE`；POSIX 进程组 + guardian；stderr 轮转；`host.ping` 5s、连续 3 次无 pong 重启；退避最多 3 次/分钟。验收：kill host / kill Connector / 注销用户三种场景无孤儿。
- [ ] **RD-P2-05 退出清理**：`BackendRpcClient.run_forever` finally、`ConnectorController._stop_runtime`、进程信号处理都调用 `host.shutdown()`；3s 后 terminate/kill；先 release-all 再退出。验收：Connector 正常退出/被 Ctrl-C/SIGTERM 后无 host 残留。
- [ ] **RD-P2-06 RPC 方法与参数**：实现 `hostInfo/start/stop/updateQuality/control.grant/screenshot/input`；参数校验用 `require_only_fields`/边界；`sessionId` 作为 `start` 幂等键；`start` 带 `deadlineMs`；Server 30s 超时后 Connector 主动 stop；未知方法返回稳定 `method_not_found`（或 Server 兼容 `ValueError`）。验收：重复 start/stop、超时、取消、旧 Connector 兼容各一测。
- [ ] **RD-P2-07 通知上报**：`state/stats/displays/consent/error` 走 `send_backend_notification` + HTTP ingest 兜底；stats 只保留最新（合并），state/error 不丢；不把 token/媒体 URL/输入内容写日志；日志 redaction 增加 `data/dataBase64/text/s/events/clipboard`。验收：断 WS 后 HTTP 兜底仍推进状态。

### 3.2 Screen Host

- [ ] **RD-P2-08 工程骨架**：新顶层 `screen-host/`（Cargo workspace），`main.rs` NDJSON 循环、`protocol.rs`、`capture/`、`publish/`、`input/`、`packaging/`；锁 LiveKit Rust SDK 精确版本并提交 `Cargo.lock`（仓库不提交其他 lockfile，Rust 例外需在 README 说明）。验收：`cargo fmt/clippy/test` 通过。
- [ ] **RD-P2-09 握手与协议**：`host.hello`（protocolVersion/hostVersion/platform/arch/pid/capabilities），握手失败退出码 78；stdout 只写协议帧，stderr 只写日志；`host.start` 幂等、`host.cancel`、`deadlineMs`、有界队列、stats 合并；非 JSON 行 → `rd_host_protocol_error` + kill/restart。验收：协议 fuzz/坏帧/超时/重复 start。
- [ ] **RD-P2-10 Windows 采集**：WGC（优先）或 DXGI Desktop Duplication；枚举显示器（id/name/width/height/scale/primary/refreshHz/x/y）、DPI/分辨率热插拔、权限探测；单帧 screenshot（内部用，默认 1280 宽）。验收：1080p/4K 各 10 分钟稳定；显示器插拔不崩溃。
- [ ] **RD-P2-11 发布**：`NativeVideoSource` 发布 I420，VP8 软件编码，`TrackSource::Screenshare`，`simulcast=false`，`contentHint=text`；`get_stats` 上报 fps/bitrate/rtt/loss/codec/width/height/encoder；重连事件映射。验收：本地 LiveKit 端到端 P95 < 250ms；stats 2s 一条。
- [ ] **RD-P2-12 Windows 输入**：`SendInput` 注入鼠标/键盘/Unicode 文本；W3C code → VK 映射；多显示器归一化坐标；120/s 限频；维护 pressed 集合，`host.stop`/会话结束/崩溃重启/Connector 退出执行 release-all；启动先 panic release。验收：stuck-key 注入测试、拖拽释放测试。
- [ ] **RD-P2-13 本机确认与可见性**：host 原生确认框（`requireConsent=true`）+ 常驻悬浮条「正在共享屏幕 · 结束共享」；`allowUnattended=false` 时无确认一律拒绝；headless/无交互会话 fail-closed；Desktop 只做托盘提示。验收：确认/拒绝/超时/无人值守组合矩阵。
- [ ] **RD-P2-14 打包与签名**：Windows `.exe` + sha256 manifest；macOS universal `.app` + codesign/notarize（P2 后半）；加入 `desktop-workbench` extraResources、DSH 插件 bundle、CLI 下载器；版本/回滚策略。验收：干净 Windows VM 安装后可用；macOS Gatekeeper 通过（若本阶段交付）。
- [ ] **RD-P2-15 host CI**：`.github/workflows/ci.yml` 增加 `screen-host/*` 路径触发与 Windows/macOS cargo fmt/clippy/test job（Linux 先编译检查）；发布产物上传 artifact。验收：PR 改 Rust 代码必须跑 CI。
- [ ] **RD-P2-16 host 端到端**：Windows + 真 LiveKit + Noop viewer 的手动/自动验证；kill host 后 Connector 上报 error/state；input release；screenshot 上限。验收：无孤儿进程/无残留 participant。

**P2 出口条件**：Windows 上能采集并发布一路 VP8 屏幕流；Connector 重启/崩溃清理可靠；输入注入与 release-all 通过；本机确认/悬浮条可见；打包可安装。

## 4. Web 与 Desktop（P3，3–4 周）

目标：Web 先完成观看，再加控制；Desktop 复制同一套实现并补本机提示。

- [ ] **RD-P3-01 依赖与类型**：`web-next` 与 `desktop-workbench/renderer` 各加 `livekit-client`（固定版本，客户端组件内动态 import，避免静态预渲染访问 `window`）；把 `contracts/remote-desktop/1.0` 加入两边 `generate-protocol-types.mjs`，脚本要兼容 manifest 的 `contractVersion`（不是 `protocolVersion`），并为 ingress 请求单独生成类型（现有脚本会过滤 `direction==="ingress"`）；`protocol:check` 覆盖。验收：两边 typecheck + protocol:check 通过；Web 静态导出构建成功。
- [ ] **RD-P3-02 API 客户端**：`lib/remote-desktop/api.ts`：capability、create、get/list、token、take/release/heartbeat、end、ticket；统一错误码映射。验收：单测覆盖 409/429/503/401。
- [ ] **RD-P3-03 观看页面**：不要用文档的 `app/(app)/devices/[connectorId]/remote/page.tsx`（`output: export` 无法生成动态段）。在现有哈希路由下增加 `#/device/{id}/remote`（`workspace-context` 路由表 + `DevicePage` 内嵌区域），或新增 `app/devices/page.tsx` 读 query；设备页加入口，会话页加「看这台设备屏幕」快捷入口。LiveKit 订阅 `screen` track；`AspectFit` + 全屏；连接/重连/弱网浮层；stats 面板（fps/bitrate/rtt/loss/codec）。验收：`NEXT_OUTPUT=export yarn build` 成功且深链可打开；假 publisher 的 Playwright 用例；真机 Web 观看 P95 < 250ms。
- [ ] **RD-P3-04 控制手势**：单指点击/拖动、长按右键、双指滚轮/缩放、双击拖拽锁定、三指键盘、悬浮修饰键；坐标经视图变换逆矩阵归一化；120/s 合并。验收：缩放/偏移/旋转组合单测；点击落点误差 < 2px。
- [ ] **RD-P3-05 键盘与剪贴板**：软键盘 `text` 事件；硬件键盘 `KeyboardEvent.code`；粘滞修饰键；剪贴板默认关闭，开启后分片（≤8 KiB/片，总 ≤64 KiB）。验收：中文 IME、Backspace/Enter/方向键、复制粘贴往返。
- [ ] **RD-P3-06 控制权 UI**：申请控制 → `awaiting_consent` 等待页；被抢占 `control.revoked` 提示；租约倒计时；`request_keyframe`/`update_quality`。验收：两客户端抢占、租约过期、设备拒绝三场景。
- [ ] **RD-P3-07 Desktop 同步与原生层**：把 Web 的 `remote-desktop` 页面/组件/测试复制到 `desktop-workbench/renderer`（它是手工同步的副本，不共享 web-next）；新增 preload/main IPC 暴露本机「被控状态/结束共享」；新增 always-on-top、frameless overlay `BrowserWindow` 和 macOS status item（现有托盘只覆盖 Windows）；不修改 `../web-next`。验收：Desktop renderer typecheck/test；Windows 托盘 + macOS 菜单栏 + 悬浮条都能显示/结束共享。
- [ ] **RD-P3-08 无障碍/降级**：能力 `supported=false` 隐藏入口；`available=false` 显示原因与重试；`allowed=false` 显示策略提示；无 LiveKit 时不影响现有设备页。验收：四种状态截图/快照测试。
- [ ] **RD-P3-09 端到端**：Web/Desktop + 真 Server + 真 host + 真 LiveKit；观看、控制、断线 90s 恢复、结束、权限拒绝。验收：Playwright + 手动清单。

**P3 出口条件**：Web 可看可点；Desktop 可看可点；手势/坐标/键盘测试通过；两处渲染层无协议漂移。

## 5. Android（P4，3 周）

- [ ] **RD-P4-01 依赖与 Manifest**：`io.livekit:livekit-android`（锁定版本；JitPack 已配置，minSdk 26 兼容）；检查 manifest 合并结果，不需要的 `RECORD_AUDIO` 用 `tools:node="remove"` 移除；记录 APK/AAB 体积基线。验收：`./gradlew testDebugUnitTest assembleDebug` 通过；manifest 无多余权限。
- [ ] **RD-P4-02 API/DTO**：`RemoteDesktopApi.kt` + DTO；Bearer + WS ticket；错误码映射；token 续签。验收：MockWebServer 测试。
- [ ] **RD-P4-03 观看 UI**：`RemoteDesktopScreen` + `SurfaceViewRenderer`；AspectFit/缩放/平移；重连浮层；stats。验收：真机 Android 14 观看 30 分钟。
- [ ] **RD-P4-04 手势/输入**：§8.1 手势表；纯坐标变换/编码放 `feature/remotedesktop`（不放 Compose），`pointerInput`/手势状态机放 `ui/screens/remote`；`RemoteInputEncoder`；`KeyEvent.keyCode → W3C code`；软键盘 `text`；硬件键盘；粘滞修饰键；稳定持久化 `clientId`（沿用 `RealtimeClientIdStore`，重连不变）。验收：`androidTest` 覆盖 tap/500ms long press/双击拖拽锁/双指滚动/pinch/坐标逆变换；中文输入/组合键。
- [ ] **RD-P4-05 生命周期**：`onStop` 停止渲染但保留 30s 重连；回前台 rejoin（token 续签）；锁屏/来电暂停输入；PiP 留到 P5。验收：切后台/回前台/断网恢复。
- [ ] **RD-P4-06 控制权与安全**：申请/释放/心跳；`control.revoked`；剪贴板默认关闭；不把 token 写入日志。验收：两设备抢占、过期、拒绝。
- [ ] **RD-P4-07 真机验收**：Android 14 手机 + Windows host；延迟/码率/耗电记录；30 分钟稳定。验收：清单签字。

## 6. iOS 与增强（P5，2–3 周）

- [ ] **RD-P5-01 LiveKit SwiftPM**：官方 SwiftPM 产品名是 `LiveKit`（`LiveKitClient` 是 CocoaPods 名称）；在 Xcode app target 加 remote package 并在 `ios/Package.swift` 同步，pin 版本、提交 `Package.resolved`。当前工程最低 iOS 26.5，文档验收写 iOS 17，二选一并统一测试矩阵；记录包体积。验收：真机编译 + `swift test`。
- [ ] **RD-P5-02 iOS 观看/控制**：`RemoteDesktopService`、`RemoteDesktopModel`、`RemoteDesktopView`；SwiftUI 手势；`pressesBegan/Ended` + `UIKey`；软键盘 text；坐标变换与缩放/偏移逆变换。验收：iOS 真机 30 分钟；点击/滚动/中文输入。
- [ ] **RD-P5-03 剪贴板双向**：分片/重组；默认关闭；仅 controller；host→client 只发 controller；去内容型 Server 通知。验收：1/8/64 KiB 边界；权限拒绝。
- [ ] **RD-P5-04 多显示器**：`remoteDesktop.displays` 通知 + 选择 UI + `updateDisplay`（若决定支持，需补契约/RPC）；否则 v1 明确不支持并隐藏。验收：插拔/切换/分辨率变化。
- [ ] **RD-P5-05 质量档位**：流畅/清晰/自动；`update_quality` 仅 controller 改全局；viewer 只改本地订阅；服务端 clamp。验收：弱网模拟（RTT 200ms/丢包 5%）。
- [ ] **RD-P5-06 PiP**：Android `PictureInPictureParams`、iOS `AVPictureInPictureController`；LiveKit 渲染层兼容性验证。验收：真机切 PiP/回全屏。
- [ ] **RD-P5-07 移动端发布**：Android versionCode 递增；iOS `CURRENT_PROJECT_VERSION` 递增；真机清单归档。

## 7. 部署、观测与发布（P6，贯穿 P1–P3）

- [ ] **RD-P6-01 Docker LiveKit**：新增 `livekit` 服务（固定 tag/digest）、`docker/livekit.yaml`；配置 `webhook: {api_key, urls}`、`prometheus_port`、TURN `cert_file/key_file`（或 L4 `external_tls`）；Redis 地址改为 compose 实际服务名；healthcheck。验收：`docker compose up` 后 LiveKit 健康、webhook 可达。
- [ ] **RD-P6-02 端口与 TLS**：公网只开 443（TLS 反代/WS upgrade）；7881/tcp、50000-50100/udp、TURN 443/3478 按需；`use_external_ip` 在 Docker bridge/NAT 下用 host network 或 `rtc.ips`；真实 4G/企业网 ICE 验证。
- [ ] **RD-P6-03 Server 环境变量**：使用完整命名 `AGENT_SERVER_REMOTE_DESKTOP_ENABLED`、`AGENT_SERVER_LIVEKIT_URL/API_KEY/API_SECRET`、`AGENT_SERVER_REMOTE_DESKTOP_TOKEN_TTL_SECONDS`、`AGENT_SERVER_REMOTE_DESKTOP_MAX_VIEWERS_PER_SESSION`、`AGENT_SERVER_REMOTE_DESKTOP_IDLE_TIMEOUT_SECONDS`；启动时校验，未配置则能力 `supported=false`；`AGENT_SERVER_SECRET` 非默认/32+；`AGENT_SERVER_CORS_ORIGINS`/`PUBLIC_ORIGIN` 文档化。验收：缺配置不崩溃、能力隐藏。
- [ ] **RD-P6-04 反代**：WebSocket `Upgrade/Connection`、`proxy_read_timeout`；webhook 不重写 body、保留 Authorization；上传/截图大小限制。验收：nginx/caddy 样例 + 集成测试。
- [ ] **RD-P6-05 监控告警**：`rd_sessions_active`、`rd_session_duration_seconds`、`rd_input_rtt_ms`、`rd_fps/bitrate/loss`、`rd_errors_total`；告警 `rd_host_not_running` 连续 3 次、`media_unavailable` 2 分钟、单节点带宽 >70%。验收：Prometheus 指标可见。
- [ ] **RD-P6-06 版本与发布**：所有端升 2.3.0（`server/pyproject.toml` + `app.py`、`desktop-workbench/package.json` + renderer、`android/app/build.gradle.kts` + versionCode、iOS `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION`、`web-next/package.json` + demo-api、`connector/pyproject.toml`、`dsh-bridge-next/package.json`）；`docs/releases/2.3.0.md`；`CHANGELOG.md` 未发布节按用户可感知变化写。验收：`docs/versioning.md` 清单逐项打勾。
- [ ] **RD-P6-07 文档**：把 HTTP/WS 契约拆到 `docs/api/remote-desktop.md`（符合 `docs/api/README.md` 的路由规则），`docs/remote-desktop.md` 保留架构与选型；更新 `docs/README.md`、`docs/api/README.md`、`docs/api/namespace.md`、`docs/api/realtime.md`、`docs/api/device-settings.md`、`docs/api/capabilities.md`、部署 README；删除或标注提案中未实现的内容。验收：文档不再描述不存在的行为。

## 8. 测试矩阵

| 层 | 必测 | 工具/方式 | 门槛 |
| --- | --- | --- | --- |
| 契约 | 每个 schema × valid/invalid；每个 oneOf 分支；manifest hash；文档示例 | `validate.py` + CI | 全绿 |
| Server | 状态机、租约、consent、限流、错误码、迁移、多 worker | pytest + Postgres + fakeredis | `ruff` + `pytest -q` |
| Connector | RPC 幂等/超时、supervisor、崩溃清理、配置、设备设置 | pytest + fake host | 全绿 |
| Screen Host | 协议 fuzz、采集、编码、输入、release-all、孤儿 | `cargo test` + Windows/macOS CI | 全绿 |
| Web | 观看/控制/手势/坐标/键盘/剪贴板/错误态 | Playwright + 假 publisher + 真 host | P95 <250ms |
| Desktop | 同 Web + 托盘/悬浮/退出 | renderer test + 手动 | 全绿 |
| Android | 观看/手势/IME/生命周期/控制权 | Compose UI test + 真机 | 30 分钟稳定 |
| iOS | 观看/手势/键盘/剪贴板/PiP | XCTest + 真机 | 30 分钟稳定 |
| 安全 | 越权、token 重放、webhook 伪造、限流、日志脱敏 | 集成 + 手工渗透 | 无 Critical/High |
| 部署 | compose、TURN、反代、断网/弱网 | 真机 + 网络模拟 | ICE 成功、webhook 可达 |

## 9. 风险与未决项

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| host 三平台打包/签名周期长 | P2 延期 | Windows-first；macOS 并行但单独 gate；Linux P6 |
| LiveKit Rust SDK 版本 breaking | 编译/行为漂移 | 锁精确版本 + Cargo.lock；CI 固定 toolchain |
| 自托管 LiveKit 无 token 吊销 | 抢占窗口 | 短 TTL controller token + `RemoveParticipant` + host epoch |
| 浏览器/移动端后台断流 | 体验 | 明示 + 30s 重连 + PiP 后置 |
| 企业网只开 443 | 连不上 | TURN/TLS 443 + `forceRelay` + 文档 |
| SFU 运维者可看媒体 | 隐私 | v1 明示信任边界；E2EE 评估后置 |
| Desktop 渲染层副本漂移 | 功能不一致 | 同步清单 + 两边 protocol:check + 同一组测试 |
| 设备设置被远程篡改 | 隐私事件 | 默认关闭 + 本机确认 + 审计 + step-up |

**需要在实现前确认的产品问题**（按 D1–D8 默认执行，若有不同意见再改）：

1. v1 是否接受「一个 Connector 同时只有一个媒体会话」？若必须多会话，需重新设计 host 多 room 发布与带宽模型。
2. 默认是否关闭远程桌面（D5）？若产品要求默认开启，至少 view 也要本机确认/悬浮条。
3. 是否接受 Windows-first、macOS 后置？若必须首版双平台，P2 工期需增加。
4. 截图预览是否确认删除？若保留，需补 consent/悬浮条/审计/限流。
5. iOS 最低版本以工程现状 26.5 为准，还是降到 17？两者只能选一个作为验收基线。

## 10. 建议执行顺序（依赖图）

```text
P0 契约冻结
   ├─► P1 Server（Noop + fake host）────────┐
   ├─► P2 Connector + Windows host ────────┤
   └─► P3 Web/Desktop（fake publisher）────┘
                                              ├─► P3 观看联调 ─► P3 控制联调
                                              ├─► P4 Android
                                              └─► P5 iOS / 剪贴板 / 多显示器
P6 部署/监控/发布：P1 起并行，P3 前完成 LiveKit/TURN/webhook 验证
```

完成标准：P0 出口 + P1/P2/P3 出口 + 测试矩阵全绿 + 版本/文档/CHANGELOG 完成，才可以把远程桌面
标记为「可用」；在此之前 UI 一律按能力 `supported=false` 隐藏。

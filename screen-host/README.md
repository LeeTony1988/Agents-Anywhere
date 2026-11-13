# aa-screen-host

Agents Anywhere 远程桌面 v1 的屏幕主机（Windows-first，Rust）。它由 Connector 通过
stdio 拉起，双向使用 HostControl NDJSON 协议（`contracts/remote-desktop/1.0/schemas/host-control-message.schema.json`）：
**stdout 只写协议帧、stderr 只写日志**，单行一个 JSON 对象，单帧上限 4 MiB。

当前为 **P2 骨架（RD-P2-08…12）**：协议、状态机、输入、显示器枚举、测试全部真实；
媒体采集与发布按 feature gate 分批落地。

## 状态：真实实现 vs feature gate / 占位

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| HostControl 协议模型（`src/protocol.rs`） | ✅ 真实 | 与 schema 逐字段一致（tag=`type`、snake_case、camelCase、`v` 默认 1） |
| `aa.pointer` 12 字节帧（`src/pointer.rs`） | ✅ 真实 | 编解码 + clamp + seq 回绕，5 组契约测试向量全过 |
| Host 状态机（`src/host.rs`） | ✅ 真实 | idle/starting/running/stopping/crashed；start/stop 幂等；release_all；updateQuality clamp；controlGrant epoch |
| Windows 显示器枚举（`src/capture/windows.rs`） | ✅ 真实 | `EnumDisplayMonitors` + `GetMonitorInfoW`（枚举失败/无头会话时回退 `GetSystemMetrics` 主屏，id 从 `1` 开始与 `\\.\DISPLAY1` 对齐；已在本机验证返回 1536x960 真实显示器） |
| Windows 输入注入（`src/input/windows.rs`） | ✅ 真实 | `SendInput`：鼠标移动（绝对坐标）、左/右/中键、滚轮、按键（W3C code→VK 映射表）、Unicode 文本（`KEYEVENTF_UNICODE`）、pressed 集合 + release_all |
| 截图（`host.screenshot`） | ✅ 真实（自研） | 单帧 I420 → 灰度 PNG（内置极简 PNG 编码器，无外部依赖），默认 maxWidth 1280 |
| 假采集/假输入（`fake.rs`） | ✅ 真实（测试用） | 2 帧 640x360 I420 渐变；事件记录 sink |
| WGC 帧采集 | ✅ feature gate `wgc`（真实现，未在本机交互桌面验证） | `windows-capture 2.0.1`：优先 Windows.Graphics.Capture，失败时回退 DXGI Desktop Duplication；BGRA→I420、按 `maxWidth` 缩放、光标包含。默认 feature 关闭时仍用空白 stub 便于协议联调 |
| LiveKit 发布 | ✅ feature gate `livekit`（真实现，未做真实 SFU 联调） | `NativeVideoSource` + `TrackSource::Screenshare` + VP8 + `simulcast=false`；Host 采集线程持续调用 `capture_frame`，2s 更新 fps/分辨率 stats；默认走 `StubPublisher` |
| consent 确认框 / 悬浮条（RD-P2-13） | ⚠️ 部分实现 | 常驻 Windows topmost 提示窗「正在共享屏幕 · 点击结束共享」，点击后主循环优雅 shutdown；Connector 侧可选原生 MessageBox 确认。macOS 菜单栏/托盘指示未做 |
| 打包 / CI（RD-P2-14/15） | ⚠️ 部分实现 | 已加 CI `screen-host` job（Windows/macOS/Linux fmt+clippy+test，Windows 额外 `--features wgc`）；安装包集成未做 |

## 构建与检查

```bash
cd screen-host
cargo fmt --check
cargo clippy --all-targets -- -D warnings
cargo test                 # 默认 features，全绿
cargo build --features wgc # WGC feature gate 编译检查
.\scripts\build-livekit.ps1 -WebrtcDir <解压后的 win-x64-release> # LiveKit 发布
```

运行：

```bash
cargo run -- --version
cargo run -- --stdio       # 默认：hello 帧 + stdin 逐行处理
cargo run -- --once        # 只处理一行请求后优雅退出
```

## 合成采集（开发与验证用）

没有交互式桌面（CI、Windows session 0）时，可以打开合成采集：host 用同一个
`CaptureSource` 契约输出一块移动的渐变图案，后面的 VP8 编码、LiveKit 发布、浏览器解码全部是真实链路。
默认关闭，只在显式设置时启用：

```powershell
$env:AA_SCREEN_HOST_SYNTHETIC_CAPTURE = "1"
cargo run -- --stdio
```

Connector 拉起 host 时会过滤环境变量，所以本地联调还要给 Connector 打开转发开关：

```powershell
$env:AGENT_CONNECTOR_REMOTE_DESKTOP_SYNTHETIC_CAPTURE = "1"
```

`server/scripts/e2e_full_chain.py --browser` 会同时设置这两个变量，并断言浏览器里的
`<video>` 真的解出了 640×360、`readyState=4` 的画面。

## 截图的格式

`host.screenshot` 目前只输出 **PNG**（8 位灰度，按 `maxWidth` 整数步降采样），响应里的 `format`
字段就是 `png`。请求里的 `format` 只是提示：没有 JPEG 编码器时不会伪造，Server 会把 host 报告的
`format` 透传给客户端，客户端应按响应字段解码而不是按自己请求的格式解码。

需要 JPEG 时再引入编码器；在那之前，UI 侧不要假设 `format=jpeg` 一定返回 JPEG。

## 验收脚本与手动探测

`scripts/acceptance.py` 是**真机验收**用的自动部分：列显示器 → 每个显示器起一次 view 会话并截图 →
一次 control 会话跑指针网格/滚轮/修饰键/中文文本 → releaseAll → stop → shutdown，逐项打印
PASS/FAIL，最后附一份人工确认清单（多显示器落点、4K 缩放、热插拔、中文输入、stuck-key、锁屏诊断）：

```bash
python scripts/acceptance.py                 # 真桌面
python scripts/acceptance.py --synthetic     # 没有交互式桌面时
python scripts/acceptance.py --json report.json
```

2026-11-13 合成采集下实测全部 PASS（`appliedSeq=13`、截图 PNG 230 KB、
`releaseAll` 无残留、退出码 0）。`scripts/probe-input.ps1` 保留为更短的手动探测（同样会打印
`appliedSeq` 与 `releasedKeys`）。

## 端到端验证（host + LiveKit）

`scripts/e2e-livekit.py` 用真实的 host 二进制驱动一次会话，断言 `hello`（含契约要求的
`arch=x86_64`）、`host.start` 被接受、`starting → running → stopping → stopped` 的状态序列、
以及退出码 0：

```bash
livekit-server --dev --bind 127.0.0.1           # 与脚本里的 devkey/secret 一致
python -X utf8 screen-host/scripts/e2e-livekit.py screen-host/target/debug/aa-screen-host.exe
```

采集用 Windows stub（不带 `wgc` feature），所以能在 Session 0 跑；媒体面是真实的：
LiveKit 日志里能看到 `host:conn_e2e` 的 `onMediaTrack`（VP8 video）。2026-11-13 本地实测通过。

已知噪音：停止会话时 LiveKit SDK 可能打印一条 `failed to negotiate the publisher … state: closed`，
是关闭房间时仍在进行的重协商，不影响发布结果与退出码。

## 打包

```powershell
.\scripts\package.ps1                       # release 构建（stub 发布器）
.\scripts\package.ps1 -Features "wgc,livekit"
.\scripts\package.ps1 -NoBuild -DebugBuild   # 复用已有 debug 二进制
```

输出 `dist/<platform>-<arch>/aa-screen-host[.exe]` 和同目录的 `manifest.json`
（`platform`/`arch`/`version`/`sha256`/`features`/`bytes`）。Connector 启动前会校验
`sha256`：二进制被替换或只复制了一半时以 `rd_host_integrity` 拒绝运行，能力保持不可用。
安装包把它放到 `resources/screen-host/<platform>-<arch>/`（Connector 的
`default_screen_host_path` 会从这里解析）。`dist/` 已被 `.gitignore` 忽略。

## LiveKit 版本锁定

- `Cargo.toml` 中 `livekit = "=0.10.1"` 精确锁定，并要求提交 `Cargo.lock`
  （仓库不提交其他 lockfile，Rust 例外：Rust 依赖树的 lock 对本工程的**可复现构建**
  是必需的，且 LiveKit/libwebrtc 的下载型 build script 必须被钉住）。
- 默认 features 为空，`livekit` 依赖只在 `--features livekit` 时参与构建。`libwebrtc` 的
  `webrtc-sys` build script 会从 GitHub 下载预编译包；如果网络不可达，可手动下载
  `webrtc-win-x64-release.zip`，解压后设置 `LK_CUSTOM_WEBRTC=<解压后的 win-x64-release 目录>`。
- 预编译 libwebrtc 使用静态 MSVC 运行时（/MT）。`screen-host/.cargo/config.toml` 已为
  MSVC 目标启用 `+crt-static`，并设置 `CC/CXX=clang-cl`（当前 Windows 镜像的 `cl.exe`
  在 cc-rs 探针上会报 D8050）。在标准 CI 镜像上可直接 `cargo build --features livekit`。
- 真实 LiveKit 服务器联调：已用本地 `livekit-server 1.13.11 --dev` + `examples/livekit_smoke.rs`
  验证 `host:smoke` 加入 `rd_smoke`、发布 `screen` 的 VP8 `SCREEN_SHARE` track（640x360，约 18–30 fps），
  服务端 stats 可见 frames/packets/bitrate。桌面采集到发布的完整链路仍需真机验证。
- `request_keyframe` 按契约返回 false（Rust SDK 不暴露可靠的订阅端关键帧请求）。

## Windows 运行要求

- Windows 10 1809+（WGC 可用版本）；`--features wgc` 时优先 WGC，创建 capture item 失败会回退 DXGI Desktop Duplication。
- 交互式桌面会话（`SendInput` 注入到用户会话；服务/Session 0 下 WGC/DXGI 与输入注入都会失败）。本次 CI 环境无法验证真实采集，需在真机运行 `cargo test --features wgc -- --ignored`。
- 屏幕采集权限：UWP 应用对非激活窗口的捕获受 Windows 设置约束；本机确认流（RD-P2-13）
  落地后由确认框承担授权。
- `ctrlc` crate 未接入；清理路径 = stdin EOF / `shutdown` 帧 / 进程退出。

## 协议帧示例

启动即写 hello（stdout 第一行）：

```json
{"v":1,"type":"hello","protocolVersion":1,"hostVersion":"0.1.0","platform":"windows","arch":"x86_64","pid":4242,"capabilities":{"capture":true,"input":true,"publish":["stub"],"codecs":["vp8"]}}
```

请求 / 响应：

```json
{"v":1,"type":"request","id":7,"method":"host.start","params":{"sessionId":"rds_1","displayId":"0","mode":"control","quality":{"maxFps":30,"maxBitrateKbps":4000,"maxWidth":1920},"deadlineMs":5000}}
{"v":1,"type":"response","id":7,"ok":true,"result":{"accepted":true}}

{"v":1,"type":"request","id":8,"method":"host.input","params":{"identity":"viewer:pt_abc","events":[{"v":1,"seq":41,"t":"key","a":"down","code":"KeyC","mods":["ctrl"]}]}}
{"v":1,"type":"response","id":8,"ok":true,"result":{"appliedSeq":41}}
```

事件（host → connector）：

```json
{"v":1,"type":"event","event":"state","params":{"sessionId":"rds_1","state":"running"}}
{"v":1,"type":"event","event":"error","params":{"sessionId":"rds_1","code":"rd_capture_failed","message":"...","retryable":false}}
```

错误与退出码：

```json
{"v":1,"type":"error","error":{"code":"rd_host_protocol_error","message":"invalid JSON frame: ...","retryable":false}}
```

非法 JSON 或单行 > 4 MiB → 输出上述 `error` 帧并以退出码 **78**（EX_CONFIG）退出；
EOF / `shutdown` 帧 → release_all + 退出码 0。

## 已知限制

- 真实 WGC 采集（RD-P2-10）未端到端验证：`--features wgc` 目前只是编译通过 +
  空白帧 stub。
- LiveKit 发布（RD-P2-11）未端到端验证：骨架未联网构建 libwebrtc；stats 全为 0；
  `requestKeyframe` 恒 false（decisions §1.1.4：best-effort）。
- 鼠标注入使用 `MOUSEEVENTF_ABSOLUTE` + 主显示器归一化坐标；多显示器坐标
  （`d` 字段）映射 TODO。
- `deadlineMs` 仅记录不执行；`host.start` 无 `host.cancel`；120/s 指针限频、
  ack 节流未实现。
- consent 原生确认框 / 常驻悬浮条（RD-P2-13）未实现，`requireConsent` 只存字段。
- 剪贴板 offer/request 只解析，不落系统剪贴板。

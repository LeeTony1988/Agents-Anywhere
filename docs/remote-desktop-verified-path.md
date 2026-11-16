# 远程桌面「能看到真实画面」的已验证路径

这份文档记录**当前唯一被验证过能出画**的链路，以及每一环的不变量和验证方法。改动这一链路上的任何一环
之前，先按这里的步骤复验；Web / Desktop 走的就是这条路径，不要让 Android 的调试改动影响它。

## 1. 链路

```text
Windows 桌面
  └─ aa-screen-host（同一进程内只有一路采集）
       ├─ 采集线程：WGC（首选）/ DXGI（回退）→ BGRA → I420 → LiveKit NativeVideoSource
       │     · 桌面静止时 DXGI 会超时，不是错误：每秒重发最新帧
       │     · 缓存最新帧，供 host.screenshot 复用
       └─ host.screenshot：直接编码缓存帧（PNG/JPEG），不再另开采集
  └─ livekit-server（SFU，绑定设备可达地址）
       └─ Web / Desktop / Android 客户端订阅 `screen` 轨并渲染
```

## 2. 不变量（破了就黑屏）

| # | 不变量 | 违反后的症状 |
| --- | --- | --- |
| 1 | host 构建必须带 `--features livekit,wgc` | 只带 `livekit` 时 `capture/windows.rs` 发 **blank stub frame**，所有客户端全黑，而 `host.screenshot` 也是黑的 |
| 2 | 一个输出只允许一路 WGC/DXGI duplication | 第二路 `DuplicateOutput` 返回 `E_INVALIDARG`，流线程退出，客户端**没有帧**（stats 里 `frameWidth: 0`） |
| 3 | 有 media 时不要让截图采集先开 | 同上：`host.start` 若先开截图采集，流线程必然失败 |
| 4 | `AcquireNextFrame` 超时是空闲，不是错误 | 静止桌面 1 秒后流线程 break，画面永远停住 |
| 5 | 媒体 URL 必须客户端可达 | 客户端连 `127.0.0.1` 时只有同一台机器能出画；模拟器要 `ws://<局域网IP>:7880`（`AGENT_E2E_LIVEKIT_URL`） |
| 6 | 后加入的观看者要能触发关键帧 | 发布端只在轨道启动时发关键帧；客户端首帧迟到时要 `request_keyframe`，host 侧通过重新发布轨道满足 |
| 7 | 采集线程要有 COM apartment | WinRT 采集在无 apartment 的线程上失败，流线程直接退出 |

## 3. 验证配方（按顺序，任一步失败就停）

```powershell
# 0) host 二进制必须是带 feature 的构建（cargo test/clippy 会覆盖它！）
cd screen-host
.\scripts\build-livekit.ps1 -WebrtcDir target\webrtc\win-x64-release   # 默认 livekit,wgc

# 1) 起一套端到端环境（真实采集，不要加 --browser，那会打开合成采集旁路）
$env:AGENT_E2E_DB_URL="postgresql+asyncpg://postgres:@127.0.0.1:54101/agents_anywhere_e2e"
$env:AGENT_E2E_LIVEKIT_URL="ws://<局域网IP>:7880"
server\.venv\Scripts\python.exe -X utf8 server\scripts\e2e_full_chain.py --web-hold-seconds 3600

# 2) 被控端自己抓一张：应该是真实桌面，不是纯黑
server\.venv\Scripts\python.exe -X utf8 <截取脚本> <token> <sessionId>
#   黑图约 9–18 KB，真实桌面 100 KB 以上

# 3) Web 端取样（唯一能证明"真的有像素"的方法）
#    在浏览器 console 里：
#    const v=document.querySelector('video');const c=document.createElement('canvas');
#    c.width=48;c.height=20;c.getContext('2d').drawImage(v,0,0,48,20);
#    const d=c.getContext('2d').getImageData(0,0,48,20).data; // 统计平均亮度
#    期望：avg > 100；全黑时 avg = 0

# 4) Android 端看 WebRTC 统计
adb logcat -d | Select-String 'VideoReceiveStreamInterface stats'
#    期望：total_bps > 0、frameWidth/frameHeight 非 0、framesDecoded 递增
```

## 4. 当前状态

| 端 | 画面 | 控制 | 说明 |
| --- | --- | --- | --- |
| Web | ✅ 已验证（canvas 取样 avg ≈ 224–235） | ✅ | 申请/释放控制、截图下载都验证过 |
| Desktop | ✅ 复用 Web 的同一实现（同一份 `remote-desktop-view.tsx` 与媒体代码） | ✅ | Electron 内嵌同一渲染器 |
| Android | ✅ 已验证（模拟器）：走 bitmap 回退路径后画面可见 | ⚠️ 已修坐标映射与接管授权（§6），待真机复验 | WebRTC 的 GL sink 在模拟器上「收到帧但不画」，因此增加了回退渲染，见 §5.3 |

### 4.1 Android 输入：2026-11-14 修掉的两个问题

| 问题 | 现象 | 原因 |
| --- | --- | --- |
| 指针坐标按整块画布换算 | 点按落在离手指很远的桌面位置，看着像「没反应」 | 手势协程握着首帧组合时的 `RemoteDesktopInteraction`（此时帧尺寸未知，`aspectFitViewport` 退化成整块画布）；Compose 的 `pointerInput` 只在 key 变化时重启 |
| 接管控制权后发不出输入 | 画面正常，鼠标键盘全无反应；SFU 直接丢弃 | LiveKit 的 `canPublishData` 在入会时就固定，Server 接管路径只给旧 controller 降权、没给新 controller 提权 |
| 画面模糊 | 手机上文字发虚，横屏放大后更明显 | 默认 1600 宽/4 Mbps 被手机放大；bitmap 回退路径还按 1/2 分辨率、4 fps 绘制 |
| 单指拖动不产生拖拽 | 点按正常，拖窗口/选文字/拉滚动条无效 | 手势只发 `aa.pointer` 移动、没有先按左键；host 看到的是 hover |
| 切换清晰度报并发会话上限 | 清晰度菜单一改就报错，重新进入观看页也会 | 每次重建观看页都新增 participant，累计到观看者上限；现在未连接的旧 participant 会复用名额，并阻止旋转重建 Activity |

## 5. Android 渲染：结论与迁移步骤

### 5.1 结论（2026-11-14 实测）

WebRTC 的 GL sink 在模拟器上**收到帧但不画**。证据链：

1. 同一个 `VideoTrack` 上挂一个诊断 `VideoSink`，逐帧把 I420 的 Y 平面取平均：
   `sink frame #1 1280x540 meanLuma=173.2` —— 解码出来的是真实桌面，不是黑帧；
2. 同一时刻界面仍是黑块；`renderer.init()` 没有抛异常，`SurfaceView` 图层也在 SurfaceFlinger 里；
3. 同一路流在浏览器里取样 `avg 224`，说明发布端与 SFU 正常。

因此问题定位在 **WebRTC EglRenderer 在本机模拟器上不出图**，与应用逻辑无关（TextureView / SurfaceView、
init 在 factory / 在 update、host GPU / swiftshader 四种组合都是黑）。

### 5.2 已实现的回退渲染

`LiveKitMediaSession` 在 tee sink 里把解码后的 I420 转成 `Bitmap`（每 100 ms 一帧，全分辨率），
`RemoteDesktopScreen` 在 `compatFrame` 有值时直接画 `Image`，否则用原来的 GL sink。启用条件：

- **模拟器**（`Build.FINGERPRINT/MODEL/PRODUCT/HARDWARE` 命中 `generic`/`sdk`/`ranchu` 等）默认开启；
- 真机上如果 GL sink 3 秒内没有收到 `onFirstFrameRendered()`，自动切到回退路径。

代价：回退路径是 CPU 转码 + Compose 位图绘制（约 10 fps），只用于兼容，不作为默认体验。

### 5.3 迁移步骤（每步都能回退）

1. ✅ 确认帧到达（诊断 sink 的 `meanLuma`）。
2. ✅ `renderer.init` 提前到 `AndroidView(factory)`。
3. ✅ 实现并验证 bitmap 回退（模拟器上可见真实桌面）。
4. ⬜ 真机验证：确认真机走 GL 路径（日志 `GL sink drew its first frame`）且画面正常；若某些真机也不出图，
   回退路径会自动接管。
5. ⬜ 用 `onFirstFrameRendered` + 帧计数做更精细的健康度判断，替代「3 秒无首帧」这个粗粒度阈值。
6. ✅ 回退路径改为全分辨率、100 ms 节流；后续可按机型再降采样。

### 5.4 不要动的东西

- Web / Desktop 的渲染与媒体代码（`web-next/src/components/remote-desktop/*`、`desktop-workbench/renderer`
  的同名文件）与本次 Android 改动无关，改完 Android 必须复跑 §3 的 Web 取样；
- 服务端的 capability/媒体逻辑未改。

### 5.5 横屏（放大）观看

- 旋转设备即进入横屏全屏：顶部栏不渲染、系统栏隐藏（`RemoteDesktopImmersiveBars`），画面按比例铺满；
- 顶部栏新增「全屏」按钮，直接请求 `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`，无需转动手机；
- 横屏时左上角有「退出全屏」按钮，恢复 `SCREEN_ORIENTATION_UNSPECIFIED` 并显示系统栏；
- 画面用 `ContentScale.Fit`，保证整屏可见且与归一化指针坐标一致（`Crop` 会裁掉边缘、点选会偏）。
- 放大后画质受 `quality.maxWidth` 限制：默认 1920，清晰档 2560；切换清晰度会通过 `update_quality` 真正调整采集宽度和编码码率。

## 6. 输入链路自检（「能看到桌面但点不动」）

输入走媒体数据通道（`aa.input` / `aa.pointer`），把下面几段日志按顺序对一遍，就能看出断在哪一段：

| 判据 | 看哪里 | 期望 |
| --- | --- | --- |
| 手势被识别 | `adb logcat -s RDInput` | 点按出现 `button left down=true at (0.42, 0.61)`；拖动出现 `gestures=RemotePointer` |
| 拿到发数据权限 | `adb logcat -s RDMedia` | `connect identity=viewer:pt_… canPublishData=true` |
| 数据真的发出去 | `adb logcat -s RDMedia` | 没有 `publishData(aa.input) failed #…` |
| host 收到 | Connector 日志 | `screen-host: … data-channel input arrived: topic=aa.input from=viewer:pt_…` |
| host 丢弃 | Connector 日志 | `… dropped: sender=… is not the controller …`，或 `received=… applied=… dropped=N (identity=… malformed=… throttled=… rejected=…)` |
| host 能注入 | Connector 日志 / 启动结果 | host `hello.capabilities.integrity=medium`；`low`/`untrusted` 会以 `rd_host_integrity_low` 拒绝，不会出现 `applied` 但光标不动 |

坐标落点看 `button … at (x, y)`：竖屏信箱边距下点画面正中应接近 `(0.5, 0.5)`，点画面右下角应接近 `(1.0, 1.0)`；
若点在画面边缘却得到整屏比例（例如画面只占中间四分之一，却得到 `y≈0.37`），说明手势层用的视口不是当前帧的。

开发机同时存在 `target/debug` 和 `target/release` 时，Connector 现在优先使用 release：`cargo test`/`cargo build` 生成的 debug 二进制常没有 `wgc/livekit` 特性，而且在沙箱里会带 Low integrity 标签，曾导致 `rd_host_integrity_low`。

host 的这几行原本只在 `--debug` 下可见，现在 Connector 把带 `data-channel input` 的行提到 `info`
（约每秒一行），其余 host stderr 仍是 debug。**改完 host 必须重新构建并重启 Connector**，否则跑的还是
旧二进制：`screen-host\scripts\build-livekit.ps1 -WebrtcDir target\webrtc\win-x64-release`。

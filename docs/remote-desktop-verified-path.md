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
| Android | ⚠️ 解码正常（`frameWidth 1280`、`total_bps > 0`）、画面黑 | ✅ 控制中、Lease、修饰键、截图按钮均正常 | 渲染层问题，见下节 |

## 5. Android 渲染迁移步骤（逐步，每步都要能回退）

1. **已完成**：确认帧到达 → `adb logcat` 里 `VideoReceiveStreamInfo stats` 的 `frameWidth/frameHeight`
   非 0、`total_bps > 0`；`screencap` 里视频区域黑。
2. **已完成**：把 `renderer.init(eglContext, null)` 从 `AndroidView(update)` 移到 `factory`，避免
   renderer 在挂载前未初始化而丢帧。
3. **进行中**：把 `TextureViewRenderer` 换成 `SurfaceViewRenderer`（独立 GL Surface）。
   ⚠️ `Activity`/`screencap` 抓不到 SurfaceView 内容，**这一步只能靠人眼看模拟器窗口**。
4. 若仍黑：在 `LiveKitMediaSession` 里关闭硬件解码（`RoomOptions(videoCodec = VideoCodec.VP8)` +
   `VideoTrack.setPlayoutDelay`/`peerConnectionFactory` 的 decoder 选择），对比 VP8 软件解码。
5. 若软件解码正常：在 `APP_PAGES`/会话页面加一条「兼容模式」开关，默认硬件解码，黑屏时降级。
6. 每一步都要跑一遍第 3 节的 **Web 复验**，确认没有把 Web/Desktop 的画面弄坏（这两个端不共用
   Android 的渲染代码，但共用服务端与媒体链路）。

## 6. 相关提交

- `cc175d93`：host 默认启用真实 WGC 采集；`requestKeyframe` 改为按需重发布
- `d4019b18`：单一采集所有权、空闲重发、截图复用缓存帧、采集线程 COM 初始化
- `5d6c41c6`：Android 首帧迟到时自动请求关键帧
- `066d4b92b`/`a73f23a3` 等：启动预算、错误映射等前置修复

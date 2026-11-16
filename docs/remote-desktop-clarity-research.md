# 远程桌面清晰度调研与实现方案

> 目的：先回答“为什么手机上看不清”，再看其他远程桌面软件怎么做，最后给出适合 Agents Anywhere 的阶段性方案。
> 本文只做调研和方案设计，不改变现有行为。

## 1. 结论先行

P0 实施前，手机端“清晰度不够”不是单一问题，按影响从大到小：

1. **码率太低**：本机实测 2560×1080、30 fps、VP8，实际发送约 **7.1 Mbps**。折算约 **0.086 bit/pixel/frame**。文字/代码需要远高于此的码率，Parsec 对编程场景建议 25–30 Mbps，Moonlight 1080p60 默认约 20 Mbps。
2. **VP8 + 4:2:0**：VP8 本身压缩效率低于 H.264/HEVC/AV1，且 4:2:0 会丢弃一半色度信息；彩色文字、代码高亮和 ClearType 边缘会发虚。
3. **采集/缩放链路有损**：当前 `bgra_to_i420` 用最近邻取样做缩放（`screen-host/src/capture/wgc.rs`），缩小时会产生锯齿和文字细节丢失；如果先缩到 1920 再由手机放大，损失叠加。
4. **没有针对文字的内容策略**：没有 `contentHint=text`、没有文字/图像分流、没有脏区域更新、没有 lossless/near-lossless 文本模式。所有内容都按普通视频帧编码。
5. **分辨率没有和手机视口匹配**：当前是固定 `maxWidth`（默认 1920，清晰档 2560），不是“按手机物理视口/窗口大小编码 1:1”。手机横屏宽 2800 时，2560 还会被放大；竖屏时 2560 又被缩小，文字变小后只能靠放大查看。
6. **质量反馈缺失**：客户端只显示预设，不显示实际编码分辨率/码率；会话由其他客户端先创建时，后来加入的 Android 可能沿用别人的 1920 质量。

最优先做的不是上 4:4:4，而是：**把码率提到 20–30 Mbps、改成 H.264/VP9（优先硬件编码）、采集保持 1:1、加文字内容提示/保持分辨率策略**。这四项的收益远大于直接换 4:4:4。

## 2. 当前实现与实测

### 2.1 链路

```
Windows WGC/DXGI 采集 BGRA
  → bgra_to_i420（当前最近邻缩放）
  → LiveKit Rust SDK，固定 VP8，simulcast=false
  → LiveKit SFU
  → Android LiveKit SDK 解码
  → TextureViewRenderer（SCALE_ASPECT_FIT）
```

关键代码：

| 环节 | 位置 | 现状 |
| --- | --- | --- |
| 采集宽度 | `screen-host/src/capture/wgc.rs` / `capture/windows.rs` | `min(display.width, quality.max_width)`，按 `max_width` 缩放 |
| BGRA→I420 | `screen-host/src/capture/wgc.rs::bgra_to_i420` | 最近邻取样；色度只取偶数像素 |
| 编码 | `screen-host/src/publish/livekit.rs` | `VideoCodec::VP8`，`simulcast=false`，只设置 `max_bitrate/max_framerate` |
| 质量预设 | `android/.../RemoteDesktopQualityPreset.kt` | P0 后：流畅 1280；清晰 2560；自动 1920；码率由 host 按分辨率 × 帧率自动计算 |
| 客户端渲染 | `android/.../LiveKitMediaSession.kt`、`RemoteDesktopScreen.kt` | `TextureViewRenderer`，`SCALE_ASPECT_FIT`；GL 失败时 bitmap 回退 |

### 2.2 P0 前的本机实测数据

用 Server API 创建控制会话，质量请求 `2560×1080 / 30fps / 8 Mbps`，在 LiveKit 日志中读取发布轨统计：

- track：`screen`，`width=2560`，`height=1080`，`mime=video/VP8`
- layer：`quality=LOW`，`width=2560`，`height=1080`，`bitrate=8000000`
- 实际发送：约 **30.06 fps**，**7.13 Mbps**，12 秒 366 帧
- 该会话的 Server `stats` 为 `null`（当前没有把 host stats 可靠落到会话 API）

计算：

```
2560 × 1080 × 30 ≈ 82.9 M pixel/s
7.13 Mbps / 82.9 M ≈ 0.086 bit/pixel/frame
```

作为对比：

| 场景 | 分辨率/帧率 | 码率 | bit/pixel/frame |
| --- | --- | ---: | ---: |
| 当前清晰档 | 2560×1080@30 | 7.1 Mbps | ≈0.086 |
| 1080p 文本推荐下限 | 1920×1080@30 | 20 Mbps | ≈0.32 |
| Parsec 编程建议 | 1080p 级 | 25–30 Mbps | >0.4 |
| Moonlight 1080p60 默认 | 1920×1080@60 | ≈20 Mbps | ≈0.16（60fps 下更依赖编码效率） |
| Moonlight 4K60 4:4:4 | 3840×2160@60 | ≈160 Mbps | ≈0.32 |

> 注：bit/pixel 只是粗略指标，不同编码器/内容差异很大；但它足以说明当前 7 Mbps 对 2560 宽文字明显偏低。

## 3. 其他远程桌面软件怎么做

### 3.1 Parsec

- 默认 4:2:0、约 10 Mbps；对编程/写作建议 **4:4:4 Color Mode + H.265 + 25–30 Mbps**，并建议固定 FPS。
- 4:4:4 通过 H.265 硬件编码实现，主要解决彩色文字边缘、代码高亮和图形设计的色度损失。
- 提供“双流”用法：一条流专门用于文字/编程，另一条用于视频/测试，各自独立画质设置。

来源：[Recommended Settings for Programming and Writing](https://support.parsec.app/hc/en-us/articles/360045797752-Recommended-Settings-for-Programming-and-Writing)、[4:4:4 Color Setting](https://support.parsec.app/hc/en-us/articles/360045446151-4-4-4-Color-Setting)

### 3.2 Microsoft RDP / Azure Virtual Desktop

- 默认按内容分流：约 80% 图形数据是文字，RDP 用**专用文字 codec** 以最低成本获得最佳文字质量；图片/视频分别走 AVC/H.264 或 RemoteFX。
- 支持 4:2:0 和 4:4:4；4:4:4 需要 AVC/H.264 full-screen encoding + 高图像质量策略，代价是更高带宽。
- 支持动态分辨率：RDP 8.1 起客户端可在窗口大小/全屏变化时通知服务器动态更新远程会话分辨率，不需要重连。
- 提供硬件/软件编码、自适应图形、delta detection/caching、full-screen video encoding 等模式。

来源：[Graphics encoding over the Remote Desktop Protocol](https://learn.microsoft.com/en-us/azure/virtual-desktop/graphics-encoding)、[Resolution and Scaling Level Updates in RDP 8.1](https://techcommunity.microsoft.com/blog/microsoft-security-blog/resolution-and-scaling-level-updates-in-rdp-8-1/248077)

### 3.3 Moonlight + Sunshine

- 以游戏串流为主，但同样适用于桌面：客户端可选择分辨率/帧率/码率，常用默认约 1080p60 = 20 Mbps、1440p120 = 57 Mbps、4K60 = 80 Mbps；4:4:4 会显著提高码率（4K60 4:4:4 约 160 Mbps）。
- 社区实测和 Sunshine issue 都指出：**4:4:4 对桌面文字明显更清晰**；4:2:0 下文字/终端/IDE 会明显发虚。
- 客户端分辨率与串流分辨率匹配是清晰度的前提；分辨率选错会直接导致低质量。

来源：[Moonlight FAQ](https://github.com/moonlight-stream/moonlight-docs/wiki/Frequently-Asked-Questions)、[Sunshine 4:4:4 讨论](https://github.com/LizardByte/Sunshine/issues/4836)、[Moonlight 码率讨论](https://www.reddit.com/r/MoonlightStreaming/comments/1j1s4ap/how_much_should_i_set_the_bitrate/)

### 3.4 RustDesk

- 提供 **True color (4:4:4)** 选项，以及 VP8/VP9/AV1/H.264/H.265 等 codec 选择。
- 官方文档明确 True color (4:4:4) 在 Desktop Settings → Display → Other default options 中；社区经验是 VP9 + 4:4:4 对文字提升明显，AV1 在部分机器上质量最好。
- 也说明 codec 与硬件编码能力相关，不是所有组合都可用。

来源：[RustDesk Advanced Settings](https://rustdesk.com/docs/en/self-host/client-configuration/advanced-settings/)、[RustDesk 4:4:4 discussion](https://github.com/rustdesk/rustdesk/discussions/4952)

### 3.5 AnyDesk / TeamViewer / VNC

- **AnyDesk**：自研 DeskRT codec，定位就是 GUI/文本/低带宽场景，强调压缩效率和低延迟，而不是通用视频编码。
- **TeamViewer**：提供“自动质量 / 优化质量 / 优化速度 / 自定义”四档，用户可在清晰度和流畅度之间取舍。
- **VNC**：Tight/ZRLE/Raw 等编码可以做到无损，文字最清晰，但带宽/CPU 代价高，适合局域网。
- 共同点：**要么有文字优化 codec，要么把“质量/速度”选择权交给用户，并明确展示或控制分辨率/码率。**

来源：[AnyDesk performance](https://anydesk.com/en/performance)、[TeamViewer quality options](https://teamviewer.com/en/global/support/knowledge-base/other-products/meeting/features-and-settings/teamviewer-meeting-options)

### 3.6 WebRTC 标准给出的方向

W3C `MediaStreamTrack.contentHint` 对 `"text"` 的定义非常直接：

- 文字内容必须避免量化/缩放导致不可读；
- 建议使用 `maintain-resolution`（宁可降帧率也不降分辨率）；
- 如果 codec 是 AV1，应启用文字模式编码工具。

来源：[MediaStreamTrack Content Hints](https://www.w3.org/TR/mst-content-hint/)

这说明我们当前的“普通视频帧 + 固定码率”方向本身就不适合桌面文字；至少应把 `contentHint=text` / `maintain-resolution` 作为默认策略。

## 4. 横向对比总结

| 能力 | Parsec | RDP/AVD | Moonlight | RustDesk | 当前实现 |
| --- | --- | --- | --- | --- | --- |
| 文字专用编码/分流 | 部分（4:4:4 + H.265） | ✅ 专用文字 codec | ❌（靠高码率/4:4:4） | ❌（靠 codec/4:4:4） | ❌ |
| 4:4:4 | ✅ H.265 | ✅ AVC 4:4:4 | ✅（高码率） | ✅ True color | ❌ 4:2:0 |
| 现代 codec | H.264/H.265 | H.264/H.265 | H.264/HEVC/AV1 | VP8/VP9/AV1/H.264/H.265 | 仅 VP8 |
| 码率 | 10–30+ Mbps | 自适应/策略 | 20–160 Mbps | 可调 | 1.5–8 Mbps |
| 分辨率匹配 | 客户端设置/虚拟显示 | ✅ 动态分辨率 | ✅ 客户端选择 | ✅ 客户端选择 | 固定 maxWidth |
| 内容提示/保持分辨率 | 有 | 有 | 有 | 部分 | ❌ |
| 质量反馈 | 有（延迟/码率） | 有 | 有 | 有 | 仅预设，无实际值 |

结论：主流产品没有一个是“VP8 + 8 Mbps + 固定 1920/2560”来做文字桌面的。当前实现处在“能看画面，但达不到可读文字”的阶段。

## 5. 适合本项目的分阶段方案

### P0：低成本、立刻改善（建议先做）

目标：不改变协议和部署，先把现有链路调到合理范围。

1. **取消固定码率上限，按分辨率 × 帧率自动给预算**（2026-11-15 已实施）
   - 设备设置和客户端预设不再把 20 Mbps 当成固定墙；`maxBitrateKbps=0` 表示自动。
   - Host 用实际采集尺寸和 `maxFps` 计算编码器上限：`宽 × 高 × fps × 0.15 bit/pixel`，
     1080p30 约 9.3 Mbps、1440p30 约 16.6 Mbps、4K30 约 37 Mbps、4K60 约 75 Mbps。
   - 这只是 libwebrtc 的**上限**，不是目标值；实际发送仍由 BWE、丢包和帧间变化决定。
   - 显式正数仍然保留，供旧客户端或需要硬上限的部署使用。

2. **编码器保持分辨率**
   - 明确设置 `degradationPreference = MaintainResolution`（LiveKit `TrackPublishOptions` 已支持）。
   - 目标：带宽不足时降帧率，不降分辨率；文字场景宁卡勿糊。
   - 如果 LiveKit Rust SDK 没有 `contentHint` API，至少在 `TrackPublishOptions`/编码参数层实现同等策略。

3. **采集缩放改成高质量滤波**
   - 将 `bgra_to_i420` 的最近邻缩放改为**面积平均/双线性**（缩小时优先 area）；色度用 2×2 平均而不是取左上像素。
   - 如果目标是 1:1（见第 4 点），这一步影响会变小，但对“手机竖屏看 2560 宽”仍然重要。

4. **采集分辨率与手机视口匹配**
   - Android 把物理视口（`width × dpr`、横竖屏、缩放后的可视区域）作为质量请求的一部分。
   - 清晰档：`maxWidth = min(display.width, 手机物理宽度)`，优先 1:1；不要固定 2560 后让手机再放大/缩小。
   - 若手机物理宽度小于桌面宽度，考虑让用户选择“适应窗口”（缩小）或“1:1/放大查看”（高分辨率 + 本地平移）；两种模式的目标不同。

5. **客户端渲染**
   - 1:1 时使用精确尺寸，避免 TextureView 的线性放大；缩放时使用高质量 GPU filter。
   - 在统计面板显示：实际编码分辨率、实际码率、解码 fps、丢包；让“清晰度”可观测。

6. **质量状态一致性**
   - 会话由其他客户端先创建时，后来者应能请求/协商自己的清晰度；至少 UI 要显示“当前会话实际质量”，不能只显示本地预设。
   - 质量变更后等待 Server `session.updated` 再更新 UI，避免“看起来切了、实际没生效”。

### P1：换 codec / 硬件编码（中期）

优先级建议：**H.264 硬件 > VP9 > AV1 > 4:4:4**。

1. **H.264 硬件编码/解码**
   - LiveKit Rust 已支持 `VideoCodec::H264`；Windows 可用 NVENC/QSV/AMF，Android 普遍有 H.264 硬解。
   - 同样码率下 H.264 通常优于 VP8；硬件编码还能降低 host CPU。
   - 需要处理 license/专利和不同 GPU 的可用性，失败时回退 VP8。

2. **VP9 / AV1**
   - VP9/AV1 压缩效率更高，适合 20 Mbps 以内的文字；AV1 还有 screen content tools。
   - 但编码 CPU/硬件支持差异大；先做能力探测和回退链。

3. **4:4:4 文本模式**
   - H.264 High 4:4:4 Predictive 或 HEVC 4:4:4；硬件支持有限，软件编码 CPU 高。
   - 建议只作为“文本/代码”模式，按需开启，不作为默认；码率约为 4:2:0 的 1.5–2 倍。
   - 如果 Android 端硬解不支持 4:4:4，会退回软解，手机发热/耗电需要评估。

### P2：分辨率协商 / 虚拟显示（体验上限）

这是 RDP/Parsec/Moonlight 体验好的关键，但工程量最大：

- 客户端上报物理视口、DPR、方向和缩放比例；
- host 端把远程桌面分辨率调整为客户端视口，或创建**虚拟显示器**（Windows IddSampleDriver 等）；
- 编码分辨率 = 客户端物理像素，做到 1:1，不再“桌面 2560 宽 → 手机 2800 宽放大”；
- 手机竖屏时使用竖屏虚拟显示，避免把 21:9 桌面压成一条。
- 复杂点：Windows 显示模式切换、DPI 缩放、虚拟显示驱动签名/安装、会话恢复。

### P3：文字/内容自适应（长期）

- 文字/图像/视频分类，分别用不同编码策略；
- 脏区域/瓦片编码，只传变化区域；
- 无损或近无损文字模式；
- 这已经接近 RDP/AnyDesk 的自研 codec 领域，投入大，建议在 P0/P1 验证后再决定。

## 6. 建议的验证方法

不要只看“感觉更清晰”，要可量化：

1. **host 侧指标**：LiveKit track 的实际 `width/height/bitrate/fps`；当前 Server session `stats` 为空，需要修好 stats 上报/展示。
2. **客户端侧指标**：Android `VideoReceiveStreamInterface stats`（frameWidth/frameHeight/framesDecoded/total_bps）。
3. **画质对比**：固定一张含 8–14px 中文/英文代码的测试图，分别用 8/12/20/30 Mbps、VP8/H.264/VP9、4:2:0/4:4:4 截图，做并排对比和 OCR 可读性检查。
4. **主观测试**：IDE、终端、浏览器、Excel 四类窗口，横屏/竖屏/2x 放大各看一次。
5. **网络矩阵**：同一局域网 Wi-Fi、5GHz、弱网（限速/丢包）各测一遍，确认提高码率不会导致卡顿。

## 6.1 码率怎么定、为什么实际值会低

码率经过四层，任何一层都可能把最终码率压下来：

1. **设备设置**：`remoteDesktopMaxBitrateKbps` 默认 `0`（自动）；正数是可选的设备级硬上限，范围 500–100000。
2. **客户端预设**：Android/Web/Desktop 只选择分辨率与帧率，不再发送固定 kbps；服务端把缺省值解释为自动。
3. **Host 编码器**：`TrackPublishOptions.video_encoding.max_bitrate` 由实际采集宽高和 `maxFps` 计算：
   `kbps = width × height × fps × 0.15 / 1000`，下限 2 Mbps、上限 100 Mbps。显式正数会覆盖这个计算。
4. **WebRTC 带宽估计**：即使预算给到 16 Mbps，实际发送仍受订阅端 BWE/丢包/网络影响；静态桌面还会因为帧间变化少而临时低于目标值。

实际值低时，按这个顺序判断：

- 先看质量菜单顶部的 `分辨率 × 码率 × fps`。如果分辨率和 fps 对，码率低但画面清楚，通常只是静态内容；
- 滚动/拖动窗口时再看一次，BWE 会把峰值抬起来；如果峰值仍低，瓶颈在网络或 BWE；
- 如果分辨率被压到 1280/1920，说明 `maxWidth` 或设备设置先限住了，不是码率问题；
- VP8 4:2:0 在文字边缘仍然会有色度损失，继续提升需要 P1 的 H.264/VP9/AV1 或 4:4:4。

`maxBitrateKbps` 现在只是编码器预算，不是“必须达到”的目标。把预算调得比链路高不会让画面更清楚，只会让 BWE 在拥塞时更晚降下来；真正决定清晰度的是**实际编码分辨率**、**编码器在目标码率下的质量**和**链路可用带宽**。

## 7. 推荐落地顺序（P0 已在 2026-11-15 实施）

1. ✅ **P0-1/2/3/5 已实施**：按分辨率 × 帧率自动码率预算、保持分辨率、面积平均缩放、host 定期上报实际码率/分辨率、客户端质量菜单显示实际值。
2. ⚠️ **P0-4 已做基础版**：Android 上报物理视口并按视口选择采集宽度，避免小屏手机无意义地抓 2560；真正的 1:1/虚拟显示仍留到 P2。
3. **然后 P1 H.264 硬件**：在同样 20 Mbps 下应该比 VP8 明显更好；如果 Android 硬解兼容性 OK，作为默认。
4. **4:4:4 作为文本模式**，在 P1 之后按设备能力开启，不默认全开。
5. **P2 虚拟显示**单独立项，属于体验重构，不要和当前清晰度修复混在一个 PR。

## 8. 参考资料

- Parsec: [Recommended Settings for Programming and Writing](https://support.parsec.app/hc/en-us/articles/360045797752-Recommended-Settings-for-Programming-and-Writing)
- Parsec: [4:4:4 Color Setting](https://support.parsec.app/hc/en-us/articles/360045446151-4-4-4-Color-Setting)
- Microsoft: [Graphics encoding over the Remote Desktop Protocol](https://learn.microsoft.com/en-us/azure/virtual-desktop/graphics-encoding)
- Microsoft: [Resolution and Scaling Level Updates in RDP 8.1](https://techcommunity.microsoft.com/blog/microsoft-security-blog/resolution-and-scaling-level-updates-in-rdp-8-1/248077)
- Moonlight: [Frequently Asked Questions](https://github.com/moonlight-stream/moonlight-docs/wiki/Frequently-Asked-Questions)
- Sunshine: [Pixel format negotiation / 4:4:4 讨论](https://github.com/LizardByte/Sunshine/issues/4836)
- RustDesk: [Advanced Settings](https://rustdesk.com/docs/en/self-host/client-configuration/advanced-settings/)
- RustDesk: [4:4:4 color mode discussion](https://github.com/rustdesk/rustdesk/discussions/4952)
- W3C: [MediaStreamTrack Content Hints](https://www.w3.org/TR/mst-content-hint/)
- AnyDesk: [Performance / DeskRT](https://anydesk.com/en/performance)
- TeamViewer: [Quality options](https://teamviewer.com/en/global/support/knowledge-base/other-products/meeting/features-and-settings/teamviewer-meeting-options)

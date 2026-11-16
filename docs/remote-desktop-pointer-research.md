# 远程桌面手机端鼠标精细化调研

状态：调研完成，P0 已于 2026-11-15 实施，并按首次上机反馈收紧触控板尺寸、增加
“按住圆环拖拽”和滚轮轨道。本文回答“手机已经能拖动，但操作还不够精细”该如何优化；
第 6 节给出一份可落地的 P0/P1/P2 方案。当前实现见
`android/app/src/main/java/com/agentsanywhere/app/feature/remotedesktop/input/`。

## 1. 结论先说

主流远控产品没有靠“把灵敏度调低”解决精细操作，而是把**触摸到鼠标的映射拆成两种明确模式**，
再在“鼠标模式”里做指针增益、按钮语义和视觉反馈：

1. **触控/直触模式（Direct Touch）**：点哪里，鼠标就到哪里；适合点大按钮、浏览网页。
2. **鼠标/触控板模式（Mouse / Trackpad）**：单指滑动只移动光标，不按左键；轻点才点击；
   适合点小按钮、选中文本、拖拽窗口和滚动条。

AnyDesk、Chrome Remote Desktop、Microsoft Remote Desktop、TeamViewer、ToDesk、向日葵、
RustDesk 都提供这两种模式，差别只在默认值和手势细节。**同一套手势里既做“点哪跳哪”又做
“一滑就按住左键”，是当前操作不精细的根因之一**：想移动光标时会误拖拽，想拖拽时又难以稳定。

第二个根因是**固定 1.8 倍灵敏度、没有速度曲线**。手指 1 px 的抖动会变成远端约 3 px 的位移，
小目标（关闭按钮、表格边框、文本插入点）很难一次对准；而长距离移动又需要反复滑。
成熟产品用“慢速低增益、快速高增益”的非线性曲线，并允许用户调灵敏度。

第三个根因是**缺少光标反馈**。手机屏幕上只看到远端画面里的光标，网络延迟会让“手指已经停了、
光标还在走”看起来像不准。触控板模式需要在本地画一个预测光标，或至少缩短指针采样到显示的链路。

## 2. 竞品做法

| 产品 | 模式 | 关键手势/行为 | 对我们的启示 |
| --- | --- | --- | --- |
| Microsoft Remote Desktop（Windows App） | Direct touch / Mouse pointer | Direct touch 点哪点哪，不显示光标；Mouse pointer 单指按住拖动移动光标，轻点左键，双击按住拖动，双指轻点右键，双指按住拖动滚轮 | 两种模式语义必须彻底分开；鼠标模式要支持“按住拖动”而不只是双击锁定 |
| AnyDesk | Touch input / Touchpad input | Touch input 点哪跳哪；Touchpad 单指滑动移动、轻点左键、长按右键、三指滑动滚动；拖动是“按下-抬起-再按下-移动” | 触控板模式点击发生在**当前光标**，不是手指位置；拖动要有明确的二次按下语义 |
| Chrome Remote Desktop | Trackpad（默认）/ Touch | 单指滑动移动、轻点左键、双指轻点右键、三指轻点中键、双指滑动滚动、双指捏合缩放；长按出现灰圈后拖动 | 默认触控板模式对手机控桌面更友好；右键/中键/滚动用多指手势区分 |
| TeamViewer | Touch mode / Mouse mode | 手机连 Windows 只提供 Mouse mode；平板可选两种；鼠标模式把屏幕当触控板 | 小屏设备默认鼠标模式是行业常见选择 |
| ToDesk | 触控板模式 / 直触模式 | 触控板模式滑动移动光标、单击左键、双击双击、双指捏合滚动，官方定位为“精确度高，适合点小按钮、选中文本”；直触模式点哪点哪 | 把“精确”作为触控板模式的第一卖点 |
| 向日葵 | 指针模式 / 触控模式 | 指针模式显示虚拟鼠标光标，按下移动等于按住左键拖拽，松开释放；触控模式点哪跳哪 | 指针模式必须有可见光标；按下即拖拽只适合指针模式 |
| RustDesk | Touchpad / Mouse mode | 手机端提供屏幕触控板和鼠标模式，另有软件键盘 | 模式切换要随手可用，不能藏在多层设置里 |
| Parsec | 以鼠标模式为主 | 强调主机与客户端的分辨率、DPI、系统鼠标灵敏度和“提高指针精确度”必须一致；提供 Exclusive Input Mode 避免本机鼠标抢占 | 相对模式要避免两端 OS 各做一次加速度；DPI/缩放要参与坐标换算 |

参考链接：

- [Microsoft：Remote Desktop 触摸与鼠标指针模式的手势表](https://techcommunity.microsoft.com/blog/microsoft-security-blog/gesture-list-for-the-touch-and-mouse-pointer-input-modes-for-the-remote-desktop-/248596)
- [Microsoft Q&A：Direct touch 与 Mouse pointer 两种模式](https://learn.microsoft.com/en-us/answers/questions/2241619/remote-desktop-(win-11pro)-with-windows-app-mobile)
- [AnyDesk：Android 触控板模式与直接鼠标模式](https://support.anydesk.com/knowledgebase/articles/1135162-android-usage-guide)
- [AnyDesk 博客：手机屏幕就是触控板](https://blog.anydesk.com/anydesk-remote-access-for-android)
- [Chrome Remote Desktop：Trackpad 与 Touch 输入模式](https://support.google.com/chrome/answer/1649523?co=GENIE.Platform%3DiOS&hl=en)
- [TeamViewer：Android 的 Touch Interactions 与 Mouse Interactions](https://teamviewer.com/en/global/support/knowledge-base/teamviewer-classic/mobile/android/touch-interactions-vs-mouse-interactions-on-android)
- [TeamViewer：iOS 的 Touch mode 与 Mouse mode](https://teamviewer.com/en/global/support/knowledge-base/teamviewer-classic/mobile/ios/interaction-methods-on-ios)
- [ToDesk 触控板/直触模式（第三方评测，含官方功能描述）](https://developer.volcengine.com/articles/7652286704782409755)
- [向日葵：鼠标模式与触摸模式](https://sunlogin.oray.com/news/61833.html)
- [向日葵 HarmonyOS 帮助：触控模式与指针模式](https://service.oray.com/question/36596.html)
- [RustDesk：Android/iOS 控制端的 touchpad 与 mouse mode](https://rustdesk.com/blog/rustdesk-remote-control-android-ios)
- [Parsec：鼠标速度/DPI/指针精确度一致性](https://support.parsec.app/hc/en-us/articles/32361352556180-Mouse-Is-Moving-Too-Fast-or-Slow-In-Some-Scenarios)
- [Parsec：Exclusive Input Mode](https://support.parsec.app/hc/en-us/articles/32381827815188-Mouse-and-Keyboard-Isn-t-Working-Correctly-When-Connected)

## 3. 精细化的六个关键机制

### 3.1 模式分离

“点哪跳哪”和“滑动移动”不能共用同一套单指手势。建议：

- **直触模式**：保留当前行为（轻点绝对点击、单指拖动按住左键拖拽），适合触屏式操作。
- **鼠标模式**：画面右下角显示一层约 28% 透明度的触控板；面板内单指滑动只发相对位移，
  不按左键；轻点在当前光标位置点击；双击进入拖拽锁定；长按右键；双指滚动。面板外仍按
  触控方式操作画面，右侧空白不再承担鼠标控制。

模式开关放在远程桌面底部的控制条，一键切换并记住上次选择，不要放进三级设置页。

### 3.2 指针增益曲线

固定倍率无法同时满足“小目标精细”和“大屏跨屏快”。行业常见做法是：

```text
gain(speed) = base × (fine + (coarse - fine) × smoothstep(speed / v_max))
```

- `speed`：手指在屏幕上的速度（view px / ms），用最近 2–3 个采样做低通，避免抖动放大。
- 慢速（< 0.2 px/ms）：`fine ≈ 0.55–0.75`，用于 1–2 px 的微调。
- 快速（> 1.5 px/ms）：`coarse ≈ 1.8–2.5`，用于跨屏。
- 中间用 smoothstep 或三次曲线，不能有拐点。
- 提供 0.5×–3.0× 的灵敏度设置，默认 1.0×（相对当前 1.8 的固定值应重新标定）。

不要同时叠加 Windows 的“提高指针精确度”：host 注入的是绝对坐标，OS 不会再加一次加速度；
但如果未来支持物理鼠标的相对模式，必须明确由哪一端做加速度。

### 3.3 子像素累积与高采样率

- 指针位置用 float/double 保存，不要把每次 delta 四舍五入成整数像素；只在线路编码时量化到
  16 位归一化坐标（`aa.pointer` 已经是 16 位）。
- 采样率建议 120–250 Hz；当前 `RemoteInputThrottle` 上限 120 events/s，对 60 Hz 屏幕够用，
  但对精细拖拽偏低。可在不增加带宽的前提下把“移动”与“按键”分开限流：按键永不丢，
  移动允许合并，但抬指前必须 flush 最后位置（当前 `flushCoalescedMove()` 已做）。
- 不要做过多的平滑/惯性；平滑会引入“手指停了光标还在走”的错觉。若要预测，只做 1 帧的
  速度外推，并在收到真实位置后立即校正。

### 3.4 按钮语义

建议统一为下表（鼠标模式）：

| 手势 | 行为 |
| --- | --- |
| 单指轻点 | 在当前光标位置左键单击 |
| 单指滑动 | 只移动光标，不按键 |
| 双击后按住 | 按住左键拖动（或沿用现有拖拽锁定） |
| 长按 500 ms | 右键单击 |
| 双指轻点 | 右键单击 |
| 三指轻点 | 中键单击 |
| 双指上下滑 | 滚轮 |
| 双指捏合 | 本地缩放（不改变远端分辨率） |

直触模式保留“单指拖动 = 左键拖拽”，因为这是触屏用户的直觉。

### 3.5 光标反馈

- 鼠标模式的触控板内叠加一个半透明光标，位置为远端归一化坐标 × 面板尺寸；它是一张
  位置指示图，不是第二个画面光标。画面本身不再叠加光标，避免和远端光标重影。
- 直触模式不画本地光标，但点击时给出短促的按下反馈。
- 当前 host 的 WGC 采集使用 `CursorCaptureSettings::WithCursor`，远端光标已经在画面里；
  如果以后把指示环移回画面，必须做“同位置去重”，否则会出现两个光标。

### 3.6 坐标与 DPI

- 触摸坐标必须经过“AspectFit + 本地 zoom/pan”的逆变换后再归一化；当前
  `RemoteViewport`/`normalizeTouch` 已覆盖，但需要补更多组合测试（缩放 + 平移 + 旋转 +
  多显示器）。
- 传入 `client.viewport.dpr`，host 用“物理像素 → 归一化”而不是 dp；否则不同手机密度下
  同样的手指位移会产生不同速度。
- 多显示器时 `aa.pointer` 坐标是相对当前采集显示器的归一化值，host 再映射到虚拟桌面绝对坐标；
  跨屏边缘要允许“推到边界后继续移动切换到下一块屏”（P2）。

## 4. 当前实现的差距（P0 前基线）

> 下表记录 P0 之前的差距；标 ✅ 的项已在 P0 修复，Direct 模式仍保留原来的固定倍率
> 和“一滑即拖”行为，这是有意为之的兼容选择。

| 现状 | 问题 | 影响 |
| --- | --- | --- |
| ✅ `RemoteInputDispatcher.DEFAULT_POINTER_SENSITIVITY = 1.8f` 固定倍率 | Direct 模式没有速度曲线；Mouse 模式已用 `RemotePointerGain` | Direct 模式小目标仍容易过冲，长距离又慢 |
| ✅ `RemoteGestureStateMachine.singleMove()` 超过 16 px 就 `PointerDown(Left)` | Direct 模式单指滑动等于按住左键；Mouse 模式已改成纯移动 | Direct 模式想移动光标时会选中文本/拖动窗口；Mouse 模式没有这个问题 |
| ✅ 轻点用绝对坐标 `clickAt()` | Mouse 模式已改为 `clickHere()` | 触控板模式不会再把光标跳走 |
| ✅ 没有本地光标叠加 | Mouse 模式已在触控板内加半透明圆环 | 圆环映射远端归一化坐标，和远端光标可能有少量位置差 |
| 120 events/s 全局限流 | 移动和按键共用预算 | 快速拖拽时按键事件可能被挤掉（P1） |
| 16 px tap slop | 对不同密度设备是固定值 | 高密度屏上微调容易被判成拖动（P1） |

## 5. 建议的产品形态

控制条上加一个两段式开关：

```text
[ 触控 ]  [ 鼠标 ]
```

- 默认：当前版本默认「触控」，保持点哪跳哪的触屏直觉；需要精确定位、选文字或点小按钮时
  一键切「鼠标」。后续可按设备形态和最后一次选择记忆默认值。
- 鼠标模式在右下角显示约 28% 透明度的触控板，不改变画面本身；画面不再被当作鼠标区域。
- 鼠标模式显示一个小的“精准”提示（或光标套圈），让用户知道当前不是直触。
- 设置页提供“指针速度”滑杆（0.5×–3.0×）和“恢复默认”。
- 外接蓝牙鼠标时自动切到“物理鼠标”模式：直接捕获 `ACTION_MOUSE` 的相对位移和按键，
  不再经过触摸手势。

## 6. 落地计划

### P0（已于 2026-11-15 实施）

1. ✅ **模式枚举与手势分流**：`RemotePointerMode.Direct / Mouse`；`RemoteGestureStateMachine`
   增加 `setPointerMode()`。鼠标模式下 `singleMove()` 只发 `RemotePointer`，不按左键；
   `up()` 发“在当前光标点击”而不是绝对坐标点击。该 Mouse 手势现在由画面右下角的独立
   触控板使用；画面本身保持直触模式。
2. ✅ **指针增益曲线**：`RemotePointerGain` 纯函数 + 单测；慢速 0.8、快速 2.2，整体
   倍率 1.25（约 1.0×–2.75×）。慢速达到 1:1 跟手，快速保留跨屏能力；鼠标模式的
   tap slop 为 4 px，但移动从第一个像素就开始发送，不再有起步死区；暂未做 UI
   滑杆，常量集中便于继续调参。
3. ✅ **本地光标叠加与圆环拖拽**：鼠标模式在右下角触控板内画一个半透明圆环（半径
   9 dp，按下 10.5 dp），位置为 `dispatcher.cursorX/Y × 面板宽高`，直接指示实际鼠标
   在远端屏幕里的相对位置；画面本身不叠加第二个光标。按住圆环不松开时圆环变成浅蓝色
   并按下左键，拖动即拖拽远端窗口或选中文字，抬起后恢复并释放左键。触控板为拇指大小
   的方形面板，约 28% 黑底，不遮挡画面主体。
4. ✅ **按钮语义与滚动**：鼠标模式支持“轻点当前光标点击”“按住圆环拖拽”“长按当前
   光标右键”“双指轻点右键”；触控板右侧隔着 8 dp 间隙是独立滚轮轨道，上下拖动发送
   `wheel` 事件，双指上下滑同样滚动；两个区域各有 pointerInput，不再重叠；直触模式
   不变。控制条和修饰键条收进左侧可折叠抽屉，抽屉入口是一个小的半透明圆形图标按钮
   （白色图标加细边框，不跟随主题色，深色或浅色画面上都看得清，占用画面很少；首次出现
   会轻微闪动提示位置），底部画面完整露出。
5. ✅ **跟手与测试**：鼠标模式取消 4 px 起步死区，回放 Compose 同一帧内的历史采样，
   指针增益改为慢速 1.0×、快速 2.75×；Android 端将指针帧送进 conflated 通道，只发布
   最新一帧，屏幕主机把输入轮询周期从 200 ms 缩短到 8 ms。`RemoteGestureStateMachineTest`
   覆盖鼠标模式移动不按键、圆环按住/释放、长按右键、切换模式释放按键；
   `RemoteInputDispatcherTest` 覆盖当前光标点击和按键帧；`RemotePointerGainTest` 覆盖
   曲线单调性和边界。2026-11-15 在 API 37 模拟器上实际运行 `RemoteDesktopScreenTest`，
   其中 `mouseTrackpadConsumesTheGestureAndMovesTheRemotePointer` 验证触控板产生
   `aa.pointer` 帧且不产生 `aa.input`（未落入画面直触路径），新增用例覆盖圆环拖拽
   按下/释放、独立滚轮轨道、间隙不穿透、双击拖拽锁保持和侧边抽屉开合。完整 12 个
   用例的结果见当次测试记录。

验收：在 1080p/1440p 桌面上，用鼠标模式能一次点中 16×16 px 的关闭按钮；选中文本时不会
误拖窗口；从屏幕左侧移到右侧不超过两次滑动。

### P1（1 周）

- 设置页指针速度滑杆 + 每设备记忆；模式开关持久化。
- 预测光标 + RTT 自适应；指针采样提升到 240 Hz，按键与移动分桶限流。
- 蓝牙/USB 鼠标的 `ACTION_MOUSE` 相对事件捕获，隐藏系统指针，支持右键/中键/滚轮。
- 触摸 slop 按密度换算（`16.dp` 而不是 16 px），并增加“微调模式”（长按后 0.3× 增益）。

### P2（后续）

- 多显示器边缘切换、光标跨屏。
- 手势自定义（右键/中键/滚动映射）。
- 手柄/触控笔压感映射（可选）。
- 与画面编码联动：快速拖动时临时提高帧率，停下后恢复清晰度优先。

## 7. 风险与取舍

- **模式太多会增加学习成本**：只保留两个模式，开关放在控制条，默认值按设备形态选。
- **预测光标可能“超调”**：只做短时预测，收到真实帧立即校正；RTT 低时不预测。
- **本地光标与远端光标重影**：鼠标模式才显示，且用半透明/环形，不做实心大光标。
- **不要用系统级鼠标加速**：host 注入绝对坐标，Windows 的“提高指针精确度”不会生效；
  如果未来做相对注入，必须显式关闭一端加速，避免双倍加速。

## 8. 验收指标

| 指标 | 目标 |
| --- | --- |
| 16×16 px 目标一次点中率 | ≥ 90%（1080p，RTT < 80 ms） |
| 文本选择误拖窗口 | 0 次 / 20 次尝试 |
| 从屏幕左到右的滑动次数 | ≤ 2 次（6.7 英寸手机，1440p） |
| 指针停手后的视觉收敛 | ≤ 100 ms |
| 拖拽释放后 host 按键残留 | 0（由 host `controlGrant` 释放兜底） |

---

相关文档：[远程桌面架构](remote-desktop.md)、[清晰度与码率调研](remote-desktop-clarity-research.md)、
[远程桌面决策记录](remote-desktop-decisions.md)。

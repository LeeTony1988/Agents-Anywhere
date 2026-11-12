# `aa.pointer` 二进制指针帧（远程桌面 1.0）

高频指针移动不进入 `input-event.schema.json`，而是走 LiveKit lossy / unordered 数据通道
`aa.pointer` 上的定长 **12 字节**二进制帧。字节序统一为**小端**（little-endian），无头部、无填充、
无校验和。

## 1. 布局

| offset | size | field | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| 0 | 1 | `type` | `u8` | 固定 `0x01`（pointer-move）。其他值接收方必须忽略整帧。 |
| 1 | 1 | `flags` | `u8` | bit0：按键状态变化标志；bit1..7 保留，发送方必须置 0，接收方必须忽略。 |
| 2 | 2 | `buttons` | `u16` | 按键位掩码：`0x0001` left、`0x0002` right、`0x0004` middle；其余位保留为 0。 |
| 4 | 2 | `x` | `u16` | 归一化横坐标，`0..65535`，相对当前 `d` 指定的单块显示器。 |
| 6 | 2 | `y` | `u16` | 归一化纵坐标，`0..65535`，相对当前 `d` 指定的单块显示器。 |
| 8 | 4 | `seq` | `u32` | 发送序号，`0..4294967295`，每帧加 1，`0xFFFFFFFF` 之后回绕到 `0`。 |

总长固定 12 字节。`buttons` 表示**当前按住的按键集合**（状态），不是本帧的增量；
`flags` bit0 只在 `buttons` 相对上一帧发生变化的帧上置 1。

## 2. 坐标归一化

- 客户端把单块显示器内的像素坐标映射到 `[0,1]` 后，按 `round(value * 65535)` 转成整数。
- `0` 对应显示器最左/最上边界，`65535` 对应最右/最下边界。
- 小于 0 的值 **clamp 到 0**；大于 65535 的值 **clamp 到 65535**。NaN、无穷大属于非法输入，
  发送方必须丢弃，不得写入帧。
- 接收方按 `x / 65535`、`y / 65535` 还原为 `[0,1]`，再乘以目标显示器像素尺寸。
- 显示器序号 `d` 不在二进制帧里，由 `input-event` 的 pointer 事件或会话的 `displayId` 决定；
  切换显示器必须先发一个 JSON pointer 事件，再继续发 `aa.pointer`。

## 3. seq 规则

- `seq` 是无符号 32 位，按模 `2^32` 递增；`0xFFFFFFFF` 的下一帧是 `0`，这是正常回绕，不是错误。
- 接收方用模运算判断新旧：`(int32)(new - old) > 0` 表示更新；不要用普通有符号比较。
- 允许丢帧（lossy/unordered）。收到明显早于当前 `seq` 的帧时丢弃；回绕窗口内的旧帧同样按
  模差值判断。重复帧可以安全重放，指针状态是幂等的。

## 4. 十六进制测试向量

以下每个向量都是完整的 12 字节帧，按 offset 0 → 11 排列。

### 4.1 正常移动（无按键）

输入：`x=0x8000 (32768)`、`y=0x4000 (16384)`、`buttons=0`、`flags=0`、`seq=1`。

```text
01 00 00 00 00 80 00 40 01 00 00 00
```

解码：`type=0x01`、`flags=0x00`、`buttons=0x0000`、`x=0x8000`、`y=0x4000`、`seq=1`。

### 4.2 seq 回绕（0xFFFFFFFF → 0）

输入：上一帧 `seq=0xFFFFFFFF`，本帧 `seq=0`、`flags=1`（按键状态变化）、`buttons=0x0001`
（left 按下）、`x=0xFFFF`、`y=0xFFFF`。上一帧的 seq 字节为 `FF FF FF FF`，本帧为
`00 00 00 00`。

```text
01 01 01 00 FF FF FF FF 00 00 00 00
```

解码：`type=0x01`、`flags=0x01`、`buttons=0x0001`、`x=0xFFFF`、`y=0xFFFF`、`seq=0`。
接收方必须把 `0xFFFFFFFF → 0` 视为连续递增，不能丢弃。

### 4.3 x/y 越界 clamp

输入：原始 `x=70000`（大于 65535）、原始 `y=-12`（小于 0）、`buttons=0`、`flags=0`、`seq=42`。
clamp 后 `x=0xFFFF`、`y=0x0000`。

```text
01 00 00 00 FF FF 00 00 2A 00 00 00
```

解码：`type=0x01`、`flags=0x00`、`buttons=0x0000`、`x=0xFFFF`、`y=0x0000`、`seq=42`。

### 4.4 buttons 组合（left + right + middle）

输入：`buttons=0x0007`（三个键同时按住）、`flags=1`（本帧按键状态变化）、`x=0`、
`y=0x7FFF`、`seq=0xDEADBEEF`。

```text
01 01 07 00 00 00 FF 7F EF BE AD DE
```

解码：`type=0x01`、`flags=0x01`、`buttons=0x0007`、`x=0x0000`、`y=0x7FFF`、
`seq=0xDEADBEEF`。

### 4.5 按键释放（buttons 归零）

输入：上一帧 `buttons=0x0007`，本帧 `buttons=0x0000`、`flags=1`、`x=0x1234`、`y=0x5678`、
`seq=43`。

```text
01 01 00 00 34 12 78 56 2B 00 00 00
```

解码：`type=0x01`、`flags=0x01`、`buttons=0x0000`、`x=0x1234`、`y=0x5678`、`seq=43`。

## 5. 伪代码

```text
encode(x, y, buttons, flags, seq):
    x = clamp(round(x), 0, 65535)
    y = clamp(round(y), 0, 65535)
    return u8(0x01)
        || u8(flags & 0x01)
        || u16le(buttons & 0x0007)
        || u16le(x)
        || u16le(y)
        || u32le(seq mod 2^32)

decode(frame):
    require length(frame) == 12 and frame[0] == 0x01
    flags   = frame[1] & 0x01
    buttons = u16le(frame[2..3]) & 0x0007
    x       = u16le(frame[4..5])
    y       = u16le(frame[6..7])
    seq     = u32le(frame[8..11])
```

客户端每秒最多发送 120 个 pointer 帧（见 `docs/remote-desktop.md` §7.6）；主机只应用事件循环
之间最新的一帧坐标，但必须按顺序处理 `buttons` 变化，避免丢失按下/释放。

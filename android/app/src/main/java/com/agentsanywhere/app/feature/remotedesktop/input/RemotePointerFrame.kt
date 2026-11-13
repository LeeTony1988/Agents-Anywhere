package com.agentsanywhere.app.feature.remotedesktop.input

/**
 * High-rate pointer motion travels outside `input-event.schema.json`: the `aa.pointer`
 * LiveKit data channel carries this fixed 12-byte little-endian frame, with no header,
 * no padding and no checksum. Wire facts are
 * `contracts/remote-desktop/1.0/pointer-frame.md`.
 *
 * Frame layout, offsets 0..11:
 * `type u8 = 0x01`, `flags u8`, `buttons u16`, `x u16`, `y u16`, `seq u32`.
 */
const val REMOTE_POINTER_FRAME_SIZE: Int = 12

/** Only `pointer-move` exists; a receiver must ignore a frame with any other type. */
const val REMOTE_POINTER_FRAME_TYPE: Int = 0x01

/** bit0: [RemotePointerFrame.buttons] differs from the previous frame. bit1..7 stay 0. */
const val REMOTE_POINTER_FLAG_BUTTONS_CHANGED: Int = 0x01

const val REMOTE_POINTER_BUTTON_LEFT: Int = 0x0001
const val REMOTE_POINTER_BUTTON_RIGHT: Int = 0x0002
const val REMOTE_POINTER_BUTTON_MIDDLE: Int = 0x0004

/** Reserved button bits; a sender must keep them 0 and a receiver must ignore them. */
private const val BUTTONS_MASK = 0x0007
private const val COORDINATE_MAX = 0xFFFF
private const val U32_MASK = 0xFFFFFFFFL

/**
 * One decoded pointer frame. [x] and [y] are normalized `0..65535` inside the display
 * chosen by the session, [seq] is the unsigned 32-bit send counter (it wraps to 0 after
 * `0xFFFFFFFF`; compare two values with modular arithmetic, not `>`).
 */
data class RemotePointerFrame(
    val x: Int,
    val y: Int,
    val buttons: Int = 0,
    val flags: Int = 0,
    val seq: Long = 0L,
) {
    fun encode(): ByteArray = encodePointerFrame(x = x, y = y, buttons = buttons, flags = flags, seq = seq)
}

/**
 * Encodes one `aa.pointer` frame.
 *
 * [x] and [y] are clamped to `0..65535`, [buttons] keeps only the defined
 * left/right/middle bits, [flags] keeps only bit0 and [seq] is taken modulo `2^32`,
 * so callers can pass a monotonically growing counter without pre-masking it.
 */
fun encodePointerFrame(x: Int, y: Int, buttons: Int, flags: Int, seq: Long): ByteArray {
    val frame = ByteArray(REMOTE_POINTER_FRAME_SIZE)
    frame[0] = REMOTE_POINTER_FRAME_TYPE.toByte()
    frame[1] = (flags and REMOTE_POINTER_FLAG_BUTTONS_CHANGED).toByte()
    writeUInt16Le(frame, 2, buttons and BUTTONS_MASK)
    writeUInt16Le(frame, 4, x.coerceIn(0, COORDINATE_MAX))
    writeUInt16Le(frame, 6, y.coerceIn(0, COORDINATE_MAX))
    writeUInt32Le(frame, 8, seq and U32_MASK)
    return frame
}

/** Decodes a frame sent by a peer; null when the length or [REMOTE_POINTER_FRAME_TYPE] is wrong. */
fun decodePointerFrame(frame: ByteArray): RemotePointerFrame? {
    if (frame.size != REMOTE_POINTER_FRAME_SIZE) return null
    if (frame[0].toInt() and 0xFF != REMOTE_POINTER_FRAME_TYPE) return null
    return RemotePointerFrame(
        x = readUInt16Le(frame, 4),
        y = readUInt16Le(frame, 6),
        buttons = readUInt16Le(frame, 2) and BUTTONS_MASK,
        flags = frame[1].toInt() and REMOTE_POINTER_FLAG_BUTTONS_CHANGED,
        seq = readUInt32Le(frame, 8),
    )
}

/** Bytes of one frame as lowercase hex pairs, for diagnostics and test vectors. */
fun RemotePointerFrame.hexBytes(): String = encode().joinToString(" ") { byte ->
    (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
}

/** Modular "newer than" test for the wrapping `seq` counter: `(int32)(candidate - current) > 0`. */
fun isNewerPointerSequence(candidate: Long, current: Long): Boolean {
    return ((candidate - current) and U32_MASK).toInt() > 0
}

private fun writeUInt16Le(frame: ByteArray, offset: Int, value: Int) {
    frame[offset] = (value and 0xFF).toByte()
    frame[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}

private fun writeUInt32Le(frame: ByteArray, offset: Int, value: Long) {
    frame[offset] = (value and 0xFF).toByte()
    frame[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    frame[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    frame[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}

private fun readUInt16Le(frame: ByteArray, offset: Int): Int {
    val low = frame[offset].toInt() and 0xFF
    val high = frame[offset + 1].toInt() and 0xFF
    return low or (high shl 8)
}

private fun readUInt32Le(frame: ByteArray, offset: Int): Long {
    return frame[offset].toLong() and 0xFF or
        ((frame[offset + 1].toLong() and 0xFF) shl 8) or
        ((frame[offset + 2].toLong() and 0xFF) shl 16) or
        ((frame[offset + 3].toLong() and 0xFF) shl 24)
}

import assert from 'node:assert/strict'
import test from 'node:test'

import {
  InputEventEncoder,
  InputThrottle,
  encodeKeyEvent,
  encodePingEvent,
  encodePointerEvent,
  encodeTextEvent,
  keyEventCode,
  modsFromEvent,
  normalizePoint,
  pointerFrame,
} from '../src/lib/remote-desktop/input.ts'

const hex = (bytes) => Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join(' ')

// ─── pointerFrame: the five contract vectors from pointer-frame.md §4 ──────

test('pointerFrame: 4.1 normal move without buttons', () => {
  const frame = pointerFrame(0x8000, 0x4000, 0x0000, 0x0000, 1)
  assert.equal(frame.byteLength, 12)
  assert.equal(hex(frame), '01 00 00 00 00 80 00 40 01 00 00 00')
})

test('pointerFrame: 4.2 seq wrap 0xFFFFFFFF -> 0 with button press', () => {
  const frame = pointerFrame(0xffff, 0xffff, 0x0001, 0x01, 0)
  assert.equal(hex(frame), '01 01 01 00 ff ff ff ff 00 00 00 00')
})

test('pointerFrame: 4.3 out-of-range x/y are clamped', () => {
  const frame = pointerFrame(70000, -12, 0x0000, 0x0000, 42)
  assert.equal(hex(frame), '01 00 00 00 ff ff 00 00 2a 00 00 00')
})

test('pointerFrame: 4.4 combined buttons left+right+middle', () => {
  const frame = pointerFrame(0, 0x7fff, 0x0007, 0x01, 0xdeadbeef)
  assert.equal(hex(frame), '01 01 07 00 00 00 ff 7f ef be ad de')
})

test('pointerFrame: 4.5 button release returns buttons to zero', () => {
  const frame = pointerFrame(0x1234, 0x5678, 0x0000, 0x01, 43)
  assert.equal(hex(frame), '01 01 00 00 34 12 78 56 2b 00 00 00')
})

test('pointerFrame: non-finite input is rejected, never written into a frame', () => {
  assert.throws(() => pointerFrame(NaN, 0, 0, 0, 0), TypeError)
  assert.throws(() => pointerFrame(0, Infinity, 0, 0, 0), TypeError)
})

test('pointerFrame: seq wraps modulo 2^32', () => {
  const frame = pointerFrame(0, 0, 0, 0, 0x1_0000_0000 + 5)
  const view = new DataView(frame.buffer)
  assert.equal(view.getUint32(8, true), 5)
})

// ─── normalizePoint: scale / offset / clamp / rotation ─────────────────────

const rect = { left: 100, top: 50, width: 400, height: 300 }

test('normalizePoint: maps the content box corners to [0,1]', () => {
  assert.deepEqual(normalizePoint(100, 50, rect), { x: 0, y: 0 })
  assert.deepEqual(normalizePoint(500, 350, rect), { x: 1, y: 1 })
  assert.deepEqual(normalizePoint(300, 200, rect), { x: 0.5, y: 0.5 })
})

test('normalizePoint: inverts zoom (scale) around the content origin', () => {
  // scale=2 doubles the drawn content: a client point at twice the offset
  // maps back to half of the content coordinate.
  const view = { scale: 2, offsetX: 0, offsetY: 0 }
  assert.deepEqual(normalizePoint(100 + 400, 50 + 300, rect, view), { x: 0.5, y: 0.5 })
  assert.deepEqual(normalizePoint(100 + 800, 50 + 600, rect, view), { x: 1, y: 1 })
})

test('normalizePoint: inverts pan (offset) before scaling', () => {
  const view = { scale: 1, offsetX: 50, offsetY: -30 }
  assert.deepEqual(normalizePoint(150, 20, rect, view), { x: 0, y: 0 })
  const zoomed = { scale: 2, offsetX: 50, offsetY: -30 }
  assert.deepEqual(normalizePoint(100 + 50 + 400, 50 - 30 + 300, rect, zoomed), { x: 0.5, y: 0.5 })
})

test('normalizePoint: clamps points outside the content box', () => {
  assert.deepEqual(normalizePoint(0, 0, rect), { x: 0, y: 0 })
  assert.deepEqual(normalizePoint(1000, 900, rect), { x: 1, y: 1 })
})

test('normalizePoint: rotation is unsupported in v1 and throws', () => {
  // v1 contract only covers unrotated displays (noted in the task spec).
  assert.throws(
    () => normalizePoint(300, 200, rect, { scale: 1, offsetX: 0, offsetY: 0, rotation: 90 }),
    /rotation/,
  )
  // rotation 0 / omitted is accepted.
  assert.deepEqual(normalizePoint(300, 200, rect, { rotation: 0 }), { x: 0.5, y: 0.5 })
})

test('normalizePoint: rejects degenerate rects and non-positive scale', () => {
  assert.throws(() => normalizePoint(0, 0, { left: 0, top: 0, width: 0, height: 10 }), RangeError)
  assert.throws(() => normalizePoint(0, 0, rect, { scale: 0 }), RangeError)
})

// ─── Keyboard helpers ──────────────────────────────────────────────────────

test('keyEventCode accepts W3C codes and rejects out-of-pattern values', () => {
  assert.equal(keyEventCode('KeyA'), 'KeyA')
  assert.equal(keyEventCode('ControlLeft'), 'ControlLeft')
  assert.equal(keyEventCode('NumpadEnter'), 'NumpadEnter')
  assert.equal(keyEventCode(''), null)
  assert.equal(keyEventCode('Bracket Left'), null)
  assert.equal(keyEventCode('a'.repeat(25)), null)
})

test('modsFromEvent maps modifier state to the wire order', () => {
  assert.deepEqual(modsFromEvent({}), [])
  assert.deepEqual(modsFromEvent({ ctrlKey: true, metaKey: true }), ['ctrl', 'meta'])
  assert.deepEqual(
    modsFromEvent({ shiftKey: true, altKey: true, ctrlKey: true, metaKey: true }),
    ['ctrl', 'alt', 'shift', 'meta'],
  )
})

// ─── InputThrottle: move merging, button ordering, rate cap ────────────────

const decodeFrame = (frame) => {
  const view = new DataView(frame.buffer, frame.byteOffset, frame.byteLength)
  return {
    type: view.getUint8(0),
    flags: view.getUint8(1),
    buttons: view.getUint16(2, true),
    x: view.getUint16(4, true),
    y: view.getUint16(6, true),
    seq: view.getUint32(8, true),
  }
}

test('InputThrottle merges pointer moves down to the newest coordinate', () => {
  let now = 0
  const throttle = new InputThrottle({ now: () => now })
  throttle.pushPointer(10, 10, 0)
  throttle.pushPointer(20, 20, 0)
  throttle.pushPointer(30, 30, 0)
  assert.equal(throttle.pending, 1)
  const frames = throttle.drain()
  assert.equal(frames.length, 1)
  assert.deepEqual(decodeFrame(frames[0]), { type: 1, flags: 0, buttons: 0, x: 30, y: 30, seq: 0 })
})

test('InputThrottle never merges button changes away', () => {
  const throttle = new InputThrottle({ now: () => 0 })
  throttle.pushPointer(1, 1, 0x0001) // press
  throttle.pushPointer(2, 2, 0x0001) // move while held
  throttle.pushPointer(3, 3, 0x0001) // merges with the previous move
  throttle.pushPointer(4, 4, 0x0000) // release
  throttle.pushPointer(5, 5, 0x0000) // move after release
  throttle.pushPointer(6, 6, 0x0000) // merges with the previous move
  const frames = throttle.flush().map(decodeFrame)
  assert.deepEqual(frames.map((frame) => [frame.x, frame.flags, frame.buttons]), [
    [1, 1, 0x0001],
    [3, 0, 0x0001],
    [4, 1, 0x0000],
    [6, 0, 0x0000],
  ])
  // seq increments by one per emitted frame.
  assert.deepEqual(frames.map((frame) => frame.seq), [0, 1, 2, 3])
})

test('InputThrottle caps emission at maxPerSecond per window', () => {
  let now = 0
  const throttle = new InputThrottle({ maxPerSecond: 2, now: () => now })
  throttle.pushPointer(1, 1, 0x0001)
  throttle.pushPointer(2, 2, 0x0000)
  throttle.pushPointer(3, 3, 0x0001)
  throttle.pushPointer(4, 4, 0x0000)
  assert.equal(throttle.drain().length, 2)
  assert.equal(throttle.drain().length, 0) // budget exhausted in the same window
  now += 1000
  assert.equal(throttle.drain().length, 2)
})

test('InputThrottle assigns its own u32 seq independent of JSON events', () => {
  const throttle = new InputThrottle({ now: () => 0 })
  for (let index = 0; index < 3; index += 1) {
    throttle.pushPointer(index, index, 0x0001)
    throttle.pushPointer(index, index, 0x0000)
    throttle.flush()
  }
  assert.equal(throttle.nextSeq, 6)
})

// ─── aa.input JSON encoders: v1 + auto-incrementing seq ────────────────────

test('encodePointerEvent: move omits b, down/up require it', () => {
  assert.deepEqual(encodePointerEvent(0, { a: 'move', x: 0.25, y: 0.75 }), {
    v: 1, seq: 0, t: 'pointer', a: 'move', x: 0.25, y: 0.75,
  })
  assert.deepEqual(encodePointerEvent(1, { a: 'down', x: 1, y: 0, b: 'right', d: 2 }), {
    v: 1, seq: 1, t: 'pointer', a: 'down', x: 1, y: 0, b: 'right', d: 2,
  })
  // Out-of-range coordinates clamp to [0,1].
  assert.equal(encodePointerEvent(2, { a: 'move', x: 4, y: -1 }).x, 1)
  assert.equal(encodePointerEvent(3, { a: 'move', x: 4, y: -1 }).y, 0)
})

test('encodeKeyEvent carries the W3C code and merged modifiers', () => {
  assert.deepEqual(encodeKeyEvent(7, { a: 'up', code: 'Escape', mods: [], repeat: false }), {
    v: 1, seq: 7, t: 'key', a: 'up', code: 'Escape',
  })
  assert.deepEqual(encodeKeyEvent(8, { a: 'down', code: 'KeyS', mods: ['ctrl'], repeat: true }), {
    v: 1, seq: 8, t: 'key', a: 'down', code: 'KeyS', mods: ['ctrl'], repeat: true,
  })
  assert.throws(() => encodeKeyEvent(9, { a: 'down', code: 'not a code' }), TypeError)
})

test('encodeTextEvent enforces the 4096-character single-event cap', () => {
  const ok = encodeTextEvent(0, { s: 'a'.repeat(4096) })
  assert.equal(ok.s.length, 4096)
  assert.throws(() => encodeTextEvent(1, { s: 'a'.repeat(4097) }), RangeError)
})

test('encodePingEvent carries no seq (the ping branch forbids it)', () => {
  assert.deepEqual(encodePingEvent(1762944000123), { v: 1, t: 'ping', at: 1762944000123 })
})

test('InputEventEncoder auto-increments seq and wraps at 2^32', () => {
  const encoder = new InputEventEncoder(0xffffffff)
  const first = encoder.encodePointerEvent({ a: 'down', x: 0, y: 0, b: 'left' })
  const second = encoder.encodeTextEvent({ s: 'hi' })
  const third = encoder.encodeKeyEvent({ a: 'down', code: 'KeyA' })
  assert.equal(first.seq, 0xffffffff)
  assert.equal(second.seq, 0)
  assert.equal(third.seq, 1)
  assert.equal(encoder.nextSeq, 2)
  // ping does not consume a seq slot.
  const ping = encoder.encodePing(42)
  assert.equal(ping.t, 'ping')
  assert.equal(encoder.nextSeq, 2)
})

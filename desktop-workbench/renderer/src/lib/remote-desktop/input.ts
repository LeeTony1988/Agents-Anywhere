/**
 * Remote-desktop input helpers (contract 1.0). Everything here is a pure
 * function or a deterministic class with injectable time — no DOM, no network,
 * no timers — so `web-next/test/remote-desktop-input.test.mjs` can run it under
 * plain `node --test`.
 *
 * Wire references:
 * - `contracts/remote-desktop/1.0/pointer-frame.md` — 12-byte `aa.pointer` frame.
 * - `contracts/remote-desktop/1.0/schemas/input-event.schema.json` — `aa.input`
 *   JSON events (branches are `additionalProperties: false`).
 * - `docs/remote-desktop-decisions.md` §2.3 — the JSON `seq` and the binary
 *   frame `seq` are two independent u32 counters, both wrapping to 0.
 */

import type { RemoteDesktopInputEvent } from "@/generated/remote-desktop/v1_0/input-event";

// ─── Shared types ───────────────────────────────────────────────────────────

export type PointerButton = "left" | "right" | "middle";
export type PointerAction = "move" | "down" | "up";
export type InputMod = "ctrl" | "alt" | "shift" | "meta";

/** Bounding rect of the element that renders the remote screen. */
export type ElementRect = {
  left: number;
  top: number;
  width: number;
  height: number;
};

/**
 * How the remote screen is drawn inside the element. The content is laid out
 * with `translate(offsetX, offsetY) scale(scale)` in element pixels; input
 * coordinates invert that transform. `rotation` is reserved: v1 does not
 * support rotated displays and throws on any non-zero value.
 */
export type ViewTransform = {
  scale: number;
  offsetX: number;
  offsetY: number;
  rotation?: number;
};

// ─── `aa.pointer` binary frame (pointer-frame.md) ───────────────────────────

export const POINTER_FRAME_TYPE = 0x01;
export const POINTER_FRAME_LENGTH = 12;

const BUTTON_LEFT = 0x0001;
const BUTTON_RIGHT = 0x0002;
const BUTTON_MIDDLE = 0x0004;
const BUTTON_MASK = BUTTON_LEFT | BUTTON_RIGHT | BUTTON_MIDDLE;

/** DOM `event.buttons` → wire mask (DOM: left=1, right=2, middle=4 — same bits). */
export function domButtonsToMask(buttons: number): number {
  return buttons & BUTTON_MASK;
}

function clampInt(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, Math.round(value)));
}

function assertFinite(value: number, name: string): void {
  if (!Number.isFinite(value)) {
    throw new TypeError(`${name} must be finite, got ${value}`);
  }
}

/**
 * Encode one 12-byte pointer frame (little-endian):
 * `type u8 | flags u8 | buttons u16 | x u16 | y u16 | seq u32`.
 *
 * `x`/`y` are raw integer coordinates clamped to `0..65535` (pointer-frame.md §4.3);
 * `buttons` keeps its low 3 bits, `flags` keeps bit0. `seq` is taken modulo 2^32.
 * NaN / Infinity are illegal input and throw — the sender must drop such events
 * before writing a frame.
 */
export function pointerFrame(
  x: number,
  y: number,
  buttons: number,
  flags: number,
  seq: number,
): Uint8Array {
  assertFinite(x, "x");
  assertFinite(y, "y");
  assertFinite(buttons, "buttons");
  assertFinite(flags, "flags");
  assertFinite(seq, "seq");
  const frame = new Uint8Array(POINTER_FRAME_LENGTH);
  frame[0] = POINTER_FRAME_TYPE;
  frame[1] = flags & 0x01;
  const wireButtons = buttons & BUTTON_MASK;
  frame[2] = wireButtons & 0xff;
  frame[3] = (wireButtons >>> 8) & 0xff;
  const wireX = clampInt(x, 0, 65535);
  const wireY = clampInt(y, 0, 65535);
  frame[4] = wireX & 0xff;
  frame[5] = (wireX >>> 8) & 0xff;
  frame[6] = wireY & 0xff;
  frame[7] = (wireY >>> 8) & 0xff;
  const wireSeq = seq >>> 0;
  frame[8] = wireSeq & 0xff;
  frame[9] = (wireSeq >>> 8) & 0xff;
  frame[10] = (wireSeq >>> 16) & 0xff;
  frame[11] = (wireSeq >>> 24) & 0xff;
  return frame;
}

// ─── Coordinate normalization ───────────────────────────────────────────────

/**
 * Map a client (viewport) point through the element rect and the inverse of
 * the zoom/pan transform into the remote screen's `[0,1]` space, clamping.
 *
 * `view` describes how the content is drawn: `elem = content * rectSize * scale + offset`,
 * so `content = (client - rect.origin - offset) / (rectSize * scale)`.
 * Rotation is not supported in v1 (rotation is always 0 / omitted).
 */
export function normalizePoint(
  clientX: number,
  clientY: number,
  rect: ElementRect,
  view?: Partial<ViewTransform>,
): { x: number; y: number } {
  assertFinite(clientX, "clientX");
  assertFinite(clientY, "clientY");
  const scale = view?.scale ?? 1;
  const offsetX = view?.offsetX ?? 0;
  const offsetY = view?.offsetY ?? 0;
  assertFinite(scale, "scale");
  assertFinite(offsetX, "offsetX");
  assertFinite(offsetY, "offsetY");
  const rotation = view?.rotation ?? 0;
  if (rotation !== 0) {
    // v1 contract only supports unrotated displays; reject loudly instead of
    // sending subtly wrong coordinates.
    throw new Error("rotation is not supported in remote-desktop 1.0");
  }
  if (!(scale > 0)) throw new RangeError(`scale must be > 0, got ${scale}`);
  if (!(rect.width > 0) || !(rect.height > 0)) {
    throw new RangeError("rect width/height must be > 0");
  }
  const x = (clientX - rect.left - offsetX) / (rect.width * scale);
  const y = (clientY - rect.top - offsetY) / (rect.height * scale);
  return { x: clampUnit(x), y: clampUnit(y) };
}

function clampUnit(value: number): number {
  return Math.min(1, Math.max(0, value));
}

// ─── Keyboard mapping ───────────────────────────────────────────────────────

const KEY_CODE_PATTERN = /^[A-Za-z0-9]{1,24}$/;

/**
 * Validate a W3C `KeyboardEvent.code` for the `key` input event. Returns the
 * code unchanged when it matches the contract pattern, `null` when the event
 * must be dropped (the wire schema rejects anything else).
 */
export function keyEventCode(code: string): string | null {
  return KEY_CODE_PATTERN.test(code) ? code : null;
}

export type ModifierSource = {
  ctrlKey?: boolean;
  altKey?: boolean;
  shiftKey?: boolean;
  metaKey?: boolean;
};

/** `KeyboardEvent` modifier state → the `mods` array of a `key` input event. */
export function modsFromEvent(event: ModifierSource): InputMod[] {
  const mods: InputMod[] = [];
  if (event.ctrlKey) mods.push("ctrl");
  if (event.altKey) mods.push("alt");
  if (event.shiftKey) mods.push("shift");
  if (event.metaKey) mods.push("meta");
  return mods;
}

// ─── Input throttle (`aa.pointer` scheduling) ───────────────────────────────

export type InputThrottleOptions = {
  /** Hard cap on emitted pointer frames per second (contract: 120). */
  maxPerSecond?: number;
  /** Injectable clock for deterministic tests; defaults to `Date.now`. */
  now?: () => number;
};

type QueuedFrame = {
  x: number;
  y: number;
  buttons: number;
  /** bit0 = buttons changed relative to the previously emitted frame. */
  flags: number;
};

/**
 * Schedules `aa.pointer` frames at a maximum rate (120/s per the contract)
 * while merging consecutive pure-move frames: the host applies only the newest
 * coordinate between event-loop turns but must observe every `buttons`
 * transition (pointer-frame.md §5), so frames whose buttons changed are never
 * merged away.
 *
 * The throttle owns its own u32 `seq` counter, independent from the JSON
 * `aa.input` counter (decisions §2.3). It never uses timers — call `drain()`
 * from the consumer's animation frame / interval, and `flush()` before
 * releasing control or unmounting.
 */
export class InputThrottle {
  readonly maxPerSecond: number;
  private readonly now: () => number;
  private queue: QueuedFrame[] = [];
  private lastButtons = 0;
  private seq = 0;
  private windowStart = 0;
  private sentInWindow = 0;

  constructor(options: InputThrottleOptions = {}) {
    this.maxPerSecond = Math.max(1, Math.floor(options.maxPerSecond ?? 120));
    this.now = options.now ?? (() => Date.now());
    this.windowStart = this.now();
  }

  /** Frames waiting to be emitted (moves already merged). */
  get pending(): number {
    return this.queue.length;
  }

  /** Next `seq` that will be written into an emitted frame. */
  get nextSeq(): number {
    return this.seq >>> 0;
  }

  /**
   * Queue a pointer position (already normalized to `0..65535` integers).
   * Pure moves merge with the pending move; any `buttons` change is kept.
   */
  pushPointer(x: number, y: number, buttons: number): void {
    assertFinite(x, "x");
    assertFinite(y, "y");
    assertFinite(buttons, "buttons");
    const wireButtons = buttons & BUTTON_MASK;
    const flags = wireButtons !== this.lastButtons ? 1 : 0;
    const last = this.queue[this.queue.length - 1];
    if (last && last.flags === 0 && flags === 0 && last.buttons === wireButtons) {
      // Merging two move-only frames with identical buttons: keep the newest
      // coordinate, drop the stale one (host applies the latest position).
      last.x = x;
      last.y = y;
      return;
    }
    this.queue.push({ x, y, buttons: wireButtons, flags });
    this.lastButtons = wireButtons;
  }

  /**
   * Emit due frames within the rate budget. Call repeatedly (e.g. per
   * animation frame); returns at most `maxPerSecond` frames per rolling
   * second window.
   */
  drain(): Uint8Array[] {
    const now = this.now();
    if (now - this.windowStart >= 1000) {
      this.windowStart = now;
      this.sentInWindow = 0;
    }
    const budget = Math.max(0, this.maxPerSecond - this.sentInWindow);
    return this.take(budget);
  }

  /** Emit every pending frame regardless of budget (release / handoff). */
  flush(): Uint8Array[] {
    return this.take(this.queue.length);
  }

  private take(count: number): Uint8Array[] {
    const out: Uint8Array[] = [];
    while (out.length < count && this.queue.length > 0) {
      const frame = this.queue.shift();
      if (!frame) break;
      out.push(pointerFrame(frame.x, frame.y, frame.buttons, frame.flags, this.seq));
      this.seq = (this.seq + 1) >>> 0;
      this.sentInWindow += 1;
    }
    return out;
  }
}

// ─── `aa.input` JSON events (input-event.schema.json) ───────────────────────

/** `text` events cap `s` at 4096 chars; longer input must be split by callers. */
export const MAX_TEXT_EVENT_LENGTH = 4096;
/** v1 clipboard is a single `clipboard` offer of at most 8 KiB UTF-8. */
export const CLIPBOARD_MAX_BYTES = 8192;

export type PointerMoveParams = {
  a: "move";
  /** Normalized `[0,1]` screen coordinate. */
  x: number;
  y: number;
  /** Display index, 0..15. */
  d?: number;
  ts?: number;
};

export type PointerButtonParams = {
  a: "down" | "up";
  x: number;
  y: number;
  b: PointerButton;
  d?: number;
  ts?: number;
};

export type PointerEventParams = PointerMoveParams | PointerButtonParams;

export type WheelEventParams = {
  dx: number;
  dy: number;
  unit?: "pixel" | "line";
  d?: number;
  ts?: number;
};

export type KeyEventParams = {
  a: "down" | "up";
  code: string;
  mods?: InputMod[];
  repeat?: boolean;
  ts?: number;
};

export type TextEventParams = {
  s: string;
  ts?: number;
};

export type ClipboardOfferParams = {
  size: number;
  dataBase64: string;
  ts?: number;
};

function assertU32Seq(seq: number): number {
  assertFinite(seq, "seq");
  return seq >>> 0;
}

function assertNormalized(value: number, name: string): number {
  assertFinite(value, name);
  return clampUnit(value);
}

function assertDisplay(d: number | undefined): number | undefined {
  if (d === undefined) return undefined;
  assertFinite(d, "d");
  const index = Math.round(d);
  if (index < 0 || index > 15) throw new RangeError(`d must be 0..15, got ${d}`);
  return index;
}

/** `pointer` branch: `move` omits `b`, `down`/`up` require it (schema condition). */
export function encodePointerEvent(seq: number, params: PointerEventParams): RemoteDesktopInputEvent {
  const event: Record<string, unknown> = {
    v: 1,
    seq: assertU32Seq(seq),
    t: "pointer",
    a: params.a,
    x: assertNormalized(params.x, "x"),
    y: assertNormalized(params.y, "y"),
  };
  if (params.ts !== undefined) event.ts = params.ts;
  if (params.a !== "move") event.b = params.b;
  const d = assertDisplay(params.d);
  if (d !== undefined) event.d = d;
  return event as RemoteDesktopInputEvent;
}

export function encodeWheelEvent(seq: number, params: WheelEventParams): RemoteDesktopInputEvent {
  const event: Record<string, unknown> = {
    v: 1,
    seq: assertU32Seq(seq),
    t: "wheel",
    dx: params.dx,
    dy: params.dy,
  };
  if (params.ts !== undefined) event.ts = params.ts;
  if (params.unit !== undefined) event.unit = params.unit;
  const d = assertDisplay(params.d);
  if (d !== undefined) event.d = d;
  return event as RemoteDesktopInputEvent;
}

export function encodeKeyEvent(seq: number, params: KeyEventParams): RemoteDesktopInputEvent {
  const code = keyEventCode(params.code);
  if (!code) throw new TypeError(`invalid KeyboardEvent.code: ${params.code}`);
  const event: Record<string, unknown> = {
    v: 1,
    seq: assertU32Seq(seq),
    t: "key",
    a: params.a,
    code,
  };
  if (params.ts !== undefined) event.ts = params.ts;
  if (params.mods && params.mods.length > 0) event.mods = [...params.mods];
  if (params.repeat) event.repeat = true;
  return event as RemoteDesktopInputEvent;
}

export function encodeTextEvent(seq: number, params: TextEventParams): RemoteDesktopInputEvent {
  if (params.s.length > MAX_TEXT_EVENT_LENGTH) {
    throw new RangeError(`text event exceeds ${MAX_TEXT_EVENT_LENGTH} characters; split it first`);
  }
  const event: Record<string, unknown> = {
    v: 1,
    seq: assertU32Seq(seq),
    t: "text",
    s: params.s,
  };
  if (params.ts !== undefined) event.ts = params.ts;
  return event as RemoteDesktopInputEvent;
}

/** `ping` carries no `seq` (schema forbids it). */
export function encodePingEvent(at: number): RemoteDesktopInputEvent {
  return { v: 1, t: "ping", at } as RemoteDesktopInputEvent;
}

export function encodeReleaseAllEvent(
  seq: number | null,
  reason: "blur" | "pagehide" | "handoff" | "manual",
): RemoteDesktopInputEvent {
  const event: Record<string, unknown> = { v: 1, t: "release_all", reason };
  if (seq !== null) event.seq = assertU32Seq(seq);
  return event as RemoteDesktopInputEvent;
}

/** `clipboard` offer (v1: single packet, ≤8 KiB UTF-8 — see CLIPBOARD_MAX_BYTES). */
export function encodeClipboardOfferEvent(
  seq: number,
  params: ClipboardOfferParams,
): RemoteDesktopInputEvent {
  if (!Number.isInteger(params.size) || params.size < 0 || params.size > 65536) {
    throw new RangeError(`clipboard size must be 0..65536 bytes, got ${params.size}`);
  }
  const event: Record<string, unknown> = {
    v: 1,
    seq: assertU32Seq(seq),
    t: "clipboard",
    a: "offer",
    mime: "text/plain",
    size: params.size,
    dataBase64: params.dataBase64,
  };
  if (params.ts !== undefined) event.ts = params.ts;
  return event as RemoteDesktopInputEvent;
}

/** `clipboard` request: asks the host for its clipboard (no payload allowed). */
export function encodeClipboardRequestEvent(seq: number, ts?: number): RemoteDesktopInputEvent {
  const event: Record<string, unknown> = {
    v: 1,
    seq: assertU32Seq(seq),
    t: "clipboard",
    a: "request",
    mime: "text/plain",
  };
  if (ts !== undefined) event.ts = ts;
  return event as RemoteDesktopInputEvent;
}

/**
 * `aa.input` JSON encoder with an auto-incrementing `seq` (v1, u32 wrap).
 * The counter is independent from `InputThrottle`'s binary-frame counter.
 */
export class InputEventEncoder {
  private seq: number;

  constructor(startSeq = 0) {
    this.seq = startSeq >>> 0;
  }

  /** The seq the next encode call will use. */
  get nextSeq(): number {
    return this.seq >>> 0;
  }

  private takeSeq(): number {
    const seq = this.seq;
    this.seq = (this.seq + 1) >>> 0;
    return seq;
  }

  encodePointerEvent(params: PointerEventParams): RemoteDesktopInputEvent {
    return encodePointerEvent(this.takeSeq(), params);
  }

  encodeWheelEvent(params: WheelEventParams): RemoteDesktopInputEvent {
    return encodeWheelEvent(this.takeSeq(), params);
  }

  encodeKeyEvent(params: KeyEventParams): RemoteDesktopInputEvent {
    return encodeKeyEvent(this.takeSeq(), params);
  }

  encodeTextEvent(params: TextEventParams): RemoteDesktopInputEvent {
    return encodeTextEvent(this.takeSeq(), params);
  }

  encodeClipboardOffer(params: ClipboardOfferParams): RemoteDesktopInputEvent {
    return encodeClipboardOfferEvent(this.takeSeq(), params);
  }

  encodeClipboardRequest(): RemoteDesktopInputEvent {
    return encodeClipboardRequestEvent(this.takeSeq());
  }

  encodeReleaseAll(reason: "blur" | "pagehide" | "handoff" | "manual"): RemoteDesktopInputEvent {
    return encodeReleaseAllEvent(this.takeSeq(), reason);
  }

  /** `ping` does not consume a seq (the schema branch has none). */
  encodePing(at: number = Date.now()): RemoteDesktopInputEvent {
    return encodePingEvent(at);
  }
}

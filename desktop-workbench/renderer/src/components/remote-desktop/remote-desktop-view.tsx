"use client"

import * as React from "react"
import { useTranslations } from "next-intl"
import {
  ChevronLeft,
  Clipboard,
  Keyboard,
  Loader2,
  Maximize2,
  Minimize2,
  Monitor,
  MousePointer2,
  RefreshCw,
  ZoomIn,
  ZoomOut,
  Camera,
} from "lucide-react"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Input } from "@/components/ui/input"
import { cn } from "@/lib/utils"
import { useAuth } from "@/components/auth/auth-context"
import { useWorkspace } from "@/components/workspace-context"
import { remoteDesktopApi, remoteDesktopErrorMessage } from "@/lib/remote-desktop/api"
import {
  screenshotBlob,
  screenshotFilename,
  type RemoteScreenshot,
} from "@/lib/remote-desktop/screenshot"
import { downloadBlob } from "@/lib/download"
import {
  useRemoteDesktop,
  type RemoteDesktopStreamStats,
} from "@/lib/remote-desktop/use-remote-desktop"
import {
  CLIPBOARD_MAX_BYTES,
  InputEventEncoder,
  InputThrottle,
  MAX_TEXT_EVENT_LENGTH,
  keyEventCode,
  modsFromEvent,
  normalizePoint,
  type InputMod,
  type PointerButton,
  type ViewTransform,
} from "@/lib/remote-desktop/input"
import type {
  RemoteDesktopCapability,
  RemoteDesktopHostInfo,
} from "@/generated/remote-desktop/v1_0/host-info"
import type {
  RemoteDesktopMediaGrant,
} from "@/generated/remote-desktop/v1_0/media-grant"
import type {
  RemoteDesktopSession,
} from "@/generated/remote-desktop/v1_0/session"

// ─── Pure helpers ───────────────────────────────────────────────────────────

type Rect = { left: number; top: number; width: number; height: number }

function capabilityById(
  info: RemoteDesktopHostInfo | null,
  id: RemoteDesktopCapability["capabilityId"],
): RemoteDesktopCapability | null {
  return info?.capabilities.find((item) => item.capabilityId === id) ?? null
}

function capabilityUsable(capability: RemoteDesktopCapability | null): boolean {
  return Boolean(capability && capability.supported && capability.available && capability.allowed)
}

/** The letterboxed content box (aspect-fit) of a video inside its container. */
function contentBox(container: Rect, videoWidth: number, videoHeight: number): Rect {
  if (videoWidth <= 0 || videoHeight <= 0) return container
  const scale = Math.min(container.width / videoWidth, container.height / videoHeight)
  const width = videoWidth * scale
  const height = videoHeight * scale
  return {
    left: container.left + (container.width - width) / 2,
    top: container.top + (container.height - height) / 2,
    width,
    height,
  }
}

function bytesToBase64(bytes: Uint8Array): string {
  let binary = ""
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary)
}

function base64ToText(base64: string): string {
  const bytes = Uint8Array.from(atob(base64), (character) => character.charCodeAt(0))
  return new TextDecoder().decode(bytes)
}

const BUTTON_BITS: Record<PointerButton, number> = { left: 1, right: 2, middle: 4 }
const MODIFIER_CODES = new Set([
  "ControlLeft", "ControlRight", "ShiftLeft", "ShiftRight",
  "AltLeft", "AltRight", "MetaLeft", "MetaRight", "OSLeft", "OSRight",
])

// ─── Page wrapper (hash route `#/device/:connectorId/remote`) ───────────────

export function RemoteDesktopPage() {
  const { activeConnectorId, navigateToDevice } = useWorkspace()
  if (!activeConnectorId) return null
  return (
    <RemoteDesktopView
      connectorId={activeConnectorId}
      onExit={() => navigateToDevice(activeConnectorId)}
    />
  )
}

// ─── Main view ──────────────────────────────────────────────────────────────

type Props = {
  connectorId: string
  onExit: () => void
}

type SessionBundle = {
  session: RemoteDesktopSession | null
  participantId: string | null
  media: RemoteDesktopMediaGrant | null
  wsTicket: string | null
}

export function RemoteDesktopView({ connectorId, onExit }: Props) {
  const t = useTranslations("remoteDesktop")
  const tCommon = useTranslations("common")
  const { session: authSession } = useAuth()
  const token = authSession?.accessToken ?? null

  // ── Capability ──────────────────────────────────────────────────────────
  const [hostInfo, setHostInfo] = React.useState<RemoteDesktopHostInfo | null>(null)
  const [capabilityError, setCapabilityError] = React.useState<string | null>(null)
  const [capabilityLoading, setCapabilityLoading] = React.useState(true)

  const loadCapability = React.useCallback(() => {
    if (!token) return
    setCapabilityLoading(true)
    setCapabilityError(null)
    remoteDesktopApi
      .getHostInfo(token, connectorId)
      .then((info) => setHostInfo(info))
      .catch((error) => setCapabilityError(remoteDesktopErrorMessage(error)))
      .finally(() => setCapabilityLoading(false))
  }, [connectorId, token])

  React.useEffect(() => { loadCapability() }, [loadCapability])

  const viewCapability = capabilityById(hostInfo, "connector.remote_desktop.view")
  const controlCapability = capabilityById(hostInfo, "connector.remote_desktop.control")
  const clipboardCapability = capabilityById(hostInfo, "connector.remote_desktop.clipboard")
  const viewReady = capabilityUsable(viewCapability)
  const controlReady = capabilityUsable(controlCapability)
  const clipboardReady = capabilityUsable(clipboardCapability)

  // ── Session lifecycle ───────────────────────────────────────────────────
  const [bundle, setBundle] = React.useState<SessionBundle>({
    session: null,
    participantId: null,
    media: null,
    wsTicket: null,
  })
  const [joining, setJoining] = React.useState(false)
  const [sessionError, setSessionError] = React.useState<string | null>(null)
  const [endedInfo, setEndedInfo] = React.useState<{ reason: string; at: string } | null>(null)
  const joinAttemptedRef = React.useRef(false)

  const joinSession = React.useCallback(() => {
    if (!token || joinAttemptedRef.current) return
    joinAttemptedRef.current = true
    setJoining(true)
    setSessionError(null)
    remoteDesktopApi
      .createSession(token, {
        connectorId,
        mode: "view",
        quality: { maxFps: 30, maxWidth: 1920 },
        client: {
          platform: "web",
          viewport: {
            width: typeof window === "undefined" ? 1440 : window.innerWidth,
            height: typeof window === "undefined" ? 900 : window.innerHeight,
          },
        },
      })
      .then((response) => {
        setBundle({
          session: response.session,
          participantId: response.participantId,
          media: response.media,
          wsTicket: response.ws.ticket,
        })
      })
      .catch((error) => {
        joinAttemptedRef.current = false
        setSessionError(remoteDesktopErrorMessage(error))
      })
      .finally(() => setJoining(false))
  }, [connectorId, token])

  React.useEffect(() => {
    if (viewReady && token && !joining && !sessionError && !bundle.session) joinSession()
  }, [bundle.session, joinSession, joining, sessionError, token, viewReady])

  const session = bundle.session
  const participantId = bundle.participantId
  const sessionId = session?.id ?? null
  const isController = Boolean(
    session && participantId && session.controller === `viewer:${participantId}`,
  )
  const isControllerRef = React.useRef(isController)
  isControllerRef.current = isController

  // ── Media + control WS (hook) ───────────────────────────────────────────
  const handleHostEvent = React.useCallback((event: unknown) => {
    const record = (event ?? {}) as {
      t?: string
      a?: string
      code?: string
      reason?: string
      dataBase64?: string
    }
    if (record.t === "reject") {
      const key = record.code ? `inputRejected.${record.code}` : "inputRejected.invalid_event"
      toast.error(t.has(key) ? t(key) : t("inputRejected.invalid_event"))
      return
    }
    if (record.t === "clipboard") {
      if (record.a === "offer" && record.dataBase64) {
        try {
          const text = base64ToText(record.dataBase64)
          void navigator.clipboard?.writeText(text).then(
            () => toast.success(t("clipboardReceived")),
            () => toast.error(t("clipboardDenied")),
          )
        } catch {
          toast.error(t("clipboardDenied"))
        }
      } else if (record.a === "denied") {
        toast.error(t("clipboardDenied"))
      }
      return
    }
    // TODO(clipboard-transfer): chunked transfers (a=begin/chunk/end) are not
    // implemented in v1; the sending side refuses payloads over 8 KiB instead.
    if (record.t === "clipboard_transfer") {
      toast.error(t("clipboardUnsupported"))
    }
  }, [t])

  const remote = useRemoteDesktop({
    token,
    sessionId,
    participantId,
    media: bundle.media,
    wsTicket: bundle.wsTicket,
    role: isController ? "controller" : "viewer",
    onSessionUpdated: (next) => {
      setBundle((current) => ({ ...current, session: next }))
      if (next.state === "ended") {
        setEndedInfo({ reason: next.endReason ?? "client_ended", at: next.endedAt ?? "" })
      }
    },
    onControlRevoked: (reason) => {
      toast.info(
        reason === "taken_over"
          ? t("takenOver")
          : reason === "lease_expired"
            ? t("leaseExpired")
            : t("policyRevoked"),
      )
    },
    onEnded: (reason, at) => setEndedInfo({ reason, at }),
    onError: (code, message) => toast.error(message || code),
    onHostEvent: handleHostEvent,
  })

  // Stable senders (the hook returns fresh objects each render).
  const sendInput = remote.sendInput
  const sendPointerFrame = remote.sendPointerFrame
  const attachRemoteVideo = remote.videoRef
  const requestKeyframe = remote.requestKeyframe
  const updateQuality = remote.updateQuality
  const reconnectMedia = remote.reconnect
  const streamStats = remote.stats

  // ── Control actions ─────────────────────────────────────────────────────
  const [controlBusy, setControlBusy] = React.useState(false)
  const [leaseNow, setLeaseNow] = React.useState(() => Date.now())

  React.useEffect(() => {
    if (!isController) return
    const timer = window.setInterval(() => setLeaseNow(Date.now()), 1_000)
    return () => window.clearInterval(timer)
  }, [isController])

  React.useEffect(() => {
    if (!isController || !token || !sessionId || !participantId) return
    // Controller lease is 60 s; renew every 20 s (docs/api/remote-desktop.md).
    const timer = window.setInterval(() => {
      remoteDesktopApi
        .heartbeat(token, sessionId, { participantId })
        .then((response) => setBundle((current) => ({ ...current, session: response.session })))
        .catch(() => {
          // Lease loss surfaces as `remote_desktop.control.revoked` on the WS.
        })
    }, 20_000)
    return () => window.clearInterval(timer)
  }, [isController, participantId, sessionId, token])

  const requestControl = React.useCallback(async () => {
    if (!token || !sessionId || !participantId) return
    setControlBusy(true)
    try {
      const response = await remoteDesktopApi.takeControl(token, sessionId, { participantId })
      setBundle((current) => ({ ...current, session: response.session }))
      if (response.session.state === "live" || response.session.state === "degraded") {
        // We are the controller now: swap in a controller grant so the data
        // channel allows `aa.input` / `aa.pointer` publishing.
        const grant = await remoteDesktopApi.refreshMediaToken(token, sessionId, {
          participantId,
          role: "controller",
        })
        setBundle((current) => ({ ...current, media: grant }))
      }
    } catch (error) {
      toast.error(remoteDesktopErrorMessage(error))
    } finally {
      setControlBusy(false)
    }
  }, [participantId, sessionId, token])

  const releaseControl = React.useCallback(async () => {
    if (!token || !sessionId) return
    setControlBusy(true)
    try {
      const response = await remoteDesktopApi.releaseControl(token, sessionId, {})
      setBundle((current) => ({ ...current, session: response.session }))
      toast.success(t("controlLost"))
    } catch (error) {
      toast.error(remoteDesktopErrorMessage(error))
    } finally {
      setControlBusy(false)
    }
  }, [sessionId, t, token])

  const cancelConsent = React.useCallback(async () => {
    // There is no cancel-consent endpoint; ending the session is the only
    // contract-supported way to withdraw a pending control request (v1).
    if (!token || !sessionId) return
    try {
      await remoteDesktopApi.endSession(token, sessionId, { reason: "client_ended" })
    } catch (error) {
      toast.error(remoteDesktopErrorMessage(error))
    }
    onExit()
  }, [onExit, sessionId, token])

  // ── Input plumbing ──────────────────────────────────────────────────────
  const containerRef = React.useRef<HTMLDivElement | null>(null)
  const encoderRef = React.useRef(new InputEventEncoder())
  const throttleRef = React.useRef(new InputThrottle())
  const buttonsMaskRef = React.useRef(0)
  const dragLockRef = React.useRef(false)
  const stickyModsRef = React.useRef<InputMod[]>([])
  const spaceDownRef = React.useRef(false)
  const viewRef = React.useRef<ViewTransform>({ scale: 1, offsetX: 0, offsetY: 0 })
  const videoDimsRef = React.useRef({ width: 0, height: 0 })
  const lastPointRef = React.useRef({ x: 0.5, y: 0.5 })
  const [videoDims, setVideoDims] = React.useState({ width: 0, height: 0 })
  const [containerSize, setContainerSize] = React.useState({ width: 0, height: 0 })
  const [view, setView] = React.useState<ViewTransform>({ scale: 1, offsetX: 0, offsetY: 0 })
  const [dragLock, setDragLock] = React.useState(false)
  const [stickyMods, setStickyMods] = React.useState<InputMod[]>([])
  const [keyboardOpen, setKeyboardOpen] = React.useState(false)
  const [statsOpen, setStatsOpen] = React.useState(false)
  const [isFullscreen, setIsFullscreen] = React.useState(false)

  const applyView = React.useCallback((next: ViewTransform) => {
    const scale = Math.min(8, Math.max(1, next.scale))
    // Keep the stage covering the content box: offsets stay within
    // [rect·(1−scale), 0] on both axes (approximated from the fit box below).
    const minX = Math.min(0, containerSize.width * (1 - scale))
    const minY = Math.min(0, containerSize.height * (1 - scale))
    const offsetX = scale === 1 ? 0 : Math.min(0, Math.max(next.offsetX, minX))
    const offsetY = scale === 1 ? 0 : Math.min(0, Math.max(next.offsetY, minY))
    const value: ViewTransform = { scale, offsetX, offsetY }
    viewRef.current = value
    setView(value)
  }, [containerSize.height, containerSize.width])

  const attachVideo = React.useCallback((element: HTMLVideoElement | null) => {
    attachRemoteVideo(element)
  }, [attachRemoteVideo])

  const containerRect = React.useCallback((): Rect | null => {
    const element = containerRef.current
    if (!element) return null
    const rect = element.getBoundingClientRect()
    return { left: rect.left, top: rect.top, width: rect.width, height: rect.height }
  }, [])

  const pointAt = React.useCallback((clientX: number, clientY: number) => {
    const container = containerRect()
    if (!container) return null
    const rect = contentBox(container, videoDimsRef.current.width, videoDimsRef.current.height)
    try {
      return normalizePoint(clientX, clientY, rect, viewRef.current)
    } catch {
      return null
    }
  }, [containerRect])

  const sendMove = React.useCallback((x: number, y: number) => {
    if (!isControllerRef.current) return
    lastPointRef.current = { x, y }
    throttleRef.current.pushPointer(Math.round(x * 65535), Math.round(y * 65535), buttonsMaskRef.current)
  }, [])

  const sendButton = React.useCallback((action: "down" | "up", button: PointerButton, x: number, y: number) => {
    if (!isControllerRef.current) return
    const bit = BUTTON_BITS[button]
    if (action === "down") buttonsMaskRef.current |= bit
    else buttonsMaskRef.current &= ~bit
    sendInput(encoderRef.current.encodePointerEvent({ a: action, x, y, b: button }))
    throttleRef.current.pushPointer(Math.round(x * 65535), Math.round(y * 65535), buttonsMaskRef.current)
  }, [sendInput])

  const sendWheel = React.useCallback((dx: number, dy: number) => {
    if (!isControllerRef.current) return
    sendInput(encoderRef.current.encodeWheelEvent({ dx, dy, unit: "pixel" }))
  }, [sendInput])

  // Drain queued pointer frames at ~60 fps (throttle caps at 120/s).
  React.useEffect(() => {
    const timer = window.setInterval(() => {
      for (const frame of throttleRef.current.drain()) sendPointerFrame(frame)
    }, 16)
    return () => {
      window.clearInterval(timer)
      for (const frame of throttleRef.current.flush()) sendPointerFrame(frame)
    }
  }, [sendPointerFrame])

  // Release everything held when the tab loses focus (contract `release_all`).
  React.useEffect(() => {
    const onBlur = () => {
      if (!isControllerRef.current) return
      buttonsMaskRef.current = 0
      setDragLock(false)
      dragLockRef.current = false
      sendInput(encoderRef.current.encodeReleaseAll("blur"))
    }
    window.addEventListener("blur", onBlur)
    return () => window.removeEventListener("blur", onBlur)
  }, [sendInput])

  // Track the video's intrinsic size and the container size for the fit box.
  const handleLoadedMetadata = React.useCallback((event: React.SyntheticEvent<HTMLVideoElement>) => {
    const element = event.currentTarget
    const dims = { width: element.videoWidth, height: element.videoHeight }
    videoDimsRef.current = dims
    setVideoDims(dims)
  }, [])

  React.useEffect(() => {
    const element = containerRef.current
    if (!element) return
    const measure = () => setContainerSize({ width: element.clientWidth, height: element.clientHeight })
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    measure()
    return () => observer.disconnect()
  }, [])

  // ── Mouse / pen (pointer events) ────────────────────────────────────────
  const panStartRef = React.useRef<{ x: number; y: number; offsetX: number; offsetY: number } | null>(null)

  const handlePointerDown = React.useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    if (event.pointerType === "touch") return // touch flows through touch handlers
    containerRef.current?.focus()
    const point = pointAt(event.clientX, event.clientY)
    if (!point) return
    if (event.button === 1 || spaceDownRef.current) {
      panStartRef.current = {
        x: event.clientX,
        y: event.clientY,
        offsetX: viewRef.current.offsetX,
        offsetY: viewRef.current.offsetY,
      }
      event.currentTarget.setPointerCapture(event.pointerId)
      return
    }
    const button: PointerButton = event.button === 2 ? "right" : event.button === 1 ? "middle" : "left"
    sendButton("down", button, point.x, point.y)
    event.currentTarget.setPointerCapture(event.pointerId)
  }, [pointAt, sendButton])

  const handlePointerMove = React.useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    if (event.pointerType === "touch") return
    const pan = panStartRef.current
    if (pan) {
      applyView({
        scale: viewRef.current.scale,
        offsetX: pan.offsetX + (event.clientX - pan.x),
        offsetY: pan.offsetY + (event.clientY - pan.y),
      })
      return
    }
    const point = pointAt(event.clientX, event.clientY)
    if (point) sendMove(point.x, point.y)
  }, [applyView, pointAt, sendMove])

  const handlePointerUp = React.useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    if (event.pointerType === "touch") return
    if (panStartRef.current) {
      panStartRef.current = null
      return
    }
    const point = pointAt(event.clientX, event.clientY) ?? lastPointRef.current
    const button: PointerButton = event.button === 2 ? "right" : event.button === 1 ? "middle" : "left"
    sendButton("up", button, point.x, point.y)
  }, [pointAt, sendButton])

  const handleDoubleClick = React.useCallback(() => {
    // Double-click drag lock: hold the left button until the next click.
    if (!isControllerRef.current) return
    const point = lastPointRef.current
    if (dragLockRef.current) {
      dragLockRef.current = false
      setDragLock(false)
      sendButton("up", "left", point.x, point.y)
    } else {
      dragLockRef.current = true
      setDragLock(true)
      sendButton("down", "left", point.x, point.y)
    }
  }, [sendButton])

  const zoomAround = React.useCallback((clientX: number, clientY: number, nextScale: number) => {
    const container = containerRect()
    if (!container) return
    const rect = contentBox(container, videoDimsRef.current.width, videoDimsRef.current.height)
    const current = viewRef.current
    const scale = Math.min(8, Math.max(1, nextScale))
    const px = (clientX - rect.left - current.offsetX) / (rect.width * current.scale)
    const py = (clientY - rect.top - current.offsetY) / (rect.height * current.scale)
    applyView({
      scale,
      offsetX: clientX - rect.left - px * rect.width * scale,
      offsetY: clientY - rect.top - py * rect.height * scale,
    })
  }, [applyView, containerRect])

  const handleWheel = React.useCallback((event: React.WheelEvent<HTMLDivElement>) => {
    if (event.ctrlKey) {
      // Ctrl+wheel zooms locally around the cursor.
      zoomAround(event.clientX, event.clientY, viewRef.current.scale * Math.exp(-event.deltaY / 320))
      return
    }
    sendWheel(event.deltaX, event.deltaY)
  }, [sendWheel, zoomAround])

  // ── Touch gestures ──────────────────────────────────────────────────────
  const touchRef = React.useRef<{
    mode: "none" | "pointer" | "two"
    startX: number
    startY: number
    lastX: number
    lastY: number
    moved: boolean
    downSent: boolean
    longPressTimer: number | null
    lastTapAt: number
    lastTapX: number
    lastTapY: number
    pinchDistance: number
    pinchMidX: number
    pinchMidY: number
  }>({
    mode: "none",
    startX: 0,
    startY: 0,
    lastX: 0,
    lastY: 0,
    moved: false,
    downSent: false,
    longPressTimer: null,
    lastTapAt: 0,
    lastTapX: 0,
    lastTapY: 0,
    pinchDistance: 0,
    pinchMidX: 0,
    pinchMidY: 0,
  })

  const clearLongPress = React.useCallback(() => {
    const timer = touchRef.current.longPressTimer
    if (timer !== null) window.clearTimeout(timer)
    touchRef.current.longPressTimer = null
  }, [])

  const handleTouchStart = React.useCallback((event: React.TouchEvent<HTMLDivElement>) => {
    event.preventDefault()
    const state = touchRef.current
    const touches = event.touches
    if (touches.length === 3) {
      clearLongPress()
      state.mode = "none"
      setKeyboardOpen((open) => !open) // 三指点击唤起软键盘
      return
    }
    if (touches.length >= 2) {
      clearLongPress()
      state.mode = "two"
      const a = touches[0]
      const b = touches[1]
      if (!a || !b) return
      state.pinchDistance = Math.hypot(b.clientX - a.clientX, b.clientY - a.clientY)
      state.pinchMidX = (a.clientX + b.clientX) / 2
      state.pinchMidY = (a.clientY + b.clientY) / 2
      state.lastX = state.pinchMidX
      state.lastY = state.pinchMidY
      return
    }
    const touch = touches[0]
    if (!touch) return
    state.mode = "pointer"
    state.startX = touch.clientX
    state.startY = touch.clientY
    state.lastX = touch.clientX
    state.lastY = touch.clientY
    state.moved = false
    state.downSent = false
    clearLongPress()
    // Long-press (500 ms) without movement = right-click.
    state.longPressTimer = window.setTimeout(() => {
      state.longPressTimer = null
      const point = pointAt(state.startX, state.startY)
      if (!point || state.moved) return
      state.downSent = true
      sendButton("down", "right", point.x, point.y)
      sendButton("up", "right", point.x, point.y)
    }, 500)
  }, [clearLongPress, pointAt, sendButton])

  const handleTouchMove = React.useCallback((event: React.TouchEvent<HTMLDivElement>) => {
    event.preventDefault()
    const state = touchRef.current
    const touches = event.touches
    if (state.mode === "two" && touches.length === 2) {
      const a = touches[0]
      const b = touches[1]
      if (!a || !b) return
      const distance = Math.hypot(b.clientX - a.clientX, b.clientY - a.clientY)
      const midX = (a.clientX + b.clientX) / 2
      const midY = (a.clientY + b.clientY) / 2
      if (state.pinchDistance > 0 && Math.abs(distance - state.pinchDistance) > 2) {
        // Pinch: zoom locally around the midpoint.
        zoomAround(state.pinchMidX, state.pinchMidY, viewRef.current.scale * (distance / state.pinchDistance))
      } else if (viewRef.current.scale > 1) {
        // Two-finger drag while zoomed pans the canvas.
        applyView({
          scale: viewRef.current.scale,
          offsetX: viewRef.current.offsetX + (midX - state.lastX),
          offsetY: viewRef.current.offsetY + (midY - state.lastY),
        })
      } else {
        // Two-finger drag scrolls the remote page (wheel events).
        sendWheel(midX - state.lastX, midY - state.lastY)
      }
      state.pinchDistance = distance
      state.pinchMidX = midX
      state.pinchMidY = midY
      state.lastX = midX
      state.lastY = midY
      return
    }
    if (state.mode !== "pointer") return
    const touch = touches[0]
    if (!touch) return
    if (!state.moved && Math.hypot(touch.clientX - state.startX, touch.clientY - state.startY) > 8) {
      state.moved = true
      clearLongPress()
      if (!dragLockRef.current) {
        const start = pointAt(state.startX, state.startY)
        if (start) {
          state.downSent = true
          sendButton("down", "left", start.x, start.y)
        }
      }
    }
    if (state.moved || dragLockRef.current) {
      const point = pointAt(touch.clientX, touch.clientY)
      if (point) sendMove(point.x, point.y)
    }
    state.lastX = touch.clientX
    state.lastY = touch.clientY
  }, [applyView, clearLongPress, pointAt, sendButton, sendMove, sendWheel, zoomAround])

  const handleTouchEnd = React.useCallback((event: React.TouchEvent<HTMLDivElement>) => {
    event.preventDefault()
    const state = touchRef.current
    if (state.mode === "two") {
      if (event.touches.length === 0) state.mode = "none"
      return
    }
    if (state.mode !== "pointer") return
    clearLongPress()
    state.mode = "none"
    const point = pointAt(state.lastX, state.lastY)
    if (!point) return
    if (state.moved) {
      if (state.downSent) sendButton("up", "left", point.x, point.y)
      return
    }
    // A tap after the long-press fired: its right-click was already sent.
    if (state.downSent && state.longPressTimer === null) return
    const now = Date.now()
    const isDoubleTap =
      now - state.lastTapAt < 300 &&
      Math.hypot(state.lastX - state.lastTapX, state.lastY - state.lastTapY) < 24
    state.lastTapAt = now
    state.lastTapX = state.lastX
    state.lastTapY = state.lastY
    if (isDoubleTap || dragLockRef.current) {
      // Double-tap toggles drag lock; a tap while locked releases it.
      if (dragLockRef.current) {
        dragLockRef.current = false
        setDragLock(false)
        sendButton("up", "left", point.x, point.y)
      } else {
        dragLockRef.current = true
        setDragLock(true)
        sendButton("down", "left", point.x, point.y)
      }
      return
    }
    sendButton("down", "left", point.x, point.y)
    sendButton("up", "left", point.x, point.y)
  }, [clearLongPress, pointAt, sendButton])

  // ── Still frame ─────────────────────────────────────────────────────────
  const [screenshotBusy, setScreenshotBusy] = React.useState(false)
  const captureScreenshot = React.useCallback(async () => {
    if (!token || !session?.id) return
    setScreenshotBusy(true)
    try {
      const shot = (await remoteDesktopApi.screenshot(token, session.id, {
        format: "jpeg",
        maxWidth: 1920,
        quality: 85,
      })) as RemoteScreenshot
      downloadBlob(
        screenshotBlob(shot),
        screenshotFilename(session.connectorId || "remote", shot.capturedAt, shot.format),
      )
      toast.success(t("screenshotSaved"))
    } catch (error) {
      toast.error(remoteDesktopErrorMessage(error))
    } finally {
      setScreenshotBusy(false)
    }
  }, [token, session?.id, session?.connectorId, t])

  // ── Physical keyboard ───────────────────────────────────────────────────
  const handleKeyDown = React.useCallback((event: React.KeyboardEvent<HTMLDivElement>) => {
    if (event.key === " " && !event.repeat) {
      spaceDownRef.current = true
      event.preventDefault()
      return
    }
    if (!isControllerRef.current) return
    const code = keyEventCode(event.code)
    if (!code) return
    if (code === "F5" || code === "F12") return // keep browser affordances
    event.preventDefault()
    const mods = modsFromEvent(event)
    for (const mod of stickyModsRef.current) if (!mods.includes(mod)) mods.push(mod)
    sendInput(encoderRef.current.encodeKeyEvent({
      a: "down",
      code,
      mods,
      repeat: event.repeat,
    }))
    if (!MODIFIER_CODES.has(code)) {
      // Sticky modifiers apply to the next keystroke only.
      stickyModsRef.current = []
      setStickyMods([])
    }
  }, [sendInput])

  const handleKeyUp = React.useCallback((event: React.KeyboardEvent<HTMLDivElement>) => {
    if (event.key === " ") {
      spaceDownRef.current = false
      return
    }
    if (!isControllerRef.current) return
    const code = keyEventCode(event.code)
    if (!code || code === "F5" || code === "F12") return
    event.preventDefault()
    sendInput(encoderRef.current.encodeKeyEvent({ a: "up", code, mods: modsFromEvent(event) }))
  }, [sendInput])

  // ── Soft keyboard (text events) ─────────────────────────────────────────
  const [softText, setSoftText] = React.useState("")
  const sendSoftText = React.useCallback(() => {
    const text = softText
    setSoftText("")
    if (!text || !isControllerRef.current) return
    for (let offset = 0; offset < text.length; offset += MAX_TEXT_EVENT_LENGTH) {
      sendInput(encoderRef.current.encodeTextEvent({ s: text.slice(offset, offset + MAX_TEXT_EVENT_LENGTH) }))
    }
  }, [sendInput, softText])

  // ── Clipboard (single-packet v1) ────────────────────────────────────────
  const pasteToDevice = React.useCallback(async () => {
    if (!isControllerRef.current) return
    try {
      const text = await navigator.clipboard.readText()
      const bytes = new TextEncoder().encode(text)
      if (bytes.byteLength > CLIPBOARD_MAX_BYTES) {
        // TODO(clipboard-transfer): chunked transfer lands in a later release.
        toast.error(t("clipboardTooLarge"))
        return
      }
      sendInput(encoderRef.current.encodeClipboardOffer({
        size: bytes.byteLength,
        dataBase64: bytesToBase64(bytes),
      }))
    } catch {
      toast.error(t("clipboardDenied"))
    }
  }, [sendInput, t])

  const copyFromDevice = React.useCallback(() => {
    if (!isControllerRef.current) return
    sendInput(encoderRef.current.encodeClipboardRequest())
  }, [sendInput])

  // ── Fullscreen ──────────────────────────────────────────────────────────
  React.useEffect(() => {
    const onChange = () => setIsFullscreen(Boolean(document.fullscreenElement))
    document.addEventListener("fullscreenchange", onChange)
    return () => document.removeEventListener("fullscreenchange", onChange)
  }, [])

  const toggleFullscreen = React.useCallback(() => {
    const element = containerRef.current
    if (!element) return
    if (document.fullscreenElement) void document.exitFullscreen()
    else void element.requestFullscreen().catch(() => undefined)
  }, [])

  // ── Derived UI state ────────────────────────────────────────────────────
  const state = session?.state
  const weakNetwork = state === "degraded" ||
    Boolean(streamStats && (streamStats.fps < 10 || streamStats.packetLossPct > 8))
  const connecting = remote.state === "connecting" || joining || state === "starting"
  const reconnecting = remote.state === "reconnecting" || state === "interrupted"
  const mediaFailed = remote.state === "failed"
  const leaseSeconds = session?.controllerLeaseExpiresAt
    ? Math.max(0, Math.round((Date.parse(session.controllerLeaseExpiresAt) - leaseNow) / 1000))
    : 0

  // The stage box is the aspect-fit content box, so the `translate(offset)
  // scale(zoom)` transform origin matches `normalizePoint`'s inverse math.
  const fit = contentBox(
    { left: 0, top: 0, width: containerSize.width, height: containerSize.height },
    videoDims.width,
    videoDims.height,
  )

  // ── Capability gate ─────────────────────────────────────────────────────
  if (capabilityLoading && !hostInfo && !capabilityError) {
    return <CenterStatus icon={<Loader2 className="size-6 animate-spin" />} text={t("connectingMedia")} />
  }
  if (capabilityError || !viewReady) {
    let reason: string
    if (capabilityError) reason = capabilityError
    else if (!viewCapability || !viewCapability.supported) reason = t("capability.notSupported")
    else if (!viewCapability.available) {
      const key = viewCapability.unavailableReason ? `capability.${viewCapability.unavailableReason}` : ""
      reason = key && t.has(key) ? t(key) : t("capability.connector_offline")
    } else reason = t("capability.notAllowed")
    return (
      <CenterStatus
        icon={<Monitor className="size-6 text-muted-foreground" />}
        title={t("capabilityTitle")}
        text={reason}
        action={
          <Button onClick={loadCapability}>
            <RefreshCw />
            {t("capabilityRetry")}
          </Button>
        }
        footer={<ExitButton onExit={onExit} />}
      />
    )
  }

  // ── Session create failure ──────────────────────────────────────────────
  if (sessionError && !session) {
    return (
      <CenterStatus
        icon={<Monitor className="size-6 text-muted-foreground" />}
        title={t("failedTitle")}
        text={sessionError}
        action={
          <Button onClick={() => { joinAttemptedRef.current = false; joinSession() }}>
            <RefreshCw />
            {t("retry")}
          </Button>
        }
        footer={<ExitButton onExit={onExit} />}
      />
    )
  }

  // ── Ended ───────────────────────────────────────────────────────────────
  if (endedInfo || state === "ended") {
    const reason = endedInfo?.reason ?? session?.endReason ?? "client_ended"
    return (
      <CenterStatus
        icon={<Monitor className="size-6 text-muted-foreground" />}
        title={t("endedTitle")}
        text={t.has(`endedReason.${reason}`) ? t(`endedReason.${reason}`) : reason}
        footer={<ExitButton onExit={onExit} />}
      />
    )
  }

  // ── Awaiting consent ────────────────────────────────────────────────────
  if (state === "awaiting_consent") {
    return (
      <CenterStatus
        icon={<Loader2 className="size-6 animate-spin" />}
        title={t("awaitingConsentTitle")}
        text={t("awaitingConsentBody")}
        action={
          <Button variant="outline" onClick={() => void cancelConsent()}>
            {tCommon("cancel")}
          </Button>
        }
        footer={<ExitButton onExit={onExit} />}
      />
    )
  }

  // ── Live view ───────────────────────────────────────────────────────────
  return (
    <div className="flex h-full min-h-0 w-full flex-col bg-background">
      {/* Toolbar */}
      <div className="flex items-center gap-2 border-b border-border px-3 py-2">
        <Button variant="ghost" size="sm" onClick={onExit}>
          <ChevronLeft />
          {t("exit")}
        </Button>
        <Badge variant={isController ? "default" : "secondary"}>
          {isController ? t("controlling") : t("title")}
        </Badge>
        {isController && session?.controllerLeaseExpiresAt ? (
          <span className="text-xs text-muted-foreground">{t("leaseRemaining", { seconds: leaseSeconds })}</span>
        ) : null}
        <div className="ml-auto flex items-center gap-1">
          {controlReady && !isController ? (
            <Button size="sm" onClick={() => void requestControl()} disabled={controlBusy}>
              <MousePointer2 />
              {t("takeControl")}
            </Button>
          ) : null}
          {controlReady && isController ? (
            <Button size="sm" variant="outline" onClick={() => void releaseControl()} disabled={controlBusy}>
              {t("releaseControl")}
            </Button>
          ) : null}
          {clipboardReady ? (
            <>
              <Button size="sm" variant="ghost" onClick={copyFromDevice} aria-label={t("clipboardCopyFromDevice")}>
                <Clipboard />
              </Button>
              <Button size="sm" variant="ghost" onClick={() => void pasteToDevice()} aria-label={t("clipboardPasteToDevice")}>
                <Clipboard />
              </Button>
            </>
          ) : null}
          <Button
            size="sm"
            variant="ghost"
            onClick={() => void captureScreenshot()}
            disabled={screenshotBusy || !session?.id}
            aria-label={t("screenshot")}
          >
            <Camera />
          </Button>
          <Button
            size="sm"
            variant="ghost"
            onClick={() => setKeyboardOpen((open) => !open)}
            aria-label={t("keyboard")}
          >
            <Keyboard />
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setStatsOpen((open) => !open)} aria-label={t("stats")}>
            {t("stats")}
          </Button>
          <Button
            size="sm"
            variant="ghost"
            onClick={() => applyView({ scale: view.scale <= 1 ? 2 : 1, offsetX: 0, offsetY: 0 })}
            aria-label={view.scale > 1 ? t("zoomReset") : t("zoomIn")}
          >
            {view.scale > 1 ? <ZoomOut /> : <ZoomIn />}
          </Button>
          <Button size="sm" variant="ghost" onClick={toggleFullscreen} aria-label={t("fullscreen")}>
            {isFullscreen ? <Minimize2 /> : <Maximize2 />}
          </Button>
        </div>
      </div>

      {/* Video stage */}
      <div
        ref={containerRef}
        tabIndex={0}
        className="relative flex min-h-0 flex-1 items-center justify-center overflow-hidden bg-black/90 outline-none"
        style={{ touchAction: "none" }}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        onPointerCancel={handlePointerUp}
        onDoubleClick={handleDoubleClick}
        onContextMenu={(event) => event.preventDefault()}
        onWheel={handleWheel}
        onKeyDown={handleKeyDown}
        onKeyUp={handleKeyUp}
        onTouchStart={handleTouchStart}
        onTouchMove={handleTouchMove}
        onTouchEnd={handleTouchEnd}
        onTouchCancel={handleTouchEnd}
      >
        <div
          className="relative"
          style={{
            width: fit.width > 0 ? fit.width : "100%",
            height: fit.height > 0 ? fit.height : "100%",
            transform: `translate(${view.offsetX}px, ${view.offsetY}px) scale(${view.scale})`,
            transformOrigin: "0 0",
          }}
        >
          <video
            ref={attachVideo}
            className="h-full w-full object-contain"
            autoPlay
            playsInline
            muted
            onLoadedMetadata={handleLoadedMetadata}
          />
        </div>

        {/* Floating modifier keys */}
        {isController ? (
          <div className="absolute left-3 top-3 flex items-center gap-1 rounded-lg bg-background/80 p-1 shadow">
            {(["ctrl", "alt", "shift", "meta"] as InputMod[]).map((mod) => (
              <button
                key={mod}
                type="button"
                className={cn(
                  "rounded px-2 py-1 text-xs font-medium uppercase",
                  stickyMods.includes(mod) ? "bg-primary text-primary-foreground" : "bg-background/60 text-foreground",
                )}
                onClick={() => {
                  stickyModsRef.current = stickyModsRef.current.includes(mod)
                    ? stickyModsRef.current.filter((item) => item !== mod)
                    : [...stickyModsRef.current, mod]
                  setStickyMods(stickyModsRef.current)
                }}
              >
                {mod === "meta" ? "win" : mod}
              </button>
            ))}
          </div>
        ) : null}

        {/* Drag-lock indicator */}
        {dragLock ? (
          <div className="absolute bottom-3 left-1/2 -translate-x-1/2 rounded-full bg-background/85 px-3 py-1 text-xs shadow">
            {t("dragLockOn")}
          </div>
        ) : null}

        {/* Stats panel */}
        {statsOpen ? (
          <div className="absolute right-3 top-3 rounded-lg bg-background/85 p-3 text-xs shadow">
            <StatsTable
              stats={streamStats}
              onSelectQuality={(quality) => {
                updateQuality(quality)
                requestKeyframe()
              }}
            />
          </div>
        ) : null}

        {/* Connection overlays */}
        {connecting ? (
          <Overlay icon={<Loader2 className="size-6 animate-spin" />} text={t("connecting")} />
        ) : reconnecting ? (
          <Overlay icon={<Loader2 className="size-6 animate-spin" />} text={t("reconnecting")} />
        ) : mediaFailed ? (
          <Overlay
            icon={<Monitor className="size-6" />}
            text={t("failedTitle")}
            action={
              <Button size="sm" onClick={reconnectMedia}>
                <RefreshCw />
                {t("retry")}
              </Button>
            }
          />
        ) : null}

        {/* Weak network banner */}
        {weakNetwork && !connecting && !reconnecting ? (
          <div className="absolute bottom-3 right-3 rounded-full bg-amber-500/90 px-3 py-1 text-xs text-amber-950 shadow">
            {t("weakNetwork")}
          </div>
        ) : null}
      </div>

      {/* Soft keyboard */}
      {keyboardOpen ? (
        <div className="flex items-center gap-2 border-t border-border px-3 py-2">
          <Input
            value={softText}
            onChange={(event) => setSoftText(event.currentTarget.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.nativeEvent.isComposing) {
                event.preventDefault()
                sendSoftText()
              }
            }}
            placeholder={t("softKeyboardPlaceholder")}
            aria-label={t("softKeyboardPlaceholder")}
            className="flex-1"
          />
          <Button onClick={sendSoftText} disabled={softText.length === 0}>{t("send")}</Button>
        </div>
      ) : null}

      {/* Gesture hint */}
      <div className="border-t border-border px-3 py-1.5 text-[11px] text-muted-foreground">
        {t("gestureHint")}
      </div>
    </div>
  )
}

// ─── Small pieces ───────────────────────────────────────────────────────────

function ExitButton({ onExit }: { onExit: () => void }) {
  const t = useTranslations("remoteDesktop")
  return (
    <Button variant="outline" onClick={onExit}>
      <ChevronLeft />
      {t("exit")}
    </Button>
  )
}

function CenterStatus({
  icon,
  title,
  text,
  action,
  footer,
}: {
  icon: React.ReactNode
  title?: string
  text: string
  action?: React.ReactNode
  footer?: React.ReactNode
}) {
  return (
    <div className="flex h-full w-full flex-col items-center justify-center gap-3 bg-background px-6 text-center">
      {icon}
      {title ? <h2 className="text-lg font-semibold">{title}</h2> : null}
      <p className="max-w-md text-sm text-muted-foreground">{text}</p>
      {action}
      {footer}
    </div>
  )
}

function Overlay({ icon, text, action }: { icon: React.ReactNode; text: string; action?: React.ReactNode }) {
  return (
    <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 bg-black/60 text-white">
      {icon}
      <p className="text-sm">{text}</p>
      {action}
    </div>
  )
}

function StatsTable({
  stats,
  onSelectQuality,
}: {
  stats: RemoteDesktopStreamStats | null
  onSelectQuality: (quality: { maxFps: number; maxWidth?: number }) => void
}) {
  const t = useTranslations("remoteDesktop")
  const rows: Array<[string, string]> = [
    [t("statsFps"), stats ? stats.fps.toFixed(1) : "—"],
    [t("statsBitrate"), stats ? `${stats.bitrateKbps} kbps` : "—"],
    [t("statsRtt"), stats ? `${stats.rttMs} ms` : "—"],
    [t("statsLoss"), stats ? `${stats.packetLossPct}%` : "—"],
    [t("statsCodec"), stats?.codec || "—"],
    [t("statsResolution"), stats ? `${stats.width}×${stats.height}` : "—"],
  ]
  return (
    <div>
      <table className="min-w-40">
        <tbody>
          {rows.map(([label, value]) => (
            <tr key={label}>
              <td className="pr-4 text-muted-foreground">{label}</td>
              <td className="text-right tabular-nums">{value}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="mt-2 flex gap-1">
        <Button size="sm" variant="outline" onClick={() => onSelectQuality({ maxFps: 15 })}>
          {t("qualitySmooth")}
        </Button>
        <Button size="sm" variant="outline" onClick={() => onSelectQuality({ maxFps: 30 })}>
          {t("qualitySharp")}
        </Button>
      </div>
    </div>
  )
}

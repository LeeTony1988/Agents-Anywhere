//! Host state machine: session lifecycle, control lease, quality, input dispatch.

use std::collections::BTreeSet;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

use log::{info, warn};

use crate::capture::{new_capture_source, CaptureError, CaptureSource, Frame};
use crate::config::{Quality, QualityPatch, DEFAULT_MAX_WIDTH};
use crate::input::{InputEvent, InputSink};
use crate::protocol::{
    event, response_err, response_ok, EventKind, HostControlMessage, Method, ERR_CAPTURE_FAILED,
    ERR_INVALID_PARAMS, ERR_NOT_CONTROLLER, ERR_RATE_LIMITED,
};
use crate::publish::{MediaGrant, Publisher, Stats};

#[cfg(windows)]
use crate::overlay::OverlayHandle;

/// Maximum number of input events the host applies per rolling second.
pub const MAX_INPUT_EVENTS_PER_SECOND: usize = 120;

/// LiveKit data topic of the reliable JSON input plane (`input-event.schema.json`).
const INPUT_TOPIC: &str = "aa.input";

/// LiveKit data topic of the lossy 12-byte pointer plane (`pointer-frame.md`).
const POINTER_TOPIC: &str = "aa.pointer";

/// How often a summary of the data-channel input is written to the log.
const CHANNEL_INPUT_LOG_INTERVAL: std::time::Duration = std::time::Duration::from_secs(1);

/// Why one data-channel packet was not injected.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ChannelInputDrop {
    /// The sender is not the granted controller (decisions D4).
    Identity,
    /// Unknown topic, or a payload that does not match its wire format.
    Malformed,
    /// The rolling [`MAX_INPUT_EVENTS_PER_SECOND`] budget is exhausted.
    Throttled,
    /// A lossy pointer frame older than the last one already applied.
    Stale,
    /// The input sink refused the event.
    Rejected,
}

/// Counters for [`Host::apply_channel_input`].
///
/// The data plane is the only input path a client uses, and every rejection used to be a
/// silent `continue`. These counters turn "the picture works but nothing answers" into a
/// log line that says whether packets arrived and why they were dropped.
#[derive(Debug, Default)]
struct ChannelInputStats {
    received: u64,
    applied: u64,
    identity_dropped: u64,
    malformed_dropped: u64,
    throttled_dropped: u64,
    stale_dropped: u64,
    rejected_dropped: u64,
    first_packet_logged: bool,
    identity_warned: bool,
    last_summary: Option<std::time::Instant>,
}

impl ChannelInputStats {
    fn record(&mut self, drop: ChannelInputDrop) {
        match drop {
            ChannelInputDrop::Identity => self.identity_dropped += 1,
            ChannelInputDrop::Malformed => self.malformed_dropped += 1,
            ChannelInputDrop::Throttled => self.throttled_dropped += 1,
            ChannelInputDrop::Stale => self.stale_dropped += 1,
            ChannelInputDrop::Rejected => self.rejected_dropped += 1,
        }
    }

    fn dropped(&self) -> u64 {
        self.identity_dropped
            + self.malformed_dropped
            + self.throttled_dropped
            + self.stale_dropped
            + self.rejected_dropped
    }
}

/// Host lifecycle states.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HostState {
    Idle,
    Starting,
    Running,
    Stopping,
    Crashed,
}

impl HostState {
    pub fn as_str(self) -> &'static str {
        match self {
            HostState::Idle => "idle",
            HostState::Starting => "starting",
            HostState::Running => "running",
            HostState::Stopping => "stopping",
            HostState::Crashed => "crashed",
        }
    }
}

/// How the output frames/events are delivered (stdio in production, a buffer in tests).
pub trait EventSink: Send {
    fn emit(&mut self, msg: HostControlMessage);
}

/// In-memory sink for tests.
#[derive(Debug, Default)]
pub struct VecEventSink {
    pub frames: Vec<HostControlMessage>,
}

impl EventSink for VecEventSink {
    fn emit(&mut self, msg: HostControlMessage) {
        self.frames.push(msg);
    }
}

/// The remote-desktop host.
pub struct Host {
    pub state: HostState,
    pub session_id: Option<String>,
    pub display_id: String,
    pub mode: String,
    pub quality: Quality,
    /// Full controller identity (`viewer:pt_...`); input is rejected when the
    /// caller does not match (decisions D4).
    pub controller_identity: Option<String>,
    /// Bumped on every `host.controlGrant`, so a caller can tell two grants apart. The
    /// data plane cannot carry it (the wire format has no epoch field), so input is
    /// authorised by identity alone; the epoch is reported, not enforced.
    pub control_epoch: u64,
    pressed_keys: BTreeSet<String>,
    pressed_buttons: BTreeSet<String>,
    pub consent_required: bool,
    capture: Box<dyn CaptureSource>,
    publisher: Arc<Mutex<Box<dyn Publisher>>>,
    stream_stop: Option<Arc<AtomicBool>>,
    stream_thread: Option<JoinHandle<()>>,
    /// Newest frame the stream thread published; screenshots reuse it so the host
    /// never opens a second WGC/DXGI session on the same output.
    last_frame: Arc<Mutex<Option<Frame>>>,
    /// Timestamps of recently applied input events, for the 120 events/s budget.
    input_window: std::collections::VecDeque<std::time::Instant>,
    /// Highest lossy pointer sequence applied; older frames carry stale button state.
    last_pointer_seq: Option<u32>,
    /// Counters of the media data-channel input plane; see [`Host::apply_channel_input`].
    channel_input: ChannelInputStats,
    #[cfg(windows)]
    overlay: Option<OverlayHandle>,
    sink: Box<dyn InputSink>,
    events: Box<dyn EventSink>,
}

impl Host {
    /// Production constructor: platform capture, stub/livekit publisher, platform input sink.
    pub fn new(
        config: &crate::config::HostConfig,
        capture: Box<dyn CaptureSource>,
        publisher: Box<dyn Publisher>,
        sink: Box<dyn InputSink>,
        events: Box<dyn EventSink>,
    ) -> Self {
        Self {
            state: HostState::Idle,
            session_id: None,
            display_id: config.display.clone(),
            mode: "view".to_string(),
            quality: config.quality,
            controller_identity: None,
            control_epoch: 0,
            pressed_keys: BTreeSet::new(),
            pressed_buttons: BTreeSet::new(),
            consent_required: false,
            capture,
            publisher: Arc::new(Mutex::new(publisher)),
            stream_stop: None,
            stream_thread: None,
            last_frame: Arc::new(Mutex::new(None)),
            input_window: std::collections::VecDeque::new(),
            last_pointer_seq: None,
            channel_input: ChannelInputStats::default(),
            #[cfg(windows)]
            overlay: None,
            sink,
            events,
        }
    }

    /// Dispatch one request to the matching method. Always returns a Response frame.
    pub fn handle_request(
        &mut self,
        id: u64,
        method: Method,
        params: &serde_json::Value,
    ) -> HostControlMessage {
        match method {
            Method::HostInfo => self.handle_info(id),
            Method::HostStart => self.handle_start(id, params),
            Method::HostStop => self.handle_stop(id),
            Method::HostUpdateQuality => self.handle_update_quality(id, params),
            Method::HostControlGrant => self.handle_control_grant(id, params),
            Method::HostRequestKeyframe => self.handle_request_keyframe(id),
            Method::HostReleaseAll => self.handle_release_all(id),
            Method::HostScreenshot => self.handle_screenshot(id, params),
            Method::HostInput => self.handle_input(id, params),
            Method::HostPing => response_ok(id, serde_json::json!({"pong": true})),
        }
    }

    /// Emits a `stats` event for the running session, if any.
    ///
    /// The host main loop calls this on a timer; without it the connector has no
    /// source for `remote_desktop.stats.updated` and clients cannot show the
    /// resolution/bitrate they are actually receiving.
    pub fn emit_stats(&mut self) {
        let Some(session_id) = self.session_id.clone() else {
            return;
        };
        if self.state != HostState::Running {
            return;
        }
        let stats = self
            .publisher
            .lock()
            .map(|publisher| publisher.stats())
            .unwrap_or_default();
        log::debug!(
            "emitting stats session={} fps={} bitrateKbps={} size={}x{}",
            session_id,
            stats.fps,
            stats.bitrate_kbps,
            stats.width,
            stats.height
        );
        self.events.emit(stats_event(&session_id, &stats));
    }

    fn handle_info(&mut self, id: u64) -> HostControlMessage {
        let displays: Vec<serde_json::Value> = self
            .capture
            .displays()
            .iter()
            .map(|d| d.to_json())
            .collect();
        let (platform, supported, permissions) = platform_facts();
        let result = serde_json::json!({
            "platform": platform,
            "arch": arch_str(),
            "hostVersion": env!("CARGO_PKG_VERSION"),
            "pid": std::process::id(),
            "capabilities": {
                "supported": supported,
                "codecs": ["vp8"],
                "input": {
                    "pointer": supported,
                    "keyboard": supported,
                    "touch": false,
                    "clipboard": false
                },
                "permissions": permissions,
                "displays": displays,
                "quality": self.quality
            },
            "displays": displays,
            "input": {
                "pointer": supported,
                "keyboard": supported,
                "touch": false,
                "clipboard": false
            },
            "permissions": permissions
        });
        response_ok(id, result)
    }

    fn handle_start(&mut self, id: u64, params: &serde_json::Value) -> HostControlMessage {
        let session_id = match params.get("sessionId").and_then(serde_json::Value::as_str) {
            Some(s) if !s.is_empty() => s.to_string(),
            _ => {
                return response_err(id, ERR_INVALID_PARAMS, "params.sessionId is required");
            }
        };
        if let Some(display_id) = params.get("displayId").and_then(serde_json::Value::as_str) {
            self.display_id = display_id.to_string();
        }
        if let Some(mode) = params.get("mode").and_then(serde_json::Value::as_str) {
            self.mode = mode.to_string();
        }
        if let Some(quality) = params.get("quality") {
            match serde_json::from_value::<QualityPatch>(quality.clone()) {
                Ok(patch) => self.quality = patch.apply(self.quality),
                Err(_) => return response_err(id, ERR_INVALID_PARAMS, "invalid quality params"),
            }
        }
        if let Some(consent) = params
            .get("requireConsent")
            .and_then(serde_json::Value::as_bool)
        {
            self.consent_required = consent;
        }
        // `deadlineMs` is the caller's budget for this start (1..=300000 per the
        // schema). The Connector enforces it by cancelling the start and sending
        // `host.stop`, so the host only records it for the log; a start that
        // arrives after the caller gave up is stopped like any other.
        if let Some(deadline) = params.get("deadlineMs").and_then(serde_json::Value::as_u64) {
            info!("host.start deadlineMs={deadline} (the Connector cancels on overrun)");
        }

        // Idempotent: same session already running -> accepted, no-op.
        if self.state == HostState::Running && self.session_id.as_deref() == Some(&session_id) {
            return response_ok(id, serde_json::json!({"accepted": true}));
        }

        // Panic-release: anything stuck from a previous session/crash is let go first.
        self.release_all_internal();
        // The viewer's lossy pointer sequence starts over with each session.
        self.last_pointer_seq = None;

        self.state = HostState::Starting;
        self.emit_state(&session_id, "starting");

        let displays = self.capture.displays();
        let requested = self.display_id.clone();
        let display = displays
            .iter()
            .find(|display| display.id == requested)
            .cloned()
            .or_else(|| {
                // "0" (and an empty id) mean "the first display": the contract
                // examples and the Connector's default use "0", while Windows
                // enumerates `\\.\DISPLAY1` as id "1".
                if requested.is_empty() || requested == "0" {
                    displays.first().cloned()
                } else {
                    None
                }
            });
        if let Some(resolved) = &display {
            if resolved.id != self.display_id {
                info!("display {requested:?} resolved to {:?}", resolved.id);
                self.display_id = resolved.id.clone();
            }
        }
        let (stream_width, stream_height) = Self::stream_dimensions(display.as_ref(), self.quality);
        // Only one WGC/DXGI session per output is allowed in a process, so the
        // screenshot source stays closed whenever the stream will own the capture.
        let has_media = params
            .get("media")
            .map(|value| !value.is_null())
            .unwrap_or(false);
        if !has_media {
            if let Err(e) = self.capture.start(&self.display_id) {
                self.state = HostState::Crashed;
                self.emit_error(&session_id, ERR_CAPTURE_FAILED, &e.to_string());
                return response_err(id, ERR_CAPTURE_FAILED, e.to_string());
            }
        } else {
        }
        // Normalized pointer coordinates belong to the captured display, not
        // necessarily the primary one.
        self.sink.set_display(display.as_ref());

        let media: Option<MediaGrant> = params
            .get("media")
            .and_then(|m| serde_json::from_value(m.clone()).ok());
        match media {
            Some(grant) => {
                let quality = self.quality;
                let width = stream_width;
                let height = stream_height;
                self.publisher
                    .lock()
                    .unwrap()
                    .start(&session_id, &grant, &quality, width, height);
            }
            None => {
                // Media grant is optional in the skeleton (e.g. consent-first flows ship
                // the grant later); publishing starts without a room connection.
                info!("host.start without media grant; publishing deferred");
            }
        }

        self.session_id = Some(session_id.clone());
        self.start_stream();
        #[cfg(windows)]
        {
            self.overlay = OverlayHandle::show();
            if self.overlay.is_none() {
                warn!("sharing overlay could not be shown");
            }
        }
        self.state = HostState::Running;
        self.emit_state(&session_id, "running");
        response_ok(id, serde_json::json!({"accepted": true}))
    }

    fn handle_stop(&mut self, id: u64) -> HostControlMessage {
        // Idempotent: stopping when not running is a no-op that still reports stopped.
        let session_id = self.session_id.clone();
        if self.state == HostState::Running || self.state == HostState::Starting {
            self.state = HostState::Stopping;
            if let Some(sid) = &session_id {
                self.emit_state(sid, "stopping");
            }
            self.hide_overlay();
            self.stop_stream();
            self.publisher.lock().unwrap().stop();
            self.capture.stop();
        }
        self.release_all_internal();
        self.last_pointer_seq = None;
        self.state = HostState::Idle;
        self.session_id = None;
        if let Some(sid) = &session_id {
            self.emit_state(sid, "stopped");
        }
        response_ok(id, serde_json::json!({"stopped": true}))
    }

    /// Capture size for [`quality`] on `display`; shared by start and runtime updates.
    fn stream_dimensions(
        display: Option<&crate::capture::DisplayInfo>,
        quality: Quality,
    ) -> (u32, u32) {
        let width = display
            .map(|display| display.width.min(quality.max_width))
            .unwrap_or(quality.max_width);
        let height = display
            .map(|display| {
                (display.height as f64 * (width as f64 / display.width.max(1) as f64))
                    .round()
                    .max(1.0) as u32
            })
            .unwrap_or(width * 9 / 16)
            .max(1);
        (width, height)
    }

    fn handle_update_quality(&mut self, id: u64, params: &serde_json::Value) -> HostControlMessage {
        let Ok(patch) = serde_json::from_value::<QualityPatch>(params.clone()) else {
            return response_err(id, ERR_INVALID_PARAMS, "invalid quality params");
        };
        // Per contract: out-of-range values are clamped, not rejected.
        let previous = self.quality;
        let q = patch.apply(previous);
        let changed = q != previous;
        self.quality = q;
        if !changed {
            warn!("host.updateQuality produced no change (values already clamped/equal)");
        }
        if self.state == HostState::Running {
            let displays = self.capture.displays();
            let display = displays
                .iter()
                .find(|display| display.id == self.display_id);
            let previous_size = Self::stream_dimensions(display, previous);
            let (width, height) = Self::stream_dimensions(display, q);
            // maxWidth is a capture-side setting: a new source is the only way to change
            // the encoded size. Bitrate/fps go through the publisher, which re-publishes
            // the track with the new encoder targets. Compare the actual capture size so
            // raising maxWidth above the display width does not needlessly restart it.
            if (width, height) != previous_size {
                self.start_stream();
            }
            self.publisher
                .lock()
                .unwrap()
                .update_quality(&q, width, height);
        }
        response_ok(id, serde_json::json!({"applied": true}))
    }

    fn handle_control_grant(&mut self, id: u64, params: &serde_json::Value) -> HostControlMessage {
        let Some(identity) = params.get("identity").and_then(serde_json::Value::as_str) else {
            return response_err(id, ERR_INVALID_PARAMS, "params.identity is required");
        };
        let next = if identity.is_empty() {
            None
        } else {
            Some(identity.to_string())
        };
        // A release revokes the SFU publish permission at the same time, so the
        // client's final `release_all` can be dropped before it reaches this host.
        // Release here as well: otherwise a mouse button held during a drag stays
        // down on Windows and every later click is consumed as a drag.
        if self.controller_identity != next {
            self.release_all_internal();
        }
        self.controller_identity = next;
        self.control_epoch += 1;
        // A new controller has its own pointer sequence starting at zero; the old
        // controller's high-water mark must not make every new frame look stale.
        self.last_pointer_seq = None;
        info!(
            "control granted to {} (epoch {})",
            if identity.is_empty() {
                "none"
            } else {
                identity
            },
            self.control_epoch
        );
        response_ok(id, serde_json::json!({"applied": true}))
    }

    fn handle_request_keyframe(&mut self, id: u64) -> HostControlMessage {
        let requested = self.publisher.lock().unwrap().request_keyframe();
        response_ok(id, serde_json::json!({"requested": requested}))
    }

    fn handle_release_all(&mut self, id: u64) -> HostControlMessage {
        let (keys, buttons) = self.release_all_internal();
        response_ok(
            id,
            serde_json::json!({"releasedKeys": keys, "releasedButtons": buttons}),
        )
    }

    fn handle_screenshot(&mut self, id: u64, params: &serde_json::Value) -> HostControlMessage {
        let max_width = params
            .get("maxWidth")
            .and_then(serde_json::Value::as_u64)
            .map(|v| v as u32)
            .unwrap_or(DEFAULT_MAX_WIDTH);
        let format = ScreenshotFormat::from_request(
            params.get("format").and_then(serde_json::Value::as_str),
        );
        let quality = params
            .get("quality")
            .and_then(serde_json::Value::as_u64)
            .map(|value| value.clamp(1, 100) as u8)
            .unwrap_or(DEFAULT_JPEG_QUALITY);

        // Display selection: explicit param wins, else the running session's display,
        // else the first enumerated display.
        let display_id = params
            .get("displayId")
            .and_then(serde_json::Value::as_str)
            .map(str::to_string)
            .or_else(|| self.session_id.as_ref().map(|_| self.display_id.clone()))
            .or_else(|| self.capture.displays().first().map(|d| d.id.clone()));
        let Some(display_id) = display_id else {
            return response_err(id, ERR_CAPTURE_FAILED, "no displays available");
        };

        let result = self.capture_screenshot(&display_id, max_width, format, quality);
        match result {
            Ok(value) => response_ok(id, value),
            Err(CaptureError::Unsupported) => response_err(
                id,
                ERR_CAPTURE_FAILED,
                "capture unsupported on this platform",
            ),
            Err(e) => response_err(id, ERR_CAPTURE_FAILED, e.to_string()),
        }
    }

    fn handle_input(&mut self, id: u64, params: &serde_json::Value) -> HostControlMessage {
        // Identity check: when the caller supplies an identity it must match the
        // granted controller (decisions D4). Missing identity is rejected while a
        // controller lease is active.
        if let Some(identity) = params.get("identity").and_then(serde_json::Value::as_str) {
            if self.controller_identity.as_deref() != Some(identity) {
                return response_err(id, ERR_NOT_CONTROLLER, "identity is not the controller");
            }
        } else if self.controller_identity.is_some() {
            return response_err(id, ERR_NOT_CONTROLLER, "identity is required");
        }

        let Some(events) = params.get("events").and_then(serde_json::Value::as_array) else {
            return response_err(id, ERR_INVALID_PARAMS, "params.events must be an array");
        };

        let mut applied_seq: u64 = 0;
        for raw in events {
            if !self.allow_input_at(std::time::Instant::now()) {
                return response_err(
                    id,
                    ERR_RATE_LIMITED,
                    format!("input rate limit exceeded (max {MAX_INPUT_EVENTS_PER_SECOND}/s)"),
                );
            }
            let event = match InputEvent::from_json(raw) {
                Ok(ev) => ev,
                Err(e) => return response_err(id, ERR_INVALID_PARAMS, e.to_string()),
            };
            self.track_pressed(&event);
            if let Err(e) = self.sink.apply(&event) {
                return response_err(id, ERR_INVALID_PARAMS, e.to_string());
            }
            if let Some(seq) = event_seq(&event) {
                applied_seq = applied_seq.max(seq as u64);
            }
        }
        response_ok(id, serde_json::json!({"appliedSeq": applied_seq}))
    }

    /// Applies input that arrived over the media data channel (`aa.input`).
    ///
    /// The primary input plane is the LiveKit data channel: low latency and no Server
    /// round-trip. Only the granted controller may use it, which is checked against the
    /// sender's participant identity.
    ///
    /// Nothing here may fail silently: a viewer whose picture works but whose taps do
    /// nothing has no other way to see that its packets arrived, from which identity, or
    /// why they were dropped.
    pub fn apply_channel_input(&mut self) {
        let packets = match self.publisher.lock() {
            Ok(mut publisher) => publisher.take_data(),
            Err(_) => return,
        };
        if packets.is_empty() {
            return;
        }
        for packet in packets {
            self.channel_input.received += 1;
            if !self.channel_input.first_packet_logged {
                self.channel_input.first_packet_logged = true;
                info!(
                    "data-channel input arrived: topic={} from={} bytes={} controller={:?}",
                    packet.topic,
                    packet.from,
                    packet.payload.len(),
                    self.controller_identity,
                );
            }
            match self.apply_channel_packet(&packet) {
                Ok(()) => self.channel_input.applied += 1,
                Err(reason) => self.channel_input.record(reason),
            }
        }
        self.log_channel_input();
    }

    /// Applies one data-channel packet; `Err` names why it was not injected.
    fn apply_channel_packet(
        &mut self,
        packet: &crate::publish::ReceivedData,
    ) -> Result<(), ChannelInputDrop> {
        // Identity gate: a packet from anyone but the granted controller never reaches
        // the OS, whichever topic it claims (decisions D4). No controller means no
        // input at all: without this, a pointer frame that was already in flight when
        // control was released could re-press the left button on the host desktop.
        let Some(expected) = self.controller_identity.as_deref() else {
            return Err(ChannelInputDrop::Identity);
        };
        if expected.is_empty() || packet.from != expected {
            if !self.channel_input.identity_warned {
                self.channel_input.identity_warned = true;
                warn!(
                    "data-channel input dropped: sender={:?} is not the controller {:?}",
                    packet.from, expected
                );
            }
            return Err(ChannelInputDrop::Identity);
        }
        match packet.topic.as_str() {
            POINTER_TOPIC => {
                // Lossy high-rate pointer plane: absolute position plus the sender's
                // button bitmask. The bitmask is applied here and not only on `aa.input`:
                // the two planes have different reliability, so a move can overtake the
                // reliable button event and the drag would start after the pointer moved.
                let frame = crate::pointer::decode_pointer_frame(&packet.payload)
                    .map_err(|_| ChannelInputDrop::Malformed)?;
                // Button transitions are safety-critical: a release that exceeds the
                // 120/s motion budget must still reach the OS or the left button sticks.
                let button_transition = frame.flags & crate::pointer::FLAG_BUTTONS_CHANGED != 0;
                if !button_transition && !self.allow_input_at(std::time::Instant::now()) {
                    return Err(ChannelInputDrop::Throttled);
                }
                self.apply_pointer_frame(frame)
            }
            INPUT_TOPIC => {
                let value = serde_json::from_slice::<serde_json::Value>(&packet.payload)
                    .map_err(|_| ChannelInputDrop::Malformed)?;
                let event =
                    InputEvent::from_json(&value).map_err(|_| ChannelInputDrop::Malformed)?;
                // A pause, lock screen or control handoff sends release_all on the
                // reliable channel (without a pointer frame). Release the host's real
                // pressed set here, otherwise a drag started before the pause stays
                // stuck until the lease expires. This is outside the motion budget:
                // after a fast drag the window is full precisely when the release matters.
                if let InputEvent::ReleaseAll { reason } = &event {
                    let (keys, buttons) = self.release_all_internal();
                    info!(
                        "client release_all: reason={reason} keys={} buttons={}",
                        keys.len(),
                        buttons.len()
                    );
                    return Ok(());
                }
                // Pointer button events are critical and rare; never let the motion
                // budget eat a down/up. Moves, keys and wheel keep the normal budget.
                let pointer_button = matches!(
                    &event,
                    InputEvent::Pointer {
                        action: crate::input::PointerAction::Down | crate::input::PointerAction::Up,
                        ..
                    }
                );
                if !pointer_button && !self.allow_input_at(std::time::Instant::now()) {
                    return Err(ChannelInputDrop::Throttled);
                }
                // The pointer plane may already have applied this press/release from its
                // button bitmask; do not inject the same OS transition twice.
                if let InputEvent::Pointer {
                    action,
                    button: Some(button),
                    ..
                } = &event
                {
                    let name = pointer_button_name(*button);
                    let held = self.pressed_buttons.contains(name);
                    if (*action == crate::input::PointerAction::Down && held)
                        || (*action == crate::input::PointerAction::Up && !held)
                    {
                        return Ok(());
                    }
                }
                self.track_pressed(&event);
                self.sink
                    .apply(&event)
                    .map_err(|_| ChannelInputDrop::Rejected)
            }
            _ => Err(ChannelInputDrop::Malformed),
        }
    }

    /// Applies one lossy pointer frame: newest position first, then any button transitions
    /// encoded in its bitmask. The sender numbers frames, so a reordered/stale frame is
    /// ignored instead of resurrecting an old button state.
    fn apply_pointer_frame(
        &mut self,
        frame: crate::pointer::PointerFrame,
    ) -> Result<(), ChannelInputDrop> {
        use crate::input::{PointerAction, PointerButton};

        if let Some(last) = self.last_pointer_seq {
            let age = frame.seq.wrapping_sub(last);
            if age == 0 || age > u32::MAX / 2 {
                return Err(ChannelInputDrop::Stale);
            }
        }
        self.last_pointer_seq = Some(frame.seq);
        let x = frame.x as f64 / u16::MAX as f64;
        let y = frame.y as f64 / u16::MAX as f64;
        for (bit, button) in [
            (crate::pointer::BUTTON_LEFT, PointerButton::Left),
            (crate::pointer::BUTTON_RIGHT, PointerButton::Right),
            (crate::pointer::BUTTON_MIDDLE, PointerButton::Middle),
        ] {
            let name = pointer_button_name(button);
            let desired = frame.buttons & bit != 0;
            let held = self.pressed_buttons.contains(name);
            if desired == held {
                continue;
            }
            let action = if desired {
                PointerAction::Down
            } else {
                PointerAction::Up
            };
            let event = InputEvent::Pointer {
                seq: frame.seq,
                action,
                x,
                y,
                button: Some(button),
                display: 0,
            };
            self.sink
                .apply(&event)
                .map_err(|_| ChannelInputDrop::Rejected)?;
            if desired {
                self.pressed_buttons.insert(name.to_string());
            } else {
                self.pressed_buttons.remove(name);
            }
        }
        let moved = InputEvent::Pointer {
            seq: frame.seq,
            action: PointerAction::Move,
            x,
            y,
            button: None,
            display: 0,
        };
        self.sink
            .apply(&moved)
            .map_err(|_| ChannelInputDrop::Rejected)
    }

    /// Writes the data-channel input counters at most once per
    /// [`CHANNEL_INPUT_LOG_INTERVAL`], so a 60 fps drag does not flood the log.
    fn log_channel_input(&mut self) {
        let now = std::time::Instant::now();
        if let Some(last) = self.channel_input.last_summary {
            if now.duration_since(last) < CHANNEL_INPUT_LOG_INTERVAL {
                return;
            }
        }
        self.channel_input.last_summary = Some(now);
        info!(
            "data-channel input: received={} applied={} dropped={} (identity={}, malformed={}, throttled={}, stale={}, rejected={}) controller={:?}",
            self.channel_input.received,
            self.channel_input.applied,
            self.channel_input.dropped(),
            self.channel_input.identity_dropped,
            self.channel_input.malformed_dropped,
            self.channel_input.throttled_dropped,
            self.channel_input.stale_dropped,
            self.channel_input.rejected_dropped,
            self.controller_identity,
        );
    }

    /// Sliding one-second budget for applied input events (decisions §2.4).
    ///
    /// Pointer motion normally travels on the lossy `aa.pointer` channel, so a
    /// client that floods `host.input` is either misbehaving or misconfigured.
    /// Rejecting the batch is safer than injecting thousands of events.
    fn allow_input_at(&mut self, now: std::time::Instant) -> bool {
        while let Some(oldest) = self.input_window.front() {
            if now.duration_since(*oldest) < std::time::Duration::from_secs(1) {
                break;
            }
            self.input_window.pop_front();
        }
        if self.input_window.len() >= MAX_INPUT_EVENTS_PER_SECOND {
            return false;
        }
        self.input_window.push_back(now);
        true
    }

    /// Pull frames from a capture source and hand them to the publisher.
    ///
    /// The stream owns the only capture session while it runs: Windows allows one
    /// WGC/DXGI duplication per output, so the screenshot path reuses the newest
    /// frame instead of opening a second one.
    fn start_stream(&mut self) {
        self.stop_stream();
        let mut capture = new_capture_source(self.quality);
        if let Err(error) = capture.start(&self.display_id) {
            warn!("stream capture did not start: {error}");
            return;
        }
        let stop = Arc::new(AtomicBool::new(false));
        let stop_flag = Arc::clone(&stop);
        let publisher = Arc::clone(&self.publisher);
        let last_frame = Arc::clone(&self.last_frame);
        self.stream_thread = Some(std::thread::spawn(move || {
            // WinRT capture needs a COM apartment on the creating thread.
            #[cfg(windows)]
            {
                use windows::Win32::System::Com::{CoInitializeEx, COINIT_APARTMENTTHREADED};
                // SAFETY: paired with the CoUninitialize at the end of this thread.
                unsafe {
                    let _ = CoInitializeEx(None, COINIT_APARTMENTTHREADED);
                }
            }
            let mut last_publish = std::time::Instant::now();
            while !stop_flag.load(Ordering::Relaxed) {
                match capture.next_frame() {
                    Ok(frame) => {
                        if let Ok(mut publisher) = publisher.lock() {
                            publisher.publish_frame(&frame);
                        }
                        if let Ok(mut slot) = last_frame.lock() {
                            *slot = Some(frame.clone());
                        }
                        last_publish = std::time::Instant::now();
                    }
                    Err(error) if error.to_string().contains("timed out") => {
                        // DXGI only yields a frame when the desktop changes; an idle
                        // screen is not an error. Re-publish the newest frame once a
                        // second so a viewer that joins later still gets a picture.
                        std::thread::sleep(std::time::Duration::from_millis(100));
                        if last_publish.elapsed() >= std::time::Duration::from_secs(1) {
                            if let Ok(slot) = last_frame.lock() {
                                if let Some(frame) = slot.as_ref() {
                                    if let Ok(mut publisher) = publisher.lock() {
                                        publisher.publish_frame(frame);
                                    }
                                }
                            }
                            last_publish = std::time::Instant::now();
                        }
                    }
                    Err(error) => {
                        warn!("stream capture stopped: {error}");
                        break;
                    }
                }
            }
            capture.stop();
            #[cfg(windows)]
            {
                use windows::Win32::System::Com::CoUninitialize;
                // SAFETY: matches the CoInitializeEx at the top of this thread.
                unsafe {
                    CoUninitialize();
                }
            }
        }));
        self.stream_stop = Some(stop);
    }

    fn stop_stream(&mut self) {
        if let Some(stop) = self.stream_stop.take() {
            stop.store(true, Ordering::Relaxed);
        }
        if let Some(handle) = self.stream_thread.take() {
            let _ = handle.join();
        }
    }

    fn hide_overlay(&mut self) {
        #[cfg(windows)]
        if let Some(mut overlay) = self.overlay.take() {
            overlay.stop();
        }
    }

    /// Full shutdown used on EOF / signals: release everything, stop media, idle out.
    pub fn shutdown(&mut self) {
        let session_id = self.session_id.clone();
        if self.state == HostState::Running || self.state == HostState::Starting {
            self.state = HostState::Stopping;
        }
        self.hide_overlay();
        self.stop_stream();
        self.publisher.lock().unwrap().stop();
        self.capture.stop();
        self.release_all_internal();
        self.last_pointer_seq = None;
        self.state = HostState::Idle;
        if let Some(sid) = session_id {
            self.emit_state(&sid, "stopped");
        }
    }

    /// `host.releaseAll` + internal paths (stop/EOF/crash): releases every pressed
    /// key and mouse button through the sink and clears the host-side pressed sets.
    fn release_all_internal(&mut self) -> (Vec<String>, Vec<String>) {
        let (sink_keys, sink_buttons) = self.sink.release_all();
        let mut keys: Vec<String> = self.pressed_keys.iter().cloned().collect();
        let mut buttons: Vec<String> = self.pressed_buttons.iter().cloned().collect();
        keys.extend(sink_keys);
        buttons.extend(sink_buttons);
        keys.sort();
        keys.dedup();
        buttons.sort();
        buttons.dedup();
        self.pressed_keys.clear();
        self.pressed_buttons.clear();
        (keys, buttons)
    }

    /// Keep the host-side pressed sets in sync with the event stream so release_all
    /// reports the right thing even if the sink drops unmapped keys.
    fn track_pressed(&mut self, event: &InputEvent) {
        use crate::input::{KeyAction, PointerAction};
        match event {
            InputEvent::Pointer {
                action,
                button: Some(b),
                ..
            } => {
                let name = pointer_button_name(*b);
                match action {
                    PointerAction::Down => {
                        self.pressed_buttons.insert(name.to_string());
                    }
                    PointerAction::Up => {
                        self.pressed_buttons.remove(name);
                    }
                    PointerAction::Move => {}
                }
            }
            InputEvent::Key { action, code, .. } => match action {
                KeyAction::Down => {
                    self.pressed_keys.insert(code.clone());
                }
                KeyAction::Up => {
                    self.pressed_keys.remove(code);
                }
            },
            _ => {}
        }
    }

    fn capture_screenshot(
        &mut self,
        display_id: &str,
        max_width: u32,
        format: ScreenshotFormat,
        quality: u8,
    ) -> Result<serde_json::Value, CaptureError> {
        // A live stream already owns the capture; reuse its newest frame instead of
        // opening a second WGC/DXGI session (Windows allows one per output).
        let cached = self
            .last_frame
            .lock()
            .ok()
            .and_then(|slot| slot.clone())
            .filter(|frame| frame.width > 0 && frame.height > 0);
        if let Some(frame) = cached {
            return screenshot_from_frame(frame, max_width, format, quality);
        }
        if self.state == HostState::Running {
            return Err(CaptureError::Failed(
                "screen capture is streaming; no frame buffered yet".to_string(),
            ));
        }
        // Single-frame capture path: try a fresh probe capture of the requested
        // display; fall back to draining one frame from the active capture source
        // when a session is already running.
        let frame = match one_shot_capture(self.capture.as_mut(), display_id) {
            Ok(f) => f,
            Err(e) if self.state == HostState::Running => {
                info!("probe capture failed ({e}); draining frame from active capture");
                self.capture.next_frame()?
            }
            Err(e) => return Err(e),
        };
        screenshot_from_frame(frame, max_width, format, quality)
    }

    fn emit_state(&mut self, session_id: &str, state: &str) {
        let msg = event(
            EventKind::State,
            serde_json::json!({"sessionId": session_id, "state": state}),
        );
        self.events.emit(msg);
    }

    fn emit_error(&mut self, session_id: &str, code: &str, message: &str) {
        let msg = event(
            EventKind::Error,
            serde_json::json!({
                "sessionId": session_id,
                "code": code,
                "message": message,
                "retryable": false
            }),
        );
        self.events.emit(msg);
    }
}

impl Drop for Host {
    fn drop(&mut self) {
        self.hide_overlay();
        self.stop_stream();
        self.publisher.lock().unwrap().stop();
        self.capture.stop();
    }
}

fn event_seq(event: &InputEvent) -> Option<u32> {
    match event {
        InputEvent::Pointer { seq, .. }
        | InputEvent::Wheel { seq, .. }
        | InputEvent::Key { seq, .. }
        | InputEvent::Text { seq, .. }
        | InputEvent::Clipboard { seq, .. } => Some(*seq),
        InputEvent::Ping { .. } | InputEvent::ReleaseAll { .. } => None,
    }
}

#[cfg(windows)]
fn platform_facts() -> (&'static str, bool, serde_json::Value) {
    (
        "windows",
        true,
        serde_json::json!({
            "screenRecording": "granted",
            "accessibility": "granted",
            "inputMonitoring": "granted"
        }),
    )
}

#[cfg(not(windows))]
fn platform_facts() -> (&'static str, bool, serde_json::Value) {
    (
        if cfg!(target_os = "macos") {
            "macos"
        } else {
            "linux"
        },
        false,
        serde_json::json!({
            "screenRecording": "unsupported",
            "accessibility": "unsupported",
            "inputMonitoring": "unsupported"
        }),
    )
}

#[cfg(target_arch = "x86_64")]
fn arch_str() -> &'static str {
    "x86_64"
}

#[cfg(target_arch = "aarch64")]
fn arch_str() -> &'static str {
    "aarch64"
}

#[cfg(not(any(target_arch = "x86_64", target_arch = "aarch64")))]
fn arch_str() -> &'static str {
    "unknown"
}

/// Standalone single-frame capture of `display_id`.
fn one_shot_capture(
    capture: &mut dyn CaptureSource,
    display_id: &str,
) -> Result<Frame, CaptureError> {
    capture.start(display_id)?;
    let frame = capture.next_frame();
    capture.stop();
    frame
}

const B64_ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

/// RFC 4648 standard base64 (no external crate).
fn base64_encode(data: &[u8]) -> String {
    let mut out = String::with_capacity(data.len().div_ceil(3) * 4);
    for chunk in data.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = *chunk.get(1).unwrap_or(&0) as u32;
        let b2 = *chunk.get(2).unwrap_or(&0) as u32;
        let n = (b0 << 16) | (b1 << 8) | b2;
        out.push(B64_ALPHABET[(n >> 18) as usize & 63] as char);
        out.push(B64_ALPHABET[(n >> 12) as usize & 63] as char);
        if chunk.len() > 1 {
            out.push(B64_ALPHABET[(n >> 6) as usize & 63] as char);
        } else {
            out.push('=');
        }
        if chunk.len() > 2 {
            out.push(B64_ALPHABET[n as usize & 63] as char);
        } else {
            out.push('=');
        }
    }
    out
}

/// Quality used when the request does not set one.
const DEFAULT_JPEG_QUALITY: u8 = 80;

/// Image format of a screenshot; the response tells the client what it got.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ScreenshotFormat {
    Png,
    Jpeg,
}

impl ScreenshotFormat {
    /// `format` is a request hint: anything unknown falls back to PNG, and the
    /// response's `format` field is authoritative.
    pub fn from_request(value: Option<&str>) -> Self {
        match value.unwrap_or("png").trim().to_ascii_lowercase().as_str() {
            "jpeg" | "jpg" => Self::Jpeg,
            _ => Self::Png,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Png => "png",
            Self::Jpeg => "jpeg",
        }
    }
}

/// Grayscale JPEG of the luma plane, downscaled by the same integer step as PNG.
///
/// Screenshots are dominated by payload size: the stored-block PNG below is
/// uncompressed, while JPEG is ~10x smaller for the same picture.
fn encode_jpeg(
    frame: &Frame,
    max_width: u32,
    quality: u8,
) -> Result<(String, u32, u32), CaptureError> {
    let step = (frame.width.max(1) / max_width.max(1)).max(1);
    let out_w = frame.width.div_ceil(step);
    let out_h = frame.height.div_ceil(step);
    let stride = frame.stride as usize;
    let y_plane = &frame.i420[..stride * frame.height as usize];
    let mut gray = Vec::with_capacity((out_w * out_h) as usize);
    for y in (0..frame.height).step_by(step as usize) {
        let row = &y_plane[y as usize * stride..];
        for x in (0..frame.width).step_by(step as usize) {
            gray.push(row[x as usize]);
        }
    }
    let width =
        u16::try_from(out_w).map_err(|_| CaptureError::Failed("screenshot too wide".into()))?;
    let height =
        u16::try_from(out_h).map_err(|_| CaptureError::Failed("screenshot too tall".into()))?;
    let mut out = Vec::new();
    jpeg_encoder::Encoder::new(&mut out, quality)
        .encode(&gray, width, height, jpeg_encoder::ColorType::Luma)
        .map_err(|error| CaptureError::Failed(format!("jpeg encode failed: {error}")))?;
    Ok((base64_encode(&out), out_w, out_h))
}

/// Minimal PNG encoder (8-bit grayscale, no compression beyond stored blocks) so a
/// screenshot can be returned without external crates. Downscales by integer step to
/// fit `max_width`.
fn encode_png(frame: &Frame, max_width: u32) -> Result<(String, u32, u32), CaptureError> {
    let step = (frame.width.max(1) / max_width.max(1)).max(1);
    let out_w = frame.width.div_ceil(step);
    let out_h = frame.height.div_ceil(step);
    let stride = frame.stride as usize;
    let y_plane = &frame.i420[..stride * frame.height as usize];
    let mut gray = Vec::with_capacity((out_w * out_h) as usize);
    for row in 0..out_h {
        for col in 0..out_w {
            let src = (row * step) as usize * stride + (col * step) as usize;
            gray.push(*y_plane.get(src).copied().as_ref().unwrap_or(&0));
        }
    }
    let png = build_gray_png(out_w, out_h, &gray);
    Ok((base64_encode(&png), out_w, out_h))
}

/// Build a valid PNG (signature + IHDR + IDAT with uncompressed DEFLATE blocks +
/// IEND) for an 8-bit grayscale image.
fn build_gray_png(width: u32, height: u32, gray: &[u8]) -> Vec<u8> {
    fn crc32(bytes: &[u8]) -> u32 {
        let mut crc: u32 = 0xFFFF_FFFF;
        for &b in bytes {
            crc ^= b as u32;
            for _ in 0..8 {
                let mask = 0u32.wrapping_sub(crc & 1);
                crc = (crc >> 1) ^ (0xEDB8_8320 & mask);
            }
        }
        !crc
    }

    /// Adler-32 checksum (RFC 1950), seeded with 1.
    fn adler32(bytes: &[u8]) -> u32 {
        const MOD: u32 = 65_521;
        let mut a: u32 = 1;
        let mut b: u32 = 0;
        for &byte in bytes {
            a = (a + byte as u32) % MOD;
            b = (b + a) % MOD;
        }
        (b << 16) | a
    }
    fn chunk(out: &mut Vec<u8>, name: &[u8; 4], data: &[u8]) {
        out.extend_from_slice(&(data.len() as u32).to_be_bytes());
        out.extend_from_slice(name);
        out.extend_from_slice(data);
        let mut crc_input = Vec::with_capacity(4 + data.len());
        crc_input.extend_from_slice(name);
        crc_input.extend_from_slice(data);
        out.extend_from_slice(&crc32(&crc_input).to_be_bytes());
    }

    let mut raw = Vec::with_capacity(((width + 1) * height) as usize);
    for row in 0..height {
        raw.push(0); // filter: none
        let start = (row * width) as usize;
        raw.extend_from_slice(&gray[start..start + width as usize]);
    }
    let mut zlib = Vec::with_capacity(raw.len() + 64);
    zlib.push(0x78);
    zlib.push(0x01);
    for block in raw.chunks(65_535) {
        let last = block.len() < 65_535;
        zlib.push(if last { 1 } else { 0 });
        zlib.extend_from_slice(&(block.len() as u16).to_le_bytes());
        zlib.extend_from_slice(&(!(block.len() as u16)).to_le_bytes());
        zlib.extend_from_slice(block);
    }
    let checksum = adler32(&raw);
    zlib.extend_from_slice(&checksum.to_be_bytes());

    let mut png = Vec::new();
    png.extend_from_slice(&[137, 80, 78, 71, 13, 10, 26, 10]);
    let mut ihdr = Vec::with_capacity(13);
    ihdr.extend_from_slice(&width.to_be_bytes());
    ihdr.extend_from_slice(&height.to_be_bytes());
    ihdr.extend_from_slice(&[8, 0, 0, 0, 0]); // 8-bit, grayscale, defaults
    chunk(&mut png, b"IHDR", &ihdr);
    chunk(&mut png, b"IDAT", &zlib);
    chunk(&mut png, b"IEND", &[]);
    png
}

/// Stable name of a pointer button in the host's pressed-button set and `release_all`.
fn pointer_button_name(button: crate::input::PointerButton) -> &'static str {
    match button {
        crate::input::PointerButton::Left => "left",
        crate::input::PointerButton::Right => "right",
        crate::input::PointerButton::Middle => "middle",
    }
}

/// Publisher stats exposed for the `stats` event.
pub fn stats_event(session_id: &str, stats: &Stats) -> HostControlMessage {
    event(
        EventKind::Stats,
        serde_json::json!({
            "sessionId": session_id,
            "fps": stats.fps,
            "bitrateKbps": stats.bitrate_kbps,
            "rttMs": stats.rtt_ms,
            "packetLossPct": stats.packet_loss_pct,
            "codec": stats.codec,
            "width": stats.width,
            "height": stats.height,
            "encoder": stats.encoder
        }),
    )
}

/// Encode one captured frame as PNG/JPEG and build the `host.screenshot` payload.
fn screenshot_from_frame(
    frame: Frame,
    max_width: u32,
    format: ScreenshotFormat,
    quality: u8,
) -> Result<serde_json::Value, CaptureError> {
    let (data, width, height) = match format {
        ScreenshotFormat::Png => encode_png(&frame, max_width)?,
        ScreenshotFormat::Jpeg => encode_jpeg(&frame, max_width, quality)?,
    };
    Ok(serde_json::json!({
        "dataBase64": data,
        "width": width,
        "height": height,
        "capturedAt": frame.captured_at,
        "format": format.as_str()
    }))
}

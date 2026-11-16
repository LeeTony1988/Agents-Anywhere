//! Host state-machine tests using fake capture/input sinks.

use aa_screen_host::capture::fake::FakeCaptureSource;
use aa_screen_host::capture::CaptureSource;
use aa_screen_host::config::{HostConfig, Quality, MAX_FPS, MIN_BITRATE_KBPS, MIN_WIDTH};
use aa_screen_host::host::{Host, HostState};
use aa_screen_host::input::fake::FakeInputSink;
use aa_screen_host::input::{InputEvent, InputSink, PointerAction, PointerButton};
use aa_screen_host::protocol::{
    response_ok, EventKind, HostControlMessage, Method, ERR_NOT_CONTROLLER,
};
use aa_screen_host::publish::stub::StubPublisher;

/// Shared event log behind the EventSink trait so tests can inspect emitted frames.
fn make_host2() -> (
    Host,
    std::sync::Arc<std::sync::Mutex<Vec<HostControlMessage>>>,
) {
    use std::sync::{Arc, Mutex};
    struct SharedSink(Arc<Mutex<Vec<HostControlMessage>>>);
    impl aa_screen_host::host::EventSink for SharedSink {
        fn emit(&mut self, msg: HostControlMessage) {
            self.0.lock().unwrap().push(msg);
        }
    }
    let log = Arc::new(Mutex::new(Vec::new()));
    let host = Host::new(
        &HostConfig::default(),
        Box::new(FakeCaptureSource::new(2, 640, 360)),
        Box::new(StubPublisher::new()),
        Box::new(FakeInputSink::new()),
        Box::new(SharedSink(log.clone())),
    );
    (host, log)
}

fn start_params(session_id: &str) -> serde_json::Value {
    serde_json::json!({
        "sessionId": session_id,
        "displayId": "0",
        "mode": "control",
        "quality": {"maxFps": 30, "maxBitrateKbps": 4000, "maxWidth": 1920}
    })
}

#[test]
fn start_is_idempotent_for_same_session() {
    let (mut host, log) = make_host2();
    let r1 = host.handle_request(1, Method::HostStart, &start_params("rds_1"));
    let r2 = host.handle_request(2, Method::HostStart, &start_params("rds_1"));
    assert!(serde_json::to_string(&r1)
        .unwrap()
        .contains("\"accepted\":true"));
    assert!(serde_json::to_string(&r2)
        .unwrap()
        .contains("\"accepted\":true"));
    assert_eq!(host.state, HostState::Running);
    assert_eq!(host.session_id.as_deref(), Some("rds_1"));
    // One running + one starting + one stopped-state transition set; start event for
    // the second call must not duplicate capture/publisher churn (verified by event log
    // containing exactly one "running" state event per start call that changed state).
    let running_events = log
        .lock()
        .unwrap()
        .iter()
        .filter(|m| {
            serde_json::to_string(m)
                .unwrap()
                .contains("\"event\":\"state\"")
                && serde_json::to_string(m)
                    .unwrap()
                    .contains("\"state\":\"running\"")
        })
        .count();
    assert_eq!(
        running_events, 1,
        "second identical start must not re-emit running"
    );
}

#[test]
fn stop_is_idempotent() {
    let (mut host, _log) = make_host2();
    host.handle_request(1, Method::HostStart, &start_params("rds_1"));
    let r1 = host.handle_request(2, Method::HostStop, &serde_json::json!({}));
    let r2 = host.handle_request(3, Method::HostStop, &serde_json::json!({}));
    assert!(serde_json::to_string(&r1)
        .unwrap()
        .contains("\"stopped\":true"));
    assert!(serde_json::to_string(&r2)
        .unwrap()
        .contains("\"stopped\":true"));
    assert_eq!(host.state, HostState::Idle);
    assert!(host.session_id.is_none());
}

#[test]
fn release_all_clears_pressed() {
    let (mut host, _log) = make_host2();
    host.handle_request(
        1,
        Method::HostControlGrant,
        &serde_json::json!({"identity": "viewer:pt_a"}),
    );
    let params = serde_json::json!({
        "identity": "viewer:pt_a",
        "events": [
            {"v":1,"seq":1,"t":"key","a":"down","code":"KeyA"},
            {"v":1,"seq":2,"t":"pointer","a":"down","b":"left","x":0.5,"y":0.5}
        ]
    });
    let applied = host.handle_request(2, Method::HostInput, &params);
    assert!(serde_json::to_string(&applied)
        .unwrap()
        .contains("\"appliedSeq\":2"));
    let released = host.handle_request(3, Method::HostReleaseAll, &serde_json::json!({}));
    let s = serde_json::to_string(&released).unwrap();
    assert!(s.contains("\"releasedKeys\":[\"KeyA\"]"), "got: {s}");
    assert!(s.contains("\"releasedButtons\":[\"left\"]"), "got: {s}");
}

#[test]
fn clearing_control_releases_a_held_mouse_button() {
    // Reproduces the "after releasing control the host desktop ignores clicks" bug:
    // the client's final release_all races with the SFU revoking publish-data, so the
    // host must release its own pressed set when the controller grant is cleared.
    #[derive(Clone, Default)]
    struct ReleasingSink(std::sync::Arc<std::sync::Mutex<Vec<InputEvent>>>);

    impl InputSink for ReleasingSink {
        fn apply(
            &mut self,
            event: &InputEvent,
        ) -> Result<(), aa_screen_host::input::InputEventError> {
            self.0.lock().unwrap().push(event.clone());
            Ok(())
        }

        fn release_all(&mut self) -> (Vec<String>, Vec<String>) {
            self.0.lock().unwrap().push(InputEvent::Pointer {
                seq: 99,
                action: PointerAction::Up,
                x: 0.5,
                y: 0.5,
                button: Some(PointerButton::Left),
                display: 0,
            });
            (Vec::new(), vec!["left".to_string()])
        }

        fn pressed_keys(&self) -> Vec<String> {
            Vec::new()
        }

        fn pressed_buttons(&self) -> Vec<String> {
            vec!["left".to_string()]
        }
    }

    let events = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
    let mut host = Host::new(
        &HostConfig::default(),
        Box::new(FakeCaptureSource::new(2, 640, 360)),
        Box::new(StubPublisher::new()),
        Box::new(ReleasingSink(events.clone())),
        Box::new(aa_screen_host::host::VecEventSink::default()),
    );
    host.handle_request(
        1,
        Method::HostControlGrant,
        &serde_json::json!({"identity": "viewer:pt_a"}),
    );
    let press = serde_json::json!({
        "identity": "viewer:pt_a",
        "events": [{"v":1,"seq":1,"t":"pointer","a":"down","b":"left","x":0.5,"y":0.5}]
    });
    host.handle_request(2, Method::HostInput, &press);

    host.handle_request(
        3,
        Method::HostControlGrant,
        &serde_json::json!({"identity": ""}),
    );
    assert!(
        events.lock().unwrap().iter().any(|event| matches!(
            event,
            InputEvent::Pointer {
                action: PointerAction::Up,
                button: Some(PointerButton::Left),
                ..
            }
        )),
        "clearing control must inject a left-button up, not leave Windows in a drag"
    );
}

#[test]
fn input_from_non_controller_is_rejected() {
    let (mut host, _log) = make_host2();
    host.handle_request(
        1,
        Method::HostControlGrant,
        &serde_json::json!({"identity": "viewer:pt_a"}),
    );
    let wrong = serde_json::json!({
        "identity": "viewer:pt_evil",
        "events": [{"v":1,"seq":1,"t":"key","a":"down","code":"KeyA"}]
    });
    let resp = host.handle_request(2, Method::HostInput, &wrong);
    let s = serde_json::to_string(&resp).unwrap();
    assert!(s.contains(ERR_NOT_CONTROLLER), "got: {s}");

    let missing = serde_json::json!({
        "events": [{"v":1,"seq":1,"t":"key","a":"down","code":"KeyA"}]
    });
    let resp = host.handle_request(3, Method::HostInput, &missing);
    assert!(serde_json::to_string(&resp)
        .unwrap()
        .contains(ERR_NOT_CONTROLLER));

    // Right identity passes.
    let right = serde_json::json!({
        "identity": "viewer:pt_a",
        "events": [{"v":1,"seq":9,"t":"key","a":"down","code":"KeyB"}]
    });
    let resp = host.handle_request(4, Method::HostInput, &right);
    assert!(serde_json::to_string(&resp)
        .unwrap()
        .contains("\"appliedSeq\":9"));
}

#[test]
fn running_host_emits_stats_events() {
    let (mut host, log) = make_host2();
    host.handle_request(1, Method::HostStart, &start_params("rds_1"));

    host.emit_stats();

    assert!(
        log.lock().unwrap().iter().any(|message| {
            let text = serde_json::to_string(message).unwrap();
            text.contains("\"event\":\"stats\"")
        }),
        "the connector needs periodic stats to show actual resolution/bitrate"
    );
}

#[test]
fn update_quality_clamps_out_of_range() {
    let (mut host, _log) = make_host2();
    let params = serde_json::json!({
        "maxFps": 500,
        "maxBitrateKbps": 10,
        "maxWidth": 10
    });
    let resp = host.handle_request(1, Method::HostUpdateQuality, &params);
    assert!(serde_json::to_string(&resp)
        .unwrap()
        .contains("\"applied\":true"));
    assert_eq!(host.quality.max_fps, MAX_FPS);
    assert_eq!(host.quality.max_bitrate_kbps, MIN_BITRATE_KBPS);
    assert_eq!(host.quality.max_width, MIN_WIDTH);
}

#[test]
fn ping_pongs() {
    let (mut host, _log) = make_host2();
    let resp = host.handle_request(1, Method::HostPing, &serde_json::json!({}));
    assert!(serde_json::to_string(&resp)
        .unwrap()
        .contains("\"pong\":true"));
}

#[test]
fn screenshot_returns_png_from_fake_capture() {
    let (mut host, _log) = make_host2();
    let resp = host.handle_request(
        1,
        Method::HostScreenshot,
        &serde_json::json!({"maxWidth": 320}),
    );
    let HostControlMessage::Response(r) = resp else {
        panic!("expected response");
    };
    let result = r.result.expect("screenshot result");
    assert_eq!(result["format"], "png");
    assert_eq!(result["width"], 320);
    assert_eq!(result["height"], 180);
    let data = result["dataBase64"].as_str().unwrap();
    let decoded = base64_decode(data);
    assert_eq!(
        &decoded[..8],
        &[137, 80, 78, 71, 13, 10, 26, 10],
        "PNG signature"
    );
}

#[test]
fn shutdown_emits_stopped_state() {
    let (mut host, log) = make_host2();
    host.handle_request(1, Method::HostStart, &start_params("rds_1"));
    host.shutdown();
    assert_eq!(host.state, HostState::Idle);
    let stopped = log.lock().unwrap().iter().any(|m| {
        serde_json::to_string(m)
            .unwrap()
            .contains("\"state\":\"stopped\"")
    });
    assert!(stopped, "shutdown must emit stopped state event");
}

#[test]
fn state_events_cover_lifecycle() {
    let (mut host, log) = make_host2();
    host.handle_request(1, Method::HostStart, &start_params("rds_9"));
    let kinds: Vec<String> = log
        .lock()
        .unwrap()
        .iter()
        .filter_map(|m| match m {
            HostControlMessage::Event(e) if e.event == EventKind::State => {
                Some(e.params["state"].as_str().unwrap().to_string())
            }
            _ => None,
        })
        .collect();
    assert_eq!(kinds, vec!["starting".to_string(), "running".to_string()]);
}

/// Tiny base64 decoder for test assertions (no external crate).
fn base64_decode(data: &str) -> Vec<u8> {
    fn val(c: u8) -> u32 {
        match c {
            b'A'..=b'Z' => (c - b'A') as u32,
            b'a'..=b'z' => (c - b'a' + 26) as u32,
            b'0'..=b'9' => (c - b'0' + 52) as u32,
            b'+' => 62,
            b'/' => 63,
            _ => 0,
        }
    }
    let bytes: Vec<u8> = data.bytes().filter(|b| *b != b'=').collect();
    let mut out = Vec::new();
    for chunk in bytes.chunks(4) {
        let mut n: u32 = 0;
        for (i, &b) in chunk.iter().enumerate() {
            n |= val(b) << (18 - 6 * i);
        }
        out.push((n >> 16) as u8);
        if chunk.len() > 2 {
            out.push((n >> 8) as u8);
        }
        if chunk.len() > 3 {
            out.push(n as u8);
        }
    }
    out
}

/// Compile-time guard: response_ok is part of the public protocol surface.
#[test]
fn response_ok_shape() {
    let r = response_ok(42, serde_json::json!({"a": 1}));
    let s = serde_json::to_string(&r).unwrap();
    assert!(s.contains("\"id\":42"));
    assert!(s.contains("\"ok\":true"));
}

/// Quality roundtrip sanity.
#[test]
fn quality_serde_defaults() {
    let q: Quality = serde_json::from_str("{}").unwrap();
    assert_eq!(q.max_fps, 30);
    assert_eq!(q.max_bitrate_kbps, 0);
    assert_eq!(q.max_width, 1920);
}

/// The fake capture source itself satisfies the trait contract used by Host.
#[test]
fn fake_capture_contract() {
    let mut src = FakeCaptureSource::new(2, 640, 360);
    assert_eq!(src.displays().len(), 1);
    src.start("0").unwrap();
    assert!(src.next_frame().is_ok());
    assert!(src.next_frame().is_ok());
    assert!(src.next_frame().is_err());
}

/// InputSink trait object is Send (required by Host's boxed fields).
#[test]
fn input_sink_is_send() {
    fn assert_send<T: Send>() {}
    assert_send::<Box<dyn InputSink>>();
    assert_send::<Box<dyn CaptureSource>>();
}

#[test]
fn input_batches_are_rate_limited_to_120_per_second() {
    let (mut host, _log) = make_host2();
    host.handle_request(1, Method::HostInput, &serde_json::json!({"events": []}));
    // 120 events in one batch fit the rolling one-second budget.
    let events: Vec<serde_json::Value> = (1..=120)
        .map(|seq| serde_json::json!({"v":1,"seq":seq,"t":"key","a":"down","code":"KeyA"}))
        .collect();
    let first = host.handle_request(2, Method::HostInput, &serde_json::json!({"events": events}));
    let first_json = serde_json::to_string(&first).unwrap();
    assert!(
        first_json.contains("\"appliedSeq\":120"),
        "got: {first_json}"
    );

    // The next event in the same second is rejected instead of being injected.
    let overflow = host.handle_request(
        3,
        Method::HostInput,
        &serde_json::json!({"events": [{"v":1,"seq":121,"t":"key","a":"up","code":"KeyA"}]}),
    );
    let overflow_json = serde_json::to_string(&overflow).unwrap();
    assert!(
        overflow_json.contains("rd_rate_limited"),
        "got: {overflow_json}"
    );
}

#[test]
fn screenshot_returns_jpeg_when_asked_and_is_smaller_than_png() {
    let (mut host, _log) = make_host2();
    let jpeg = host.handle_request(
        1,
        Method::HostScreenshot,
        &serde_json::json!({"maxWidth": 320, "format": "jpeg", "quality": 80}),
    );
    let png = host.handle_request(
        2,
        Method::HostScreenshot,
        &serde_json::json!({"maxWidth": 320, "format": "png"}),
    );
    let HostControlMessage::Response(jpeg) = jpeg else {
        panic!("expected response");
    };
    let HostControlMessage::Response(png) = png else {
        panic!("expected response");
    };
    let jpeg = jpeg.result.expect("jpeg result");
    let png = png.result.expect("png result");

    assert_eq!(jpeg["format"], "jpeg");
    assert_eq!(jpeg["width"], png["width"]);
    assert_eq!(jpeg["height"], png["height"]);

    let jpeg_bytes = base64_decode(jpeg["dataBase64"].as_str().unwrap());
    let png_bytes = base64_decode(png["dataBase64"].as_str().unwrap());
    assert_eq!(&jpeg_bytes[..2], &[0xFF, 0xD8], "JPEG SOI marker");
    assert_eq!(
        &jpeg_bytes[jpeg_bytes.len() - 2..],
        &[0xFF, 0xD9],
        "JPEG EOI"
    );
    assert!(
        jpeg_bytes.len() * 2 < png_bytes.len(),
        "jpeg {} should be much smaller than png {}",
        jpeg_bytes.len(),
        png_bytes.len()
    );
}

#[test]
fn screenshot_format_falls_back_to_png_for_unknown_values() {
    let (mut host, _log) = make_host2();
    let resp = host.handle_request(
        1,
        Method::HostScreenshot,
        &serde_json::json!({"maxWidth": 160, "format": "webp"}),
    );
    let HostControlMessage::Response(r) = resp else {
        panic!("expected response");
    };
    let result = r.result.expect("screenshot result");
    assert_eq!(result["format"], "png");
}

// ─── Media data-channel input (`aa.input` / `aa.pointer`) ────────────────────
//
// The primary input plane of a viewer is the LiveKit data channel, not `host.input`:
// `Host::apply_channel_input` drains what the publisher received and hands it to the same
// sink. These tests pin the three ways that path can silently do nothing: no packet, a
// packet from someone who is not the controller, and a malformed payload.

mod channel_input {
    use super::*;
    use aa_screen_host::input::{InputEvent, KeyAction, PointerAction, PointerButton};
    use aa_screen_host::pointer::{encode_pointer_frame, PointerFrame, POINTER_TYPE};
    use aa_screen_host::publish::{MediaGrant, Publisher, ReceivedData, Stats};
    use std::sync::{Arc, Mutex};

    /// Input sink that records what the host injected.
    #[derive(Clone, Default)]
    struct RecordedSink {
        events: Arc<Mutex<Vec<InputEvent>>>,
        releases: Arc<Mutex<usize>>,
    }

    impl InputSink for RecordedSink {
        fn apply(
            &mut self,
            event: &InputEvent,
        ) -> Result<(), aa_screen_host::input::InputEventError> {
            self.events.lock().unwrap().push(event.clone());
            Ok(())
        }

        fn release_all(&mut self) -> (Vec<String>, Vec<String>) {
            *self.releases.lock().unwrap() += 1;
            (Vec::new(), Vec::new())
        }

        fn pressed_keys(&self) -> Vec<String> {
            Vec::new()
        }

        fn pressed_buttons(&self) -> Vec<String> {
            Vec::new()
        }
    }

    /// Publisher whose data queue the test fills, standing in for a LiveKit room.
    #[derive(Clone, Default)]
    struct ChannelPublisher(Arc<Mutex<Vec<ReceivedData>>>);

    impl Publisher for ChannelPublisher {
        fn start(&mut self, _: &str, _: &MediaGrant, _: &Quality, _: u32, _: u32) {}
        fn publish_frame(&mut self, _: &aa_screen_host::capture::Frame) {}
        fn stop(&mut self) {}
        fn request_keyframe(&mut self) -> bool {
            false
        }
        fn take_data(&mut self) -> Vec<ReceivedData> {
            std::mem::take(&mut *self.0.lock().unwrap())
        }
        fn stats(&self) -> Stats {
            Stats::default()
        }
    }

    fn make_host() -> (
        Host,
        Arc<Mutex<Vec<InputEvent>>>,
        Arc<Mutex<Vec<ReceivedData>>>,
        Arc<Mutex<usize>>,
    ) {
        let events = Arc::new(Mutex::new(Vec::new()));
        let queue = Arc::new(Mutex::new(Vec::new()));
        let releases = Arc::new(Mutex::new(0usize));
        let host = Host::new(
            &HostConfig::default(),
            Box::new(FakeCaptureSource::new(1, 640, 360)),
            Box::new(ChannelPublisher(queue.clone())),
            Box::new(RecordedSink {
                events: events.clone(),
                releases: releases.clone(),
            }),
            Box::new(aa_screen_host::host::VecEventSink::default()),
        );
        (host, events, queue, releases)
    }

    fn key_event(seq: u32, code: &str) -> serde_json::Value {
        serde_json::json!({"v":1,"seq":seq,"t":"key","a":"down","code":code})
    }

    fn packet(topic: &str, from: &str, payload: Vec<u8>) -> ReceivedData {
        ReceivedData {
            topic: topic.to_string(),
            payload,
            from: from.to_string(),
        }
    }

    #[test]
    fn applies_json_input_from_the_controller() {
        let (mut host, events, queue, _) = make_host();
        host.handle_request(
            1,
            Method::HostControlGrant,
            &serde_json::json!({"identity": "viewer:pt_a"}),
        );
        queue.lock().unwrap().push(packet(
            "aa.input",
            "viewer:pt_a",
            key_event(1, "KeyA").to_string().into_bytes(),
        ));

        host.apply_channel_input();

        let events = events.lock().unwrap();
        assert_eq!(
            events.as_slice(),
            &[InputEvent::Key {
                seq: 1,
                action: KeyAction::Down,
                code: "KeyA".to_string(),
                mods: Vec::new(),
            }]
        );
    }

    #[test]
    fn ignores_pointer_frames_after_control_is_released() {
        let (mut host, events, queue, _) = make_host();
        host.handle_request(
            1,
            Method::HostControlGrant,
            &serde_json::json!({"identity": "viewer:pt_a"}),
        );
        host.handle_request(
            2,
            Method::HostControlGrant,
            &serde_json::json!({"identity": ""}),
        );
        queue.lock().unwrap().push(packet(
            "aa.pointer",
            "viewer:pt_a",
            encode_pointer_frame(&PointerFrame {
                frame_type: POINTER_TYPE,
                flags: 1,
                buttons: aa_screen_host::pointer::BUTTON_LEFT,
                x: 32768,
                y: 32768,
                seq: 5,
            })
            .to_vec(),
        ));

        host.apply_channel_input();

        assert!(
            events.lock().unwrap().is_empty(),
            "a late drag frame must not re-press the button after control is released"
        );
    }

    #[test]
    fn applies_release_all_from_the_controller() {
        let (mut host, _events, queue, releases) = make_host();
        host.handle_request(
            1,
            Method::HostControlGrant,
            &serde_json::json!({"identity": "viewer:pt_a"}),
        );
        *releases.lock().unwrap() = 0;
        {
            let mut queue = queue.lock().unwrap();
            queue.push(packet(
                "aa.input",
                "viewer:pt_a",
                key_event(1, "KeyA").to_string().into_bytes(),
            ));
            queue.push(packet(
                "aa.input",
                "viewer:pt_a",
                serde_json::json!({"v":1,"t":"release_all","reason":"pagehide"})
                    .to_string()
                    .into_bytes(),
            ));
        }

        host.apply_channel_input();

        assert_eq!(
            1,
            *releases.lock().unwrap(),
            "release_all must reach the sink so a held left button is released"
        );
        assert!(
            _events
                .lock()
                .unwrap()
                .iter()
                .any(|event| matches!(event, InputEvent::Key { .. })),
            "the key before release_all should still have been injected"
        );
    }

    #[test]
    fn release_all_bypasses_the_motion_budget() {
        let (mut host, _events, queue, releases) = make_host();
        host.handle_request(
            1,
            Method::HostControlGrant,
            &serde_json::json!({"identity": "viewer:pt_a"}),
        );
        *releases.lock().unwrap() = 0;
        {
            let mut queue = queue.lock().unwrap();
            for seq in 1..=120 {
                queue.push(packet(
                    "aa.input",
                    "viewer:pt_a",
                    key_event(seq, "KeyA").to_string().into_bytes(),
                ));
            }
            // The 120/s window is full; this release must still reach the sink.
            queue.push(packet(
                "aa.input",
                "viewer:pt_a",
                serde_json::json!({"v":1,"t":"release_all","reason":"pagehide"})
                    .to_string()
                    .into_bytes(),
            ));
        }

        host.apply_channel_input();

        assert_eq!(
            1,
            *releases.lock().unwrap(),
            "release_all must bypass the full 120/s motion budget"
        );
    }

    #[test]
    fn applies_pointer_frames_from_the_lossy_channel() {
        let (mut host, events, queue, _) = make_host();
        host.handle_request(
            1,
            Method::HostControlGrant,
            &serde_json::json!({"identity": "viewer:pt_a"}),
        );
        let frame = encode_pointer_frame(&PointerFrame {
            frame_type: POINTER_TYPE,
            flags: 0,
            buttons: 0,
            x: 65535,
            y: 0,
            seq: 42,
        });
        queue
            .lock()
            .unwrap()
            .push(packet("aa.pointer", "viewer:pt_a", frame.to_vec()));

        host.apply_channel_input();

        let events = events.lock().unwrap();
        assert_eq!(events.len(), 1, "one frame must inject one move");
        match &events[0] {
            InputEvent::Pointer {
                seq,
                action,
                x,
                y,
                button,
                ..
            } => {
                assert_eq!(*seq, 42);
                assert_eq!(*action, PointerAction::Move);
                assert!((*x - 1.0).abs() < 1e-6, "x={x}");
                assert!(*y < 1e-6, "y={y}");
                assert!(button.is_none());
            }
            other => panic!("expected a pointer move, got {other:?}"),
        }
    }

    #[test]
    fn pointer_frame_button_bits_press_and_release_the_drag_button() {
        let (mut host, events, queue, _) = make_host();
        host.handle_request(
            1,
            Method::HostControlGrant,
            &serde_json::json!({"identity": "viewer:pt_a"}),
        );
        let pressed = encode_pointer_frame(&PointerFrame {
            frame_type: POINTER_TYPE,
            flags: 1,
            buttons: aa_screen_host::pointer::BUTTON_LEFT,
            x: 32768,
            y: 32768,
            seq: 10,
        });
        let released = encode_pointer_frame(&PointerFrame {
            frame_type: POINTER_TYPE,
            flags: 1,
            buttons: 0,
            x: 33000,
            y: 32768,
            seq: 11,
        });
        // A stale frame (lower seq) must not resurrect the button state.
        let stale = encode_pointer_frame(&PointerFrame {
            frame_type: POINTER_TYPE,
            flags: 0,
            buttons: 0,
            x: 0,
            y: 0,
            seq: 9,
        });
        {
            let mut queue = queue.lock().unwrap();
            queue.push(packet("aa.pointer", "viewer:pt_a", pressed.to_vec()));
            queue.push(packet("aa.pointer", "viewer:pt_a", released.to_vec()));
            queue.push(packet("aa.pointer", "viewer:pt_a", stale.to_vec()));
        }

        host.apply_channel_input();

        let events = events.lock().unwrap();
        let actions: Vec<(PointerAction, Option<PointerButton>)> = events
            .iter()
            .filter_map(|event| match event {
                InputEvent::Pointer { action, button, .. } => Some((*action, *button)),
                _ => None,
            })
            .collect();
        assert_eq!(
            actions,
            vec![
                (PointerAction::Down, Some(PointerButton::Left)),
                (PointerAction::Move, None),
                (PointerAction::Up, Some(PointerButton::Left)),
                (PointerAction::Move, None),
            ],
            "drag press/release is applied from the lossy frame, stale frame ignored"
        );
    }

    #[test]
    fn drops_packets_from_anyone_but_the_controller() {
        let (mut host, events, queue, _) = make_host();
        host.handle_request(
            1,
            Method::HostControlGrant,
            &serde_json::json!({"identity": "viewer:pt_a"}),
        );
        {
            let mut queue = queue.lock().unwrap();
            queue.push(packet(
                "aa.input",
                "viewer:pt_evil",
                key_event(1, "KeyA").to_string().into_bytes(),
            ));
            queue.push(packet(
                "aa.pointer",
                "viewer:pt_evil",
                encode_pointer_frame(&PointerFrame {
                    frame_type: POINTER_TYPE,
                    flags: 0,
                    buttons: 0,
                    x: 1,
                    y: 1,
                    seq: 1,
                })
                .to_vec(),
            ));
        }

        host.apply_channel_input();

        assert!(
            events.lock().unwrap().is_empty(),
            "input from a non-controller must never reach the OS"
        );
    }

    #[test]
    fn ignores_malformed_payloads_and_unknown_topics() {
        let (mut host, events, queue, _) = make_host();
        host.handle_request(
            1,
            Method::HostControlGrant,
            &serde_json::json!({"identity": "viewer:pt_a"}),
        );
        {
            let mut queue = queue.lock().unwrap();
            queue.push(packet("aa.input", "viewer:pt_a", b"not json".to_vec()));
            queue.push(packet("aa.pointer", "viewer:pt_a", vec![0x01, 0x00]));
            queue.push(packet("aa.something", "viewer:pt_a", b"{}".to_vec()));
            // A good packet after the bad ones still lands.
            queue.push(packet(
                "aa.input",
                "viewer:pt_a",
                key_event(9, "KeyB").to_string().into_bytes(),
            ));
        }

        host.apply_channel_input();

        let events = events.lock().unwrap();
        assert_eq!(events.len(), 1, "only the valid packet is injected");
        assert!(matches!(
            &events[0],
            InputEvent::Key { code, .. } if code == "KeyB"
        ));
    }
}

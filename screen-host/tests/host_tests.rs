//! Host state-machine tests using fake capture/input sinks.

use aa_screen_host::capture::fake::FakeCaptureSource;
use aa_screen_host::capture::CaptureSource;
use aa_screen_host::config::{HostConfig, Quality, MAX_FPS, MIN_BITRATE_KBPS, MIN_WIDTH};
use aa_screen_host::host::{Host, HostState};
use aa_screen_host::input::fake::FakeInputSink;
use aa_screen_host::input::InputSink;
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
    assert_eq!(q.max_bitrate_kbps, 4000);
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

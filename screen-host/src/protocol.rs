//! HostControl wire protocol model.
//!
//! Mirrors `contracts/remote-desktop/1.0/schemas/host-control-message.schema.json`
//! exactly: one NDJSON frame per line, UTF-8, `type`-tagged union, camelCase fields,
//! `v` pinned to 1.
//!
//! Framing rules (from the schema `x-transport`): stdout carries protocol frames only,
//! stderr carries logs only, and a single frame must not exceed 4 MiB (4194304 bytes).

use serde::{Deserialize, Serialize};

/// Maximum size of a single inbound NDJSON frame (4 MiB), per the schema.
pub const MAX_FRAME_BYTES: usize = 4 * 1024 * 1024;

/// Current protocol version.
pub const PROTOCOL_VERSION: u8 = 1;

/// Internal error codes emitted on the control channel.
pub const ERR_PROTOCOL: &str = "rd_host_protocol_error";
pub const ERR_INVALID_PARAMS: &str = "rd_invalid_params";
pub const ERR_NOT_CONTROLLER: &str = "rd_not_controller";
pub const ERR_CAPTURE_FAILED: &str = "rd_capture_failed";
pub const ERR_UNSUPPORTED: &str = "rd_unsupported";

/// One frame on the bidirectional stdio control channel.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum HostControlMessage {
    /// Host -> connector greeting, written immediately on startup.
    Hello(Hello),
    /// Connector -> host method invocation.
    Request(Request),
    /// Host -> connector method result.
    Response(Response),
    /// Host -> connector asynchronous event.
    Event(Event),
    /// Fatal protocol-level error; host exits with code 78 afterwards.
    Error(ErrorFrame),
    /// Graceful shutdown (either direction).
    Shutdown(Shutdown),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Hello {
    #[serde(default = "default_v")]
    pub v: u8,
    pub protocol_version: u8,
    pub host_version: String,
    pub platform: Platform,
    pub arch: Arch,
    /// Must be >= 1.
    pub pid: u32,
    pub capabilities: serde_json::Value,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Platform {
    Windows,
    Macos,
    Linux,
    #[serde(rename = "unknown")]
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Arch {
    X8664,
    Aarch64,
    Unknown,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Request {
    #[serde(default = "default_v")]
    pub v: u8,
    /// Must be >= 1.
    pub id: u64,
    pub method: Method,
    pub params: serde_json::Value,
    /// 1..=300000; recorded but not enforced.
    #[serde(default)]
    pub deadline_ms: Option<u64>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Method {
    #[serde(rename = "host.info")]
    HostInfo,
    #[serde(rename = "host.start")]
    HostStart,
    #[serde(rename = "host.stop")]
    HostStop,
    #[serde(rename = "host.updateQuality")]
    HostUpdateQuality,
    #[serde(rename = "host.controlGrant")]
    HostControlGrant,
    #[serde(rename = "host.requestKeyframe")]
    HostRequestKeyframe,
    #[serde(rename = "host.releaseAll")]
    HostReleaseAll,
    #[serde(rename = "host.screenshot")]
    HostScreenshot,
    #[serde(rename = "host.input")]
    HostInput,
    #[serde(rename = "host.ping")]
    HostPing,
}

impl Method {
    pub fn as_str(self) -> &'static str {
        match self {
            Method::HostInfo => "host.info",
            Method::HostStart => "host.start",
            Method::HostStop => "host.stop",
            Method::HostUpdateQuality => "host.updateQuality",
            Method::HostControlGrant => "host.controlGrant",
            Method::HostRequestKeyframe => "host.requestKeyframe",
            Method::HostReleaseAll => "host.releaseAll",
            Method::HostScreenshot => "host.screenshot",
            Method::HostInput => "host.input",
            Method::HostPing => "host.ping",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Response {
    #[serde(default = "default_v")]
    pub v: u8,
    pub id: u64,
    pub ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<ErrorObject>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Event {
    #[serde(default = "default_v")]
    pub v: u8,
    pub event: EventKind,
    pub params: serde_json::Value,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EventKind {
    State,
    Stats,
    Displays,
    Consent,
    Clipboard,
    Error,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ErrorFrame {
    #[serde(default = "default_v")]
    pub v: u8,
    pub error: ErrorObjectExt,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ErrorObject {
    pub code: String,
    pub message: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ErrorObjectExt {
    pub code: String,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub retryable: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Shutdown {
    #[serde(default = "default_v")]
    pub v: u8,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
}

fn default_v() -> u8 {
    PROTOCOL_VERSION
}

pub fn response_ok(id: u64, result: serde_json::Value) -> HostControlMessage {
    HostControlMessage::Response(Response {
        v: PROTOCOL_VERSION,
        id,
        ok: true,
        result: Some(result),
        error: None,
    })
}

pub fn response_err(id: u64, code: &str, message: impl Into<String>) -> HostControlMessage {
    HostControlMessage::Response(Response {
        v: PROTOCOL_VERSION,
        id,
        ok: false,
        result: None,
        error: Some(ErrorObject {
            code: code.to_string(),
            message: message.into(),
        }),
    })
}

pub fn protocol_error(code: &str, message: impl Into<String>) -> HostControlMessage {
    HostControlMessage::Error(ErrorFrame {
        v: PROTOCOL_VERSION,
        error: ErrorObjectExt {
            code: code.to_string(),
            message: message.into(),
            retryable: Some(false),
        },
    })
}

pub fn event(kind: EventKind, params: serde_json::Value) -> HostControlMessage {
    HostControlMessage::Event(Event {
        v: PROTOCOL_VERSION,
        event: kind,
        params,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn roundtrip(msg: &HostControlMessage) -> HostControlMessage {
        let s = serde_json::to_string(msg).unwrap();
        serde_json::from_str(&s).unwrap()
    }

    #[test]
    fn hello_roundtrip() {
        let m = HostControlMessage::Hello(Hello {
            v: 1,
            protocol_version: 1,
            host_version: "0.1.0".into(),
            platform: Platform::Windows,
            arch: Arch::X8664,
            pid: 4242,
            capabilities: serde_json::json!({"view": true}),
        });
        let m = roundtrip(&m);
        let s = serde_json::to_string(&m).unwrap();
        assert!(s.contains("\"type\":\"hello\""));
        assert!(s.contains("\"protocolVersion\":1"));
        assert!(s.contains("\"hostVersion\":\"0.1.0\""));
    }

    #[test]
    fn request_roundtrip() {
        let m: HostControlMessage = serde_json::from_str(
            r#"{"v":1,"type":"request","id":7,"method":"host.start",
               "params":{"sessionId":"rds_1"},"deadlineMs":5000}"#,
        )
        .unwrap();
        match &m {
            HostControlMessage::Request(r) => {
                assert_eq!(r.id, 7);
                assert_eq!(r.method, Method::HostStart);
                assert_eq!(r.deadline_ms, Some(5000));
                assert_eq!(r.params["sessionId"], "rds_1");
            }
            other => panic!("unexpected: {other:?}"),
        }
        let m = roundtrip(&m);
        assert!(serde_json::to_string(&m)
            .unwrap()
            .contains("\"method\":\"host.start\""));
    }

    #[test]
    fn response_roundtrip() {
        let m = response_ok(7, serde_json::json!({"accepted": true}));
        let m = roundtrip(&m);
        assert!(serde_json::to_string(&m).unwrap().contains("\"ok\":true"));

        let m = response_err(9, ERR_NOT_CONTROLLER, "not controller");
        let m = roundtrip(&m);
        let s = serde_json::to_string(&m).unwrap();
        assert!(s.contains("\"ok\":false"));
        assert!(s.contains(ERR_NOT_CONTROLLER));
    }

    #[test]
    fn event_roundtrip() {
        let m = event(
            EventKind::State,
            serde_json::json!({"sessionId": "rds_1", "state": "running"}),
        );
        let m = roundtrip(&m);
        assert!(serde_json::to_string(&m)
            .unwrap()
            .contains("\"event\":\"state\""));
    }

    #[test]
    fn error_roundtrip() {
        let m = protocol_error(ERR_PROTOCOL, "line too long");
        let m = roundtrip(&m);
        assert!(serde_json::to_string(&m)
            .unwrap()
            .contains("\"type\":\"error\""));
    }

    #[test]
    fn shutdown_roundtrip() {
        let m: HostControlMessage =
            serde_json::from_str(r#"{"v":1,"type":"shutdown","reason":"eof"}"#).unwrap();
        let m = roundtrip(&m);
        assert!(serde_json::to_string(&m)
            .unwrap()
            .contains("\"type\":\"shutdown\""));
    }

    #[test]
    fn v_defaults_to_one() {
        let m: HostControlMessage =
            serde_json::from_str(r#"{"type":"request","id":1,"method":"host.ping","params":{}}"#)
                .unwrap();
        match m {
            HostControlMessage::Request(r) => assert_eq!(r.v, 1),
            other => panic!("unexpected: {other:?}"),
        }
    }
}

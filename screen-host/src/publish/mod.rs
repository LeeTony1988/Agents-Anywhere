//! Publisher abstraction over media backends (LiveKit, stub).

#[cfg(feature = "livekit")]
pub mod livekit;
pub mod stub;

use serde::{Deserialize, Serialize};

/// Media delivery contract handed to the publisher on `host.start`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct MediaGrant {
    pub url: String,
    pub room: String,
    pub token: String,
    pub identity: String,
}

/// Publish/metrics snapshot returned by [`Publisher::stats`].
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Stats {
    pub fps: f64,
    pub bitrate_kbps: f64,
    pub rtt_ms: f64,
    pub packet_loss_pct: f64,
    pub codec: String,
    pub width: u32,
    pub height: u32,
    pub encoder: String,
}

impl Default for Stats {
    fn default() -> Self {
        Self {
            fps: 0.0,
            bitrate_kbps: 0.0,
            rtt_ms: 0.0,
            packet_loss_pct: 0.0,
            codec: "vp8".to_string(),
            width: 0,
            height: 0,
            encoder: crate::config::DEFAULT_ENCODER.to_string(),
        }
    }
}

/// Media backend used by the host.
/// One data-channel packet a viewer sent to the host (input plane).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReceivedData {
    pub topic: String,
    pub payload: Vec<u8>,
    pub from: String,
}

pub trait Publisher: Send {
    fn start(
        &mut self,
        session_id: &str,
        media: &MediaGrant,
        quality: &crate::config::Quality,
        width: u32,
        height: u32,
    );
    fn publish_frame(&mut self, frame: &crate::capture::Frame);
    fn stop(&mut self);
    /// Best-effort keyframe request (decisions §1.1.4); returns whether it was sent.
    fn request_keyframe(&mut self) -> bool;

    /// Drains input packets received over the media data channel. The default is empty:
    /// publishers without a data plane simply never deliver input that way.
    fn take_data(&mut self) -> Vec<ReceivedData> {
        Vec::new()
    }
    fn stats(&self) -> Stats;
}

/// Pick the publisher backend. With the `livekit` feature the real
/// [`livekit::LiveKitPublisher`] skeleton is used (see that module for the
/// remaining build requirements); otherwise a [`stub::StubPublisher`].
pub fn new_publisher() -> Box<dyn Publisher> {
    #[cfg(feature = "livekit")]
    {
        Box::new(livekit::LiveKitPublisher::new())
    }
    #[cfg(not(feature = "livekit"))]
    {
        Box::new(stub::StubPublisher::new())
    }
}

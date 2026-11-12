//! No-op publisher used by default and in tests.

use super::{MediaGrant, Publisher, Stats};

#[derive(Debug, Default)]
pub struct StubPublisher {
    running: bool,
    session_id: Option<String>,
}

impl StubPublisher {
    pub fn new() -> Self {
        Self::default()
    }
}

impl Publisher for StubPublisher {
    fn start(
        &mut self,
        session_id: &str,
        _media: &MediaGrant,
        _quality: &crate::config::Quality,
        _width: u32,
        _height: u32,
    ) {
        self.running = true;
        self.session_id = Some(session_id.to_string());
    }

    fn stop(&mut self) {
        self.running = false;
        self.session_id = None;
    }

    fn publish_frame(&mut self, _frame: &crate::capture::Frame) {
        // The stub has no media; the host still exercises the capture loop.
    }

    fn request_keyframe(&mut self) -> bool {
        // Best-effort: nothing to request on a stub.
        false
    }

    fn stats(&self) -> Stats {
        Stats::default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stub_lifecycle() {
        let mut p = StubPublisher::new();
        assert!(!p.request_keyframe());
        p.start(
            "rds_1",
            &MediaGrant {
                url: "wss://rtc.example.com".into(),
                room: "rd_rds_1".into(),
                token: "t".into(),
                identity: "host:conn_1".into(),
            },
            &crate::config::Quality::default(),
            1920,
            1080,
        );
        assert!(!p.request_keyframe());
        let stats = p.stats();
        assert_eq!(stats.codec, "vp8");
        assert_eq!(stats.encoder, "libwebrtc-sw");
        p.stop();
        assert!(!p.request_keyframe());
    }
}

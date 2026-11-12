//! LiveKit publisher skeleton (feature `livekit`, crate pinned to `=0.10.1`).
//!
//! Publishes raw I420 frames through `NativeVideoSource` with VP8 software
//! encoding, `TrackSource::Screenshare`, `simulcast = false` and
//! `content_hint("text")`, per docs/remote-desktop.md §6.3/§7.5.
//!
//! IMPORTANT — build status: this module is a skeleton. The `livekit = "=0.10.1"`
//! dependency pulls in `libwebrtc` which requires a network build
//! (`webrtc-sys-build` downloads prebuilt or builds from source). It is
//! **deliberately not part of the default build** and has not been compiled
//! end-to-end in this change (see README "已知限制"). Enable with
//! `cargo build --features livekit` once network access to the webrtc build
//! artifacts is available. The default `stub` publisher keeps `cargo test` fast
//! and offline.

#![allow(dead_code, unused_variables)]

use log::{info, warn};

use super::{MediaGrant, Publisher, Stats};

pub struct LiveKitPublisher {
    started: bool,
    session_id: Option<String>,
    grant: Option<MediaGrant>,
}

impl LiveKitPublisher {
    pub fn new() -> Self {
        Self {
            started: false,
            session_id: None,
            grant: None,
        }
    }
}

impl Publisher for LiveKitPublisher {
    fn start(&mut self, session_id: &str, media: &MediaGrant) {
        // TODO(RD-P2-11): connect the LiveKit room:
        //   let (room, mut events) = livekit::Room::connect(&media.url, &media.token, RoomOptions::default()).await;
        //   let source = NativeVideoSource::new(true, VideoResolution { width, height });
        //   let track = LocalVideoTrack::create_video_track("screen", RtcVideoSource::Native(source.clone()), &room);
        //   room.local_participant().publish_track(track, TrackPublishOptions {
        //       source: TrackSource::Screenshare,
        //       video_codec: VideoCodec::VP8,
        //       simulcast: false,
        //       ..Default::default()
        //   }).await;
        //   track.set_content_hint("text");
        // Then feed `source.capture_frame(&I420Buffer)` from the capture thread.
        info!("livekit publisher start requested for session {session_id} (skeleton)");
        self.started = true;
        self.session_id = Some(session_id.to_string());
        self.grant = Some(media.clone());
    }

    fn stop(&mut self) {
        info!("livekit publisher stop (skeleton)");
        self.started = false;
        self.session_id = None;
        self.grant = None;
    }

    fn request_keyframe(&mut self) -> bool {
        // Best-effort (decisions §1.1.4): the Rust SDK does not expose a subscriber
        // keyframe request; log and report false.
        warn!("requestKeyframe is best-effort and unsupported by the Rust SDK skeleton");
        false
    }

    fn stats(&self) -> Stats {
        // TODO(RD-P2-11): fill from Room::get_stats: fps, bitrateKbps, rttMs,
        // packetLossPct, codec, width, height, encoder ("libwebrtc-sw").
        Stats::default()
    }
}

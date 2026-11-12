//! LiveKit publisher (feature `livekit`, crate pinned to `=0.10.1`).
//!
//! Publishes raw I420 frames through `NativeVideoSource` with VP8 software
//! encoding, `TrackSource::Screenshare`, `simulcast = false`. The room worker
//! runs on a dedicated Tokio runtime so the synchronous host stdio loop never
//! blocks on WebRTC.
//!
//! Build requires the prebuilt libwebrtc archive from the LiveKit Rust SDK
//! release; set `LK_CUSTOM_WEBRTC` to an extracted `win-x64-release` directory
//! when GitHub download is blocked (see README).

#![allow(dead_code)]

use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

use log::{info, warn};
use tokio::sync::mpsc::error::TrySendError;

use livekit::options::{TrackPublishOptions, VideoCodec, VideoEncoding};
use livekit::prelude::*;
use livekit::webrtc::video_frame::{I420Buffer, VideoFrame, VideoRotation};
use livekit::webrtc::video_source::native::NativeVideoSource;
use livekit::webrtc::video_source::VideoResolution;

use super::{MediaGrant, Publisher, Stats};
use crate::capture::Frame;
use crate::config::Quality;

enum Command {
    Frame(Frame),
    Stop,
}

pub struct LiveKitPublisher {
    sender: Option<tokio::sync::mpsc::Sender<Command>>,
    worker: Option<JoinHandle<()>>,
    stats: Arc<Mutex<Stats>>,
    started: bool,
}

impl LiveKitPublisher {
    pub fn new() -> Self {
        Self {
            sender: None,
            worker: None,
            stats: Arc::new(Mutex::new(Stats::default())),
            started: false,
        }
    }

    fn stop_worker(&mut self) {
        if let Some(sender) = self.sender.take() {
            let _ = sender.try_send(Command::Stop);
        }
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}

impl Default for LiveKitPublisher {
    fn default() -> Self {
        Self::new()
    }
}

impl Publisher for LiveKitPublisher {
    fn start(
        &mut self,
        session_id: &str,
        media: &MediaGrant,
        quality: &Quality,
        width: u32,
        height: u32,
    ) {
        self.stop_worker();
        let (sender, mut receiver) = tokio::sync::mpsc::channel::<Command>(2);
        let stats = Arc::clone(&self.stats);
        let grant = media.clone();
        let quality = *quality;
        let session = session_id.to_string();
        self.worker = Some(std::thread::spawn(move || {
            let runtime = match tokio::runtime::Builder::new_multi_thread()
                .worker_threads(2)
                .enable_all()
                .build()
            {
                Ok(runtime) => runtime,
                Err(error) => {
                    warn!("LiveKit runtime start failed: {error}");
                    return;
                }
            };
            runtime.block_on(async move {
                let (room, mut events) =
                    match Room::connect(&grant.url, &grant.token, RoomOptions::default()).await {
                        Ok(room) => room,
                        Err(error) => {
                            warn!("LiveKit connect failed session={session}: {error}");
                            return;
                        }
                    };
                tokio::spawn(async move { while events.recv().await.is_some() {} });
                let resolution = VideoResolution {
                    width: width.max(2),
                    height: height.max(2),
                };
                let source = NativeVideoSource::new(resolution, true);
                let track = LocalVideoTrack::create_video_track(
                    "screen",
                    livekit::webrtc::video_source::RtcVideoSource::Native(source.clone()),
                );
                let options = TrackPublishOptions {
                    source: TrackSource::Screenshare,
                    video_codec: VideoCodec::VP8,
                    simulcast: false,
                    video_encoding: Some(VideoEncoding {
                        max_bitrate: (quality.max_bitrate_kbps as u64) * 1000,
                        max_framerate: quality.max_fps as f64,
                    }),
                    ..Default::default()
                };
                if let Err(error) = room
                    .local_participant()
                    .publish_track(LocalTrack::Video(track), options)
                    .await
                {
                    warn!("LiveKit publish_track failed session={session}: {error}");
                    let _ = room.close().await;
                    return;
                }
                info!("LiveKit publisher connected session={session}");
                let mut frame_count = 0u32;
                let mut last_report = std::time::Instant::now();
                while let Some(command) = receiver.recv().await {
                    match command {
                        Command::Frame(frame) => {
                            let mut buffer = I420Buffer::with_strides(
                                frame.width,
                                frame.height,
                                frame.width,
                                frame.width / 2,
                                frame.width / 2,
                            );
                            let (y, u, v) = buffer.data_mut();
                            let y_len = frame.width as usize * frame.height as usize;
                            let uv_len = (frame.width / 2) as usize * (frame.height / 2) as usize;
                            if frame.i420.len() >= y_len + 2 * uv_len {
                                y.copy_from_slice(&frame.i420[..y_len]);
                                u.copy_from_slice(&frame.i420[y_len..y_len + uv_len]);
                                v.copy_from_slice(&frame.i420[y_len + uv_len..y_len + 2 * uv_len]);
                            }
                            let video_frame =
                                VideoFrame::new(VideoRotation::VideoRotation0, buffer);
                            let _ = source.capture_frame(&video_frame);
                            frame_count += 1;
                            let elapsed = last_report.elapsed().as_secs_f64();
                            if elapsed >= 1.0 {
                                if let Ok(mut stats) = stats.lock() {
                                    stats.fps = frame_count as f64 / elapsed;
                                    stats.width = frame.width;
                                    stats.height = frame.height;
                                    stats.codec = "vp8".to_string();
                                    stats.encoder = "libwebrtc-sw".to_string();
                                }
                                frame_count = 0;
                                last_report = std::time::Instant::now();
                            }
                        }
                        Command::Stop => break,
                    }
                }
                let _ = room.close().await;
            });
        }));
        self.sender = Some(sender);
        self.started = true;
    }

    fn publish_frame(&mut self, frame: &Frame) {
        let Some(sender) = self.sender.as_ref() else {
            return;
        };
        match sender.try_send(Command::Frame(frame.clone())) {
            Ok(()) | Err(TrySendError::Full(_)) | Err(TrySendError::Closed(_)) => {}
        }
    }

    fn stop(&mut self) {
        self.started = false;
        self.stop_worker();
    }

    fn request_keyframe(&mut self) -> bool {
        // Best-effort (decisions §1.1.4): the Rust SDK does not expose a
        // reliable subscriber-side keyframe request; never claim success.
        warn!("requestKeyframe is best-effort and unsupported by the LiveKit Rust SDK");
        false
    }

    fn stats(&self) -> Stats {
        self.stats
            .lock()
            .map(|stats| stats.clone())
            .unwrap_or_default()
    }
}

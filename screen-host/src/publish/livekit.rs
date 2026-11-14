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

use super::{MediaGrant, Publisher, ReceivedData, Stats};
use crate::capture::Frame;
use crate::config::Quality;

enum Command {
    Frame(Frame),
    /// Re-publish the video track so the encoder emits a fresh keyframe.
    Refresh,
    Stop,
}

pub struct LiveKitPublisher {
    data_queue: std::sync::Arc<std::sync::Mutex<std::collections::VecDeque<ReceivedData>>>,
    sender: Option<tokio::sync::mpsc::Sender<Command>>,
    worker: Option<JoinHandle<()>>,
    stats: Arc<Mutex<Stats>>,
    started: bool,
}

impl LiveKitPublisher {
    pub fn new() -> Self {
        Self {
            data_queue: std::sync::Arc::new(std::sync::Mutex::new(
                std::collections::VecDeque::new(),
            )),
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
        let data_queue = std::sync::Arc::clone(&self.data_queue);
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
                let queue = std::sync::Arc::clone(&data_queue);
                tokio::spawn(async move {
                    while let Some(event) = events.recv().await {
                        if let RoomEvent::DataReceived {
                            payload,
                            topic,
                            participant,
                            ..
                        } = event
                        {
                            let from = participant
                                .as_ref()
                                .map(|p| p.identity().to_string())
                                .unwrap_or_default();
                            if let Ok(mut queue) = queue.lock() {
                                queue.push_back(ReceivedData {
                                    topic: topic.unwrap_or_default(),
                                    payload: payload.as_ref().clone(),
                                    from,
                                });
                            }
                        }
                    }
                });
                let resolution = VideoResolution {
                    width: width.max(2),
                    height: height.max(2),
                };
                let mut source = NativeVideoSource::new(resolution.clone(), true);
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
                let mut publication = match room
                    .local_participant()
                    .publish_track(LocalTrack::Video(track), options.clone())
                    .await
                {
                    Ok(publication) => publication,
                    Err(error) => {
                        warn!("LiveKit publish_track failed session={session}: {error}");
                        let _ = room.close().await;
                        return;
                    }
                };
                info!("LiveKit publisher connected session={session}");
                let mut frame_count = 0u32;
                let mut last_report = std::time::Instant::now();
                // The Rust SDK exposes no keyframe request, and the encoder only
                // emits one when a track starts. A viewer that joins later asks the
                // Server for a keyframe, which lands here as `Refresh`; re-publishing
                // is the only way to satisfy it with this SDK version.
                while let Some(command) = receiver.recv().await {
                    if matches!(command, Command::Refresh) {
                        // Unpublish + publish is the only way to force a keyframe
                        // with this SDK version: a fresh publication starts with one.
                        let sid = publication.sid();
                        if let Err(error) = room.local_participant().unpublish_track(&sid).await {
                            warn!("LiveKit unpublish failed session={session}: {error}");
                        }
                        // A source can back a single publication, so the refresh
                        // installs a new one; capture keeps targeting `source`.
                        source = NativeVideoSource::new(resolution.clone(), true);
                        let track = LocalVideoTrack::create_video_track(
                            "screen",
                            livekit::webrtc::video_source::RtcVideoSource::Native(source.clone()),
                        );
                        match room
                            .local_participant()
                            .publish_track(LocalTrack::Video(track), options.clone())
                            .await
                        {
                            Ok(refreshed) => publication = refreshed,
                            Err(error) => {
                                warn!("LiveKit re-publish failed session={session}: {error}");
                            }
                        }
                        continue;
                    }
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
                        Command::Refresh => {}
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

    fn take_data(&mut self) -> Vec<ReceivedData> {
        let mut drained = Vec::new();
        if let Ok(mut queue) = self.data_queue.lock() {
            while let Some(item) = queue.pop_front() {
                drained.push(item);
            }
        }
        drained
    }

    fn request_keyframe(&mut self) -> bool {
        // The SDK has no keyframe API, so the track is re-published instead: a
        // new publication always starts with a keyframe.
        let Some(sender) = self.sender.as_ref() else {
            return false;
        };
        matches!(sender.try_send(Command::Refresh), Ok(()))
    }

    fn stats(&self) -> Stats {
        self.stats
            .lock()
            .map(|stats| stats.clone())
            .unwrap_or_default()
    }
}

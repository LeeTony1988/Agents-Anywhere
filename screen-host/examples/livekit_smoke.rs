//! Publish a short synthetic I420 stream to a LiveKit room.
//!
//! Usage (PowerShell):
//!   $env:LIVEKIT_URL="ws://127.0.0.1:7880"
//!   $env:LIVEKIT_TOKEN="<host JWT>"
//!   cargo run --features livekit --example livekit_smoke
//!
//! The token must grant `roomJoin` + `canPublish` for `LIVEKIT_ROOM`
//! (default `rd_smoke`). This example does not capture the desktop, so it can
//! run in CI/session-0 environments where WGC is unavailable.

#[cfg(feature = "livekit")]
fn main() {
    use std::time::Duration;

    use aa_screen_host::capture::Frame;
    use aa_screen_host::config::Quality;
    use aa_screen_host::publish::livekit::LiveKitPublisher;
    use aa_screen_host::publish::{MediaGrant, Publisher};

    let url = std::env::var("LIVEKIT_URL").expect("LIVEKIT_URL is required");
    let token = std::env::var("LIVEKIT_TOKEN").expect("LIVEKIT_TOKEN is required");
    let room = std::env::var("LIVEKIT_ROOM").unwrap_or_else(|_| "rd_smoke".to_string());
    let identity = std::env::var("LIVEKIT_IDENTITY")
        .unwrap_or_else(|_| "host:smoke".to_string());
    let mut publisher = LiveKitPublisher::new();
    publisher.start(
        "rds_smoke",
        &MediaGrant {
            url,
            room,
            token,
            identity,
        },
        &Quality {
            max_fps: 30,
            max_bitrate_kbps: 2000,
            max_width: 640,
        },
        640,
        360,
    );
    for index in 0..60u32 {
        let mut i420 = vec![80u8; 640 * 360];
        i420.extend(vec![128u8; 320 * 180]);
        i420.extend(vec![128u8; 320 * 180]);
        // A moving luma stripe keeps the encoder producing frames.
        for y in 0..360usize {
            for x in 0..640usize {
                i420[y * 640 + x] = ((x + index as usize * 4) % 255) as u8;
            }
        }
        publisher.publish_frame(&Frame {
            width: 640,
            height: 360,
            stride: 640,
            i420,
            captured_at: "2026-11-13T00:00:00Z".to_string(),
        });
        std::thread::sleep(Duration::from_millis(33));
    }
    std::thread::sleep(Duration::from_secs(1));
    println!("stats: {:?}", publisher.stats());
    publisher.stop();
}

#[cfg(not(feature = "livekit"))]
fn main() {
    eprintln!("build with --features livekit");
}

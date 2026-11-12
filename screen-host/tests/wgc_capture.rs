//! Real Windows Graphics Capture smoke test (only built with `--features wgc`).
//!
//! It intentionally runs only on an interactive Windows desktop: GitHub's
//! default `cargo test` does not enable `wgc`, so CI stays deterministic.

#![cfg(all(windows, feature = "wgc"))]

use aa_screen_host::capture::windows::WindowsCaptureSource;
use aa_screen_host::capture::CaptureSource;
use aa_screen_host::config::Quality;

#[test]
#[ignore = "requires an interactive Windows desktop; run with --ignored on a real device"]
fn captures_a_non_blank_real_frame() {
    let quality = Quality {
        max_fps: 30,
        max_bitrate_kbps: 4000,
        max_width: 1280,
    };
    let mut source = WindowsCaptureSource::new(quality);
    let displays = source.displays();
    assert!(!displays.is_empty(), "expected at least one display");
    source
        .start(&displays[0].id)
        .expect("WGC capture should start on an interactive desktop");
    let frame = source.next_frame().expect("WGC should produce one frame");
    assert_eq!(frame.stride, frame.width);
    assert_eq!(
        frame.i420.len(),
        (frame.width * frame.height + 2 * (frame.width / 2) * (frame.height / 2)) as usize
    );
    // A fully uniform black frame is what the old stub produced. Require at
    // least some variation so a real desktop content path is exercised.
    let first = *frame.i420.first().unwrap_or(&0);
    assert!(
        frame.i420.iter().any(|byte| *byte != first),
        "captured frame looks blank"
    );
    source.stop();
}

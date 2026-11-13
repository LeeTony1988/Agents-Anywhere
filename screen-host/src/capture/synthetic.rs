//! Synthetic moving picture, for demos and for verifying the media pipeline on
//! machines without an interactive desktop (CI, Windows session 0).
//!
//! It implements the same [`CaptureSource`] contract as the real backends, so
//! everything downstream — VP8 encoder, LiveKit publisher, browser renderer —
//! runs on real pixels instead of a blank frame.
//!
//! Enabled with `AA_SCREEN_HOST_SYNTHETIC_CAPTURE=1`; never the default.

use std::time::{Duration, Instant};

use super::{CaptureError, CaptureSource, DisplayInfo, Frame};
use crate::config::Quality;

/// Frames per second the synthetic source paces itself to.
const SYNTHETIC_FPS: u32 = 15;
const SYNTHETIC_DISPLAY_ID: &str = "synthetic";
const SYNTHETIC_WIDTH: u32 = 640;
const SYNTHETIC_HEIGHT: u32 = 360;

/// True when the environment asks for the synthetic picture.
pub fn synthetic_requested() -> bool {
    matches!(
        std::env::var("AA_SCREEN_HOST_SYNTHETIC_CAPTURE")
            .unwrap_or_default()
            .trim()
            .to_ascii_lowercase()
            .as_str(),
        "1" | "true" | "yes" | "on"
    )
}

/// A capture source that draws a moving gradient so frames are never identical.
pub struct SyntheticCaptureSource {
    width: u32,
    height: u32,
    started: bool,
    frame_index: u64,
    next_deadline: Option<Instant>,
    phase: u8,
}

impl SyntheticCaptureSource {
    pub fn new(quality: Quality) -> Self {
        let width = quality.max_width.clamp(160, SYNTHETIC_WIDTH);
        let height = (width * SYNTHETIC_HEIGHT / SYNTHETIC_WIDTH).max(90);
        Self {
            width: even(width),
            height: even(height),
            started: false,
            frame_index: 0,
            next_deadline: None,
            phase: 0,
        }
    }

    fn build_frame(&mut self) -> Frame {
        let width = self.width;
        let height = self.height;
        let stride = width;
        let chroma_stride = width / 2;
        let mut i420 = vec![0u8; (stride * height + 2 * chroma_stride * (height / 2)) as usize];
        let phase = self.phase;
        for y in 0..height {
            for x in 0..width {
                // A diagonal moving band plus a vertical gradient keeps the
                // encoder busy and makes dropped frames obvious.
                let band = ((x + y + phase as u32) % 64) < 32;
                let base: i32 = if band { 200 } else { 40 };
                let value = (base + (y * 60 / height.max(1)) as i32).clamp(0, 255) as u8;
                i420[(y * stride + x) as usize] = value;
            }
        }
        let chroma_len = chroma_stride * (height / 2);
        let u_offset = (stride * height) as usize;
        for index in 0..chroma_len as usize {
            i420[u_offset + index] = 96;
            i420[u_offset + chroma_len as usize + index] = 160;
        }
        self.frame_index += 1;
        self.phase = ((self.phase as u64 + 7) % 256) as u8;
        Frame {
            width,
            height,
            stride,
            i420,
            captured_at: super::now_rfc3339(),
        }
    }
}

fn even(value: u32) -> u32 {
    if value % 2 == 0 {
        value
    } else {
        value.saturating_sub(1)
    }
}

impl CaptureSource for SyntheticCaptureSource {
    fn displays(&self) -> Vec<DisplayInfo> {
        vec![DisplayInfo {
            id: SYNTHETIC_DISPLAY_ID.to_string(),
            name: "Synthetic display".to_string(),
            width: self.width,
            height: self.height,
            scale: 1.0,
            primary: true,
            refresh_hz: Some(SYNTHETIC_FPS),
            x: 0,
            y: 0,
        }]
    }

    fn start(&mut self, display_id: &str) -> Result<(), CaptureError> {
        if !display_id.is_empty() && display_id != "0" && display_id != SYNTHETIC_DISPLAY_ID {
            return Err(CaptureError::DisplayNotFound(display_id.to_string()));
        }
        self.started = true;
        self.next_deadline = None;
        Ok(())
    }

    fn next_frame(&mut self) -> Result<Frame, CaptureError> {
        if !self.started {
            return Err(CaptureError::Failed("synthetic capture not started".into()));
        }
        // Pace the producer; the publisher drops frames it cannot send.
        let interval = Duration::from_millis(1000 / SYNTHETIC_FPS as u64);
        let deadline = match self.next_deadline {
            Some(previous) => previous + interval,
            None => Instant::now(),
        };
        let now = Instant::now();
        if deadline > now {
            std::thread::sleep(deadline - now);
        }
        self.next_deadline = Some(deadline.max(now));
        Ok(self.build_frame())
    }

    fn stop(&mut self) {
        self.started = false;
        self.next_deadline = None;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn source() -> SyntheticCaptureSource {
        let quality = Quality {
            max_width: 320,
            ..Quality::default()
        };
        SyntheticCaptureSource::new(quality)
    }

    #[test]
    fn exposes_one_primary_display() {
        let displays = source().displays();
        assert_eq!(displays.len(), 1);
        assert!(displays[0].primary);
        assert_eq!(displays[0].id, SYNTHETIC_DISPLAY_ID);
        assert_eq!(displays[0].width % 2, 0);
        assert_eq!(displays[0].height % 2, 0);
    }

    #[test]
    fn start_accepts_primary_aliases_and_rejects_unknown_displays() {
        let mut capture = source();
        assert!(capture.start("0").is_ok());
        assert!(capture.start(SYNTHETIC_DISPLAY_ID).is_ok());
        assert!(capture.start("").is_ok());
        assert!(matches!(
            capture.start("\\\\.\\DISPLAY9"),
            Err(CaptureError::DisplayNotFound(_))
        ));
    }

    #[test]
    fn frames_are_i420_sized_and_change_between_calls() {
        let mut capture = source();
        capture.start("0").unwrap();
        let first = capture.next_frame().unwrap();
        let second = capture.next_frame().unwrap();
        let expected =
            (first.stride * first.height + 2 * (first.width / 2) * (first.height / 2)) as usize;
        assert_eq!(first.i420.len(), expected);
        assert_eq!(first.width, 320);
        assert_ne!(first.i420, second.i420, "synthetic frames must move");
    }

    #[test]
    fn next_frame_before_start_fails() {
        let mut capture = source();
        assert!(capture.next_frame().is_err());
    }
}

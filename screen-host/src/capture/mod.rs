//! Capture source abstraction and platform selection.

pub mod fake;
pub mod synthetic;
#[cfg(all(windows, feature = "wgc"))]
pub mod wgc;
#[cfg(windows)]
pub mod windows;

use serde::{Deserialize, Serialize};
use thiserror::Error;

use crate::config::Quality;

#[derive(Debug, Error)]
pub enum CaptureError {
    #[error("capture failed: {0}")]
    Failed(String),
    #[error("display {0:?} not found")]
    DisplayNotFound(String),
    #[error("capture unsupported on this platform")]
    Unsupported,
}

/// A single I420 video frame.
#[derive(Debug, Clone, PartialEq)]
pub struct Frame {
    pub width: u32,
    pub height: u32,
    /// Row stride of the Y plane in bytes (>= width).
    pub stride: u32,
    /// I420 planes, tightly packed: Y (stride*height), then U, then V
    /// at quarter size with the same stride/2.
    pub i420: Vec<u8>,
    /// RFC 3339 UTC capture timestamp.
    pub captured_at: String,
}

/// Display metadata aligned with `host-info.schema.json#/$defs/DisplayInfo`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct DisplayInfo {
    /// Stable per-host identifier, e.g. `"0"` or `\\\\.\\DISPLAY2`.
    pub id: String,
    pub name: String,
    pub width: u32,
    pub height: u32,
    pub scale: f64,
    pub primary: bool,
    pub refresh_hz: Option<u32>,
    pub x: i32,
    pub y: i32,
}

impl DisplayInfo {
    /// Serialize for the `host.info` result, matching the contract field names.
    pub fn to_json(&self) -> serde_json::Value {
        serde_json::json!({
            "id": self.id,
            "name": self.name,
            "width": self.width,
            "height": self.height,
            "scale": self.scale,
            "primary": self.primary,
            "refreshHz": self.refresh_hz,
            "x": self.x,
            "y": self.y,
        })
    }
}

/// Abstraction over screen capture backends.
pub trait CaptureSource: Send {
    fn displays(&self) -> Vec<DisplayInfo>;
    fn start(&mut self, display_id: &str) -> Result<(), CaptureError>;
    /// Block until the next frame is available.
    fn next_frame(&mut self) -> Result<Frame, CaptureError>;
    fn stop(&mut self);
}

/// RFC 3339 UTC timestamp, used for `Frame::captured_at`.
///
/// `std` has no calendar formatting, so this converts epoch seconds to civil
/// time with Howard Hinnant's days-from-epoch algorithm (valid for the host's
/// operational range).
pub fn now_rfc3339() -> String {
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let days = secs / 86_400;
    let tod = secs % 86_400;
    let (hh, mm, ss) = (tod / 3600, (tod % 3600) / 60, tod % 60);
    let z = days as i64 + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = if m <= 2 { y + 1 } else { y };
    format!("{year:04}-{m:02}-{d:02}T{hh:02}:{mm:02}:{ss:02}Z")
}

/// Pick the platform capture backend. On Windows this is a real
/// [`windows::WindowsCaptureSource`]; elsewhere a [`fake::FakeCaptureSource`] so the
/// host stays functional (and testable) off-platform.
pub fn new_capture_source(quality: Quality) -> Box<dyn CaptureSource> {
    if synthetic::synthetic_requested() {
        // Development/demo path: moving pixels on machines without an
        // interactive desktop (CI, Windows session 0).
        return Box::new(synthetic::SyntheticCaptureSource::new(quality));
    }
    #[cfg(windows)]
    {
        Box::new(windows::WindowsCaptureSource::new(quality))
    }
    #[cfg(not(windows))]
    {
        Box::new(fake::FakeCaptureSource::new(2, 640, 360))
    }
}

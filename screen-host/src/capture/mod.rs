//! Capture source abstraction and platform selection.

pub mod fake;
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

/// Pick the platform capture backend. On Windows this is a real
/// [`windows::WindowsCaptureSource`]; elsewhere a [`fake::FakeCaptureSource`] so the
/// host stays functional (and testable) off-platform.
pub fn new_capture_source(quality: Quality) -> Box<dyn CaptureSource> {
    #[cfg(windows)]
    {
        Box::new(windows::WindowsCaptureSource::new(quality))
    }
    #[cfg(not(windows))]
    {
        Box::new(fake::FakeCaptureSource::new(2, 640, 360))
    }
}

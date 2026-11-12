//! Test capture source producing deterministic synthetic frames.

use super::{CaptureError, CaptureSource, DisplayInfo, Frame};

/// Generates `frame_count` frames of `width`x`height` I420 test pattern.
#[derive(Debug)]
pub struct FakeCaptureSource {
    displays: Vec<DisplayInfo>,
    remaining: u32,
    width: u32,
    height: u32,
    started: bool,
    frame_index: u32,
}

impl FakeCaptureSource {
    pub fn new(frame_count: u32, width: u32, height: u32) -> Self {
        Self {
            displays: vec![DisplayInfo {
                id: "0".to_string(),
                name: "Fake Display".to_string(),
                width,
                height,
                scale: 1.0,
                primary: true,
                refresh_hz: Some(60),
                x: 0,
                y: 0,
            }],
            remaining: frame_count,
            width,
            height,
            started: false,
            frame_index: 0,
        }
    }

    fn synthetic_frame(&mut self) -> Frame {
        let (w, h) = (self.width as usize, self.height as usize);
        let mut i420 = vec![0u8; w * h + 2 * (w / 2) * (h / 2)];
        // Deterministic gradient so frames differ from each other.
        let phase = (self.frame_index % 256) as u8;
        for (i, px) in i420[..w * h].iter_mut().enumerate() {
            *px = ((i % w) as u8).wrapping_add(phase);
        }
        for px in i420[w * h..].iter_mut() {
            *px = 128;
        }
        self.frame_index += 1;
        Frame {
            width: self.width,
            height: self.height,
            stride: self.width,
            i420,
            captured_at: "1970-01-01T00:00:00Z".to_string(),
        }
    }
}

impl CaptureSource for FakeCaptureSource {
    fn displays(&self) -> Vec<DisplayInfo> {
        self.displays.clone()
    }

    fn start(&mut self, display_id: &str) -> Result<(), CaptureError> {
        if !self.displays.iter().any(|d| d.id == display_id) {
            return Err(CaptureError::DisplayNotFound(display_id.to_string()));
        }
        self.started = true;
        self.remaining = self.remaining.max(1);
        Ok(())
    }

    fn next_frame(&mut self) -> Result<Frame, CaptureError> {
        if !self.started {
            return Err(CaptureError::Failed("capture not started".to_string()));
        }
        if self.remaining == 0 {
            return Err(CaptureError::Failed("no more frames".to_string()));
        }
        self.remaining -= 1;
        Ok(self.synthetic_frame())
    }

    fn stop(&mut self) {
        self.started = false;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn produces_configured_frame_count() {
        let mut src = FakeCaptureSource::new(2, 640, 360);
        assert_eq!(src.displays().len(), 1);
        assert_eq!(src.displays()[0].width, 640);
        assert!(src.next_frame().is_err());
        src.start("0").unwrap();
        let f1 = src.next_frame().unwrap();
        let f2 = src.next_frame().unwrap();
        assert_eq!(f1.width, 640);
        assert_eq!(f1.height, 360);
        assert_eq!(f1.stride, 640);
        assert_eq!(f1.i420.len(), 640 * 360 * 3 / 2);
        assert_ne!(f1.i420, f2.i420);
        assert!(src.next_frame().is_err());
        src.stop();
        assert!(src.next_frame().is_err());
    }

    #[test]
    fn rejects_unknown_display() {
        let mut src = FakeCaptureSource::new(1, 320, 180);
        assert!(matches!(
            src.start("99"),
            Err(CaptureError::DisplayNotFound(_))
        ));
    }
}

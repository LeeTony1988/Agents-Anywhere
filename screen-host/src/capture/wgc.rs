//! Windows Graphics Capture (WGC) backend, enabled by the `wgc` feature.
//!
//! `windows-capture` runs the WinRT capture loop on its own thread and hands us
//! BGRA8 frames. We keep only the newest frame in a small channel and convert it
//! to the I420 layout expected by the publisher. The default build does not pull
//! this dependency in, so non-Windows CI and quick `cargo test` stay fast.

use std::sync::mpsc::{sync_channel, Receiver, SyncSender};
use std::time::Duration;

use windows_capture::capture::{Context, GraphicsCaptureApiHandler};
use windows_capture::dxgi_duplication_api::{DxgiDuplicationApi, DxgiDuplicationFormat};
use windows_capture::frame::Frame as WgcFrame;
use windows_capture::graphics_capture_api::InternalCaptureControl;
use windows_capture::monitor::Monitor;
use windows_capture::settings::{
    ColorFormat, CursorCaptureSettings, DirtyRegionSettings, DrawBorderSettings,
    MinimumUpdateIntervalSettings, SecondaryWindowSettings, Settings,
};

use super::{CaptureError, Frame};
use crate::config::Quality;

const FRAME_TIMEOUT: Duration = Duration::from_secs(3);

struct CapturedFrame {
    width: u32,
    height: u32,
    bgra: Vec<u8>,
}

struct Handler {
    sender: SyncSender<CapturedFrame>,
    scratch: Vec<u8>,
}

impl GraphicsCaptureApiHandler for Handler {
    type Flags = ();
    type Error = Box<dyn std::error::Error + Send + Sync>;

    fn new(_ctx: Context<Self::Flags>) -> Result<Self, Self::Error> {
        let (sender, _receiver) = sync_channel(1);
        // The receiver is installed by `WgcCapture::new`; this constructor is
        // only used by the crate's internal thread setup, so the sender is
        // replaced there through `Handler::with_sender`.
        Ok(Self {
            sender,
            scratch: Vec::new(),
        })
    }

    fn on_frame_arrived(
        &mut self,
        frame: &mut WgcFrame<'_>,
        _capture_control: InternalCaptureControl,
    ) -> Result<(), Self::Error> {
        let width = frame.width();
        let height = frame.height();
        let buffer = frame.buffer()?;
        let data = buffer.as_nopadding_buffer(&mut self.scratch);
        // One-slot channel: a slow consumer drops older frames, which is the
        // desired behavior for a screen stream.
        let _ = self.sender.try_send(CapturedFrame {
            width,
            height,
            bgra: data.to_vec(),
        });
        Ok(())
    }
}

pub struct DxgiCapture {
    api: DxgiDuplicationApi,
    target_width: u32,
    target_height: u32,
    scratch: Vec<u8>,
}

impl DxgiCapture {
    pub fn new(display_id: &str, quality: Quality) -> Result<Self, CaptureError> {
        let index = display_id
            .parse::<usize>()
            .map_err(|_| CaptureError::DisplayNotFound(display_id.to_string()))?;
        let monitor = if index == 1 {
            Monitor::primary()
                .map_err(|err| CaptureError::Failed(format!("DXGI primary monitor: {err}")))?
        } else {
            Monitor::from_index(index)
                .map_err(|err| CaptureError::Failed(format!("DXGI monitor: {err}")))?
        };
        let api = DxgiDuplicationApi::new_options(monitor, &[DxgiDuplicationFormat::Bgra8])
            .map_err(|err| CaptureError::Failed(format!("DXGI duplication: {err}")))?;
        let width = api.width().max(2);
        let height = api.height().max(2);
        let target_width = quality.max_width.min(width).max(2) & !1;
        let target_height = ((height as f64 * (target_width as f64 / width as f64))
            .round()
            .max(2.0) as u32)
            & !1;
        Ok(Self {
            api,
            target_width,
            target_height,
            scratch: Vec::new(),
        })
    }

    pub fn next_frame(&mut self) -> Result<Frame, CaptureError> {
        for _ in 0..30 {
            let mut frame = self
                .api
                .acquire_next_frame(33)
                .map_err(|err| CaptureError::Failed(format!("DXGI frame: {err}")))?;
            let width = frame.width();
            let height = frame.height();
            let buffer = frame
                .buffer()
                .map_err(|err| CaptureError::Failed(format!("DXGI buffer: {err}")))?;
            let data = buffer.as_nopadding_buffer(&mut self.scratch);
            if data.iter().any(|byte| *byte != 0) {
                let i420 = bgra_to_i420(data, width, height, self.target_width, self.target_height);
                return Ok(Frame {
                    width: self.target_width,
                    height: self.target_height,
                    stride: self.target_width,
                    i420,
                    captured_at: now_rfc3339(),
                });
            }
        }
        Err(CaptureError::Failed(
            "DXGI produced only empty frames".to_string(),
        ))
    }

    pub fn stop(&mut self) {
        // Dropping the duplication session is sufficient; there is no thread.
    }
}

pub struct WgcCapture {
    receiver: Receiver<CapturedFrame>,
    control: Option<
        windows_capture::capture::CaptureControl<Handler, Box<dyn std::error::Error + Send + Sync>>,
    >,
    target_width: u32,
    target_height: u32,
}

impl WgcCapture {
    pub fn new(display_id: &str, quality: Quality) -> Result<Self, CaptureError> {
        let index = display_id
            .parse::<usize>()
            .map_err(|_| CaptureError::DisplayNotFound(display_id.to_string()))?;
        let monitor = if index == 1 {
            Monitor::primary()
                .map_err(|err| CaptureError::Failed(format!("WGC primary monitor: {err}")))?
        } else {
            Monitor::from_index(index)
                .map_err(|err| CaptureError::Failed(format!("WGC monitor: {err}")))?
        };
        let width = monitor
            .width()
            .map_err(|err| CaptureError::Failed(format!("WGC width: {err}")))?;
        let height = monitor
            .height()
            .map_err(|err| CaptureError::Failed(format!("WGC height: {err}")))?;
        let (sender, receiver) = sync_channel(1);
        let settings = Settings::new(
            monitor,
            CursorCaptureSettings::WithCursor,
            DrawBorderSettings::WithoutBorder,
            SecondaryWindowSettings::Default,
            MinimumUpdateIntervalSettings::Custom(Duration::from_millis(16)),
            DirtyRegionSettings::Default,
            ColorFormat::Bgra8,
            (),
        );
        let control = Handler::start_free_threaded(settings)
            .map_err(|err| CaptureError::Failed(format!("WGC start: {err}")))?;
        // `start_free_threaded` builds its handler via `new`, which creates its
        // own sender. Replace the channel in the shared handler so frames arrive
        // on the channel owned by this object.
        {
            let callback = control.callback();
            let mut handler = callback.lock();
            handler.sender = sender;
        }
        let target_width = quality.max_width.min(width).max(2) & !1;
        let target_height = ((height as f64 * (target_width as f64 / width.max(1) as f64))
            .round()
            .max(2.0) as u32)
            & !1;
        Ok(Self {
            receiver,
            control: Some(control),
            target_width,
            target_height,
        })
    }

    pub fn next_frame(&mut self) -> Result<Frame, CaptureError> {
        let captured = self
            .receiver
            .recv_timeout(FRAME_TIMEOUT)
            .map_err(|_| CaptureError::Failed("WGC produced no frame".to_string()))?;
        let (width, height) = (self.target_width, self.target_height);
        let i420 = bgra_to_i420(
            &captured.bgra,
            captured.width,
            captured.height,
            width,
            height,
        );
        Ok(Frame {
            width,
            height,
            stride: width,
            i420,
            captured_at: now_rfc3339(),
        })
    }

    pub fn stop(&mut self) {
        if let Some(control) = self.control.take() {
            let _ = control.stop();
        }
    }
}

impl Drop for WgcCapture {
    fn drop(&mut self) {
        self.stop();
    }
}

/// Prefer WGC (handles hybrid GPUs and modern desktop composition); fall back
/// to DXGI Desktop Duplication when WGC cannot create a capture item, which is
/// common in virtualized/RDP sessions.
pub enum WindowsBackend {
    Wgc(WgcCapture),
    Dxgi(DxgiCapture),
}

impl WindowsBackend {
    pub fn new(display_id: &str, quality: Quality) -> Result<Self, CaptureError> {
        match WgcCapture::new(display_id, quality) {
            Ok(capture) => Ok(Self::Wgc(capture)),
            Err(wgc_error) => {
                log::warn!("WGC unavailable ({wgc_error}); falling back to DXGI duplication");
                DxgiCapture::new(display_id, quality).map(Self::Dxgi)
            }
        }
    }

    pub fn next_frame(&mut self) -> Result<Frame, CaptureError> {
        match self {
            Self::Wgc(capture) => capture.next_frame(),
            Self::Dxgi(capture) => capture.next_frame(),
        }
    }

    pub fn stop(&mut self) {
        match self {
            Self::Wgc(capture) => capture.stop(),
            Self::Dxgi(capture) => capture.stop(),
        }
    }
}

/// Nearest-neighbour BGRA8 -> I420 conversion with optional downscaling.
fn bgra_to_i420(
    src: &[u8],
    src_width: u32,
    src_height: u32,
    dst_width: u32,
    dst_height: u32,
) -> Vec<u8> {
    let (sw, sh) = (src_width.max(1) as usize, src_height.max(1) as usize);
    let (dw, dh) = (dst_width.max(2) as usize, dst_height.max(2) as usize);
    let mut y_plane = vec![0u8; dw * dh];
    let mut u_plane = vec![0u8; (dw / 2) * (dh / 2)];
    let mut v_plane = vec![0u8; (dw / 2) * (dh / 2)];

    // Luma: area-average every destination pixel. Nearest-neighbour sampling (the old
    // implementation) is cheap but aliases small text and thin lines when the desktop
    // is downscaled; the area filter keeps much more of the glyph shape.
    for dy in 0..dh {
        let sy0 = dy * sh / dh;
        let sy1 = ((dy + 1) * sh / dh).max(sy0 + 1).min(sh);
        for dx in 0..dw {
            let sx0 = dx * sw / dw;
            let sx1 = ((dx + 1) * sw / dw).max(sx0 + 1).min(sw);
            let (r, g, b) = average_bgra_rect(src, sw, sx0, sy0, sx1, sy1);
            let y = 0.299 * r + 0.587 * g + 0.114 * b;
            y_plane[dy * dw + dx] = y.clamp(0.0, 255.0) as u8;
        }
    }

    // Chroma: one sample per 2x2 destination block, averaged over the full source area
    // covered by that block. This avoids the old top-left chroma sample and its colour
    // fringes on ClearType text.
    for by in 0..(dh / 2) {
        let sy0 = by * 2 * sh / dh;
        let sy1 = ((by * 2 + 2) * sh / dh).max(sy0 + 1).min(sh);
        for bx in 0..(dw / 2) {
            let sx0 = bx * 2 * sw / dw;
            let sx1 = ((bx * 2 + 2) * sw / dw).max(sx0 + 1).min(sw);
            let (r, g, b) = average_bgra_rect(src, sw, sx0, sy0, sx1, sy1);
            let index = by * (dw / 2) + bx;
            u_plane[index] =
                (-0.168_736 * r - 0.331_264 * g + 0.5 * b + 128.0).clamp(0.0, 255.0) as u8;
            v_plane[index] =
                (0.5 * r - 0.418_688 * g - 0.081_312 * b + 128.0).clamp(0.0, 255.0) as u8;
        }
    }

    let mut out = Vec::with_capacity(y_plane.len() + u_plane.len() + v_plane.len());
    out.extend_from_slice(&y_plane);
    out.extend_from_slice(&u_plane);
    out.extend_from_slice(&v_plane);
    out
}

/// Average BGRA colours in the half-open source rectangle `[x0,x1) × [y0,y1)`.
fn average_bgra_rect(
    src: &[u8],
    src_width: usize,
    x0: usize,
    y0: usize,
    x1: usize,
    y1: usize,
) -> (f32, f32, f32) {
    let mut r = 0.0f32;
    let mut g = 0.0f32;
    let mut b = 0.0f32;
    let mut count = 0u32;
    for sy in y0..y1 {
        let row = sy * src_width;
        for sx in x0..x1 {
            let offset = (row + sx) * 4;
            if offset + 3 >= src.len() {
                continue;
            }
            b += src[offset] as f32;
            g += src[offset + 1] as f32;
            r += src[offset + 2] as f32;
            count += 1;
        }
    }
    if count == 0 {
        (0.0, 0.0, 0.0)
    } else {
        let divisor = count as f32;
        (r / divisor, g / divisor, b / divisor)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bgra(width: usize, height: usize, channels: [u8; 4]) -> Vec<u8> {
        channels
            .iter()
            .copied()
            .cycle()
            .take(width * height * 4)
            .collect()
    }

    #[test]
    fn downscale_averages_source_blocks_instead_of_sampling_one_pixel() {
        // 4x4: left two columns white, right two columns black; 2x2 output.
        let mut src = Vec::with_capacity(4 * 4 * 4);
        for _y in 0..4 {
            for x in 0..4 {
                let value = if x < 2 { 255 } else { 0 };
                src.extend_from_slice(&[value, value, value, 255]);
            }
        }

        let i420 = bgra_to_i420(&src, 4, 4, 2, 2);

        // Luma plane: the left destination column is white, the right is black.
        assert_eq!(&i420[..4], &[255, 0, 255, 0]);
    }

    #[test]
    fn one_to_one_keeps_every_pixel_and_neutral_chroma() {
        let src = bgra(2, 2, [255, 255, 255, 255]);
        let i420 = bgra_to_i420(&src, 2, 2, 2, 2);
        assert_eq!(&i420[..4], &[255, 255, 255, 255]);
        assert_eq!(&i420[4..], &[128, 128]);
    }
}

fn now_rfc3339() -> String {
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
    let y = if m <= 2 { y + 1 } else { y };
    format!("{y:04}-{m:02}-{d:02}T{hh:02}:{mm:02}:{ss:02}Z")
}

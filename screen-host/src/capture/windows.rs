//! Windows capture source.
//!
//! Display enumeration is real (`EnumDisplayMonitors` + `GetMonitorInfoW` + DPI
//! awareness). Frame capture is a WGC placeholder: with the `wgc` feature enabled
//! it is still TODO (RD-P2-10) and returns `rd_capture_failed`; without the feature
//! it is a bounded stub that yields blank frames so the publish pipeline can be
//! exercised without a capture backend.

use log::warn;

use windows::Win32::Foundation::{LPARAM, RECT};
use windows::Win32::Graphics::Gdi::{
    EnumDisplayMonitors, GetMonitorInfoW, HDC, HMONITOR, MONITORINFOEXW,
};
use windows::Win32::UI::HiDpi::GetDpiForSystem;
use windows::Win32::UI::WindowsAndMessaging::MONITORINFOF_PRIMARY;

#[cfg(test)]
use windows::Win32::UI::WindowsAndMessaging::{GetSystemMetrics, SM_CXSCREEN, SM_CYSCREEN};

use super::now_rfc3339;
#[cfg(feature = "wgc")]
use super::wgc::WindowsBackend;
use super::{CaptureError, CaptureSource, DisplayInfo, Frame};
use crate::config::Quality;
use crate::protocol::ERR_CAPTURE_FAILED;

const STUB_FRAMES: u32 = 300;

struct EnumState {
    displays: Vec<DisplayInfo>,
    next_index: u32,
    /// Set when the callback fires on a monitor (i.e. EnumDisplayMonitors is working).
    enumerated_any: bool,
}

unsafe extern "system" fn monitor_enum_proc(
    hmonitor: HMONITOR,
    _hdc: HDC,
    _clip: *mut RECT,
    lparam: LPARAM,
) -> windows::core::BOOL {
    use windows::core::BOOL;
    // SAFETY: lparam points to the EnumState living on the caller's stack for the
    // duration of EnumDisplayMonitors.
    let state = unsafe { &mut *(lparam.0 as *mut EnumState) };
    state.enumerated_any = true;
    let mut info = MONITORINFOEXW::default();
    // SAFETY: info is a valid, zero-initialised MONITORINFOEXW owned by this stack frame.
    let ok = unsafe { GetMonitorInfoW(hmonitor, &mut info as *mut _ as *mut _).as_bool() };
    if !ok {
        return BOOL(1); // continue enumeration
    }
    let index = state.next_index;
    state.next_index += 1;
    let primary = (info.monitorInfo.dwFlags & MONITORINFOF_PRIMARY) != 0;
    let width = (info.monitorInfo.rcMonitor.right - info.monitorInfo.rcMonitor.left).max(1) as u32;
    let height = (info.monitorInfo.rcMonitor.bottom - info.monitorInfo.rcMonitor.top).max(1) as u32;
    let name = String::from_utf16_lossy(&info.szDevice)
        .trim_end_matches('\0')
        .to_string();
    state.displays.push(DisplayInfo {
        id: index.to_string(),
        name: if name.is_empty() {
            format!("DISPLAY{index}")
        } else {
            name
        },
        width,
        height,
        scale: system_scale(),
        primary,
        refresh_hz: None,
        x: info.monitorInfo.rcMonitor.left,
        y: info.monitorInfo.rcMonitor.top,
    });
    BOOL(1) // continue
}

fn system_scale() -> f64 {
    // SAFETY: no parameters; returns the system DPI.
    let dpi = unsafe { GetDpiForSystem() };
    if dpi == 0 {
        1.0
    } else {
        dpi as f64 / 96.0
    }
}

/// Real display enumeration + capture backend selection.
pub struct WindowsCaptureSource {
    quality: Quality,
    started: bool,
    display_id: String,
    remaining_stub_frames: u32,
    width: u32,
    height: u32,
    #[cfg(feature = "wgc")]
    wgc: Option<WindowsBackend>,
}

impl WindowsCaptureSource {
    pub fn new(quality: Quality) -> Self {
        Self {
            quality,
            started: false,
            display_id: String::new(),
            remaining_stub_frames: 0,
            width: 0,
            height: 0,
            #[cfg(feature = "wgc")]
            wgc: None,
        }
    }
}

impl CaptureSource for WindowsCaptureSource {
    fn displays(&self) -> Vec<DisplayInfo> {
        enumerate_displays()
    }

    fn start(&mut self, display_id: &str) -> Result<(), CaptureError> {
        let displays = enumerate_displays();
        let display = displays
            .iter()
            .find(|d| d.id == display_id)
            .ok_or_else(|| CaptureError::DisplayNotFound(display_id.to_string()))?;
        self.display_id = display_id.to_string();
        self.width = display.width.min(self.quality.max_width);
        self.height = (display.height as f64 * (self.width as f64 / display.width.max(1) as f64))
            .round()
            .max(1.0) as u32;
        self.started = true;
        self.remaining_stub_frames = STUB_FRAMES;
        #[cfg(feature = "wgc")]
        {
            self.wgc = Some(WindowsBackend::new(display_id, self.quality)?);
        }
        Ok(())
    }

    fn next_frame(&mut self) -> Result<Frame, CaptureError> {
        if !self.started {
            return Err(CaptureError::Failed("capture not started".to_string()));
        }
        #[cfg(feature = "wgc")]
        if let Some(capture) = self.wgc.as_mut() {
            return capture.next_frame();
        }
        if self.remaining_stub_frames == 0 {
            return Err(CaptureError::Failed(format!(
                "{ERR_CAPTURE_FAILED}: WGC capture not implemented yet (TODO RD-P2-10)"
            )));
        }
        self.remaining_stub_frames -= 1;
        warn!(
            "WGC capture not implemented yet (TODO RD-P2-10); emitting blank stub frame for display {}",
            self.display_id
        );
        let (w, h) = (self.width as usize, self.height as usize);
        Ok(Frame {
            width: self.width,
            height: self.height,
            stride: self.width,
            i420: vec![0u8; w * h + 2 * (w / 2) * (h / 2)],
            captured_at: now_rfc3339(),
        })
    }

    fn stop(&mut self) {
        self.started = false;
        self.remaining_stub_frames = 0;
        #[cfg(feature = "wgc")]
        if let Some(capture) = self.wgc.as_mut() {
            capture.stop();
        }
    }
}

/// Enumerate displays attached to the interactive desktop. Falls back to the
/// primary screen's system metrics when no monitor can be enumerated (headless
/// sessions), so the host stays addressable.
fn enumerate_displays() -> Vec<DisplayInfo> {
    // NOTE: we deliberately do NOT call SetProcessDpiAwarenessContext here. When the
    // host runs inside a parent that is not DPI aware (e.g. a connector hosting us
    // on its default desktop), forcing per-monitor awareness makes
    // EnumDisplayMonitors fail outright. The rects returned without it are scaled,
    // but functional; DPI-correct sizing is revisited with real WGC (RD-P2-10).
    let mut state = EnumState {
        displays: Vec::new(),
        // IDs match what the OS reports (\\.\DISPLAY1 is index 1 on most systems).
        next_index: 1,
        enumerated_any: false,
    };
    // SAFETY: state outlives the call and is only mutated from this thread.
    let enum_ok = unsafe {
        EnumDisplayMonitors(
            None,
            None,
            Some(monitor_enum_proc),
            LPARAM(&mut state as *mut EnumState as isize),
        )
        .as_bool()
    };
    if !enum_ok {
        warn!("EnumDisplayMonitors failed (no interactive desktop?); using fallback");
    }
    // Fallback for environments where EnumDisplayMonitors enumerates nothing or
    // fails (headless/hidden sessions): report the primary screen from system
    // metrics so the host remains addressable. Real captures still require an
    // interactive desktop.
    if state.displays.is_empty() {
        // SAFETY: GetSystemMetrics with SM_CXSCREEN/SM_CYSCREEN takes no parameters.
        let (w, h) = unsafe {
            (
                windows::Win32::UI::WindowsAndMessaging::GetSystemMetrics(
                    windows::Win32::UI::WindowsAndMessaging::SM_CXSCREEN,
                ),
                windows::Win32::UI::WindowsAndMessaging::GetSystemMetrics(
                    windows::Win32::UI::WindowsAndMessaging::SM_CYSCREEN,
                ),
            )
        };
        state.displays.push(DisplayInfo {
            id: "1".to_string(),
            name: "DISPLAY1".to_string(),
            width: w.max(1) as u32,
            height: h.max(1) as u32,
            scale: system_scale(),
            primary: true,
            refresh_hz: None,
            x: 0,
            y: 0,
        });
    }
    state.displays
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn enumerates_displays_or_falls_back() {
        let displays = enumerate_displays();
        // On a real desktop this lists the attached monitors; in headless CI it
        // falls back to a single primary display entry. Either way it must not panic
        // and must produce sane geometry.
        assert!(!displays.is_empty(), "expected at least one display");
        for d in &displays {
            assert!(d.width >= 1 && d.height >= 1, "bad geometry: {d:?}");
            assert!(d.scale > 0.0);
        }
        if displays.len() == 1 {
            // SAFETY: GetSystemMetrics with SM_CXSCREEN/SM_CYSCREEN takes no params.
            let (w, h) = unsafe { (GetSystemMetrics(SM_CXSCREEN), GetSystemMetrics(SM_CYSCREEN)) };
            assert_eq!(displays[0].width, w.max(1) as u32);
            assert_eq!(displays[0].height, h.max(1) as u32);
            assert!(displays[0].primary);
        } else {
            assert!(
                displays.iter().any(|d| d.primary),
                "expected a primary display"
            );
        }
    }

    #[test]
    fn now_rfc3339_format() {
        let s = now_rfc3339();
        assert_eq!(s.len(), 20);
        assert!(s.ends_with('Z'));
        assert_eq!(&s[4..5], "-");
        assert_eq!(&s[10..11], "T");
    }
}

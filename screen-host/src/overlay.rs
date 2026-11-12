//! Persistent Windows sharing indicator for the screen host.
//!
//! A small topmost, non-activating popup says the screen is being shared and
//! can be clicked to request a graceful stop. The request is exposed through
//! [`stop_requested`], which the stdio loop checks between frames; the popup
//! itself never calls `ExitProcess`, so the host still runs release-all/stop.

#![cfg(windows)]

use std::sync::atomic::{AtomicBool, AtomicIsize, Ordering};
use std::sync::{mpsc, Arc, Once};
use std::thread::JoinHandle;

use log::{info, warn};
use windows::core::PCWSTR;
use windows::Win32::Foundation::{COLORREF, HINSTANCE, HWND, LPARAM, LRESULT, RECT, WPARAM};
use windows::Win32::Graphics::Gdi::{
    BeginPaint, CreateSolidBrush, DeleteObject, EndPaint, FillRect, GetStockObject, SelectObject,
    SetBkMode, SetTextColor, TextOutW, UpdateWindow, DEFAULT_GUI_FONT, PAINTSTRUCT, TRANSPARENT,
};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::UI::WindowsAndMessaging::{
    CreateWindowExW, DefWindowProcW, DestroyWindow, DispatchMessageW, GetClientRect, GetMessageW,
    LoadCursorW, PostMessageW, PostQuitMessage, RegisterClassW, SetLayeredWindowAttributes,
    SetTimer, SetWindowPos, ShowWindow, TranslateMessage, CS_HREDRAW, CS_VREDRAW, HWND_TOPMOST,
    IDC_ARROW, LWA_ALPHA, MSG, SWP_NOACTIVATE, SWP_NOSIZE, SW_SHOWNOACTIVATE, WM_CLOSE, WM_DESTROY,
    WM_LBUTTONUP, WM_PAINT, WM_TIMER, WNDCLASSW, WS_EX_LAYERED, WS_EX_TOOLWINDOW, WS_EX_TOPMOST,
    WS_POPUP, WS_VISIBLE,
};

static STOP_REQUESTED: AtomicBool = AtomicBool::new(false);
static OVERLAY_STOP: AtomicIsize = AtomicIsize::new(0);
static REGISTER_CLASS: Once = Once::new();

/// Set by the popup (or another local surface) to ask the Host for `shutdown`.
pub fn stop_requested() -> bool {
    STOP_REQUESTED.load(Ordering::Relaxed)
}

pub fn clear_stop_request() {
    STOP_REQUESTED.store(false, Ordering::Relaxed);
}

fn wide(value: &str) -> Vec<u16> {
    value.encode_utf16().chain(std::iter::once(0)).collect()
}

unsafe extern "system" fn wndproc(
    hwnd: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    match message {
        WM_PAINT => {
            let mut paint = PAINTSTRUCT::default();
            let hdc = unsafe { BeginPaint(hwnd, &mut paint) };
            let mut rect = RECT::default();
            let _ = unsafe { GetClientRect(hwnd, &mut rect) };
            let brush = unsafe { CreateSolidBrush(COLORREF(0x0020_2020)) };
            unsafe {
                FillRect(hdc, &rect, brush);
                let _ = DeleteObject(brush.into());
            }
            let font = unsafe { GetStockObject(DEFAULT_GUI_FONT) };
            unsafe {
                SelectObject(hdc, font);
                SetBkMode(hdc, TRANSPARENT);
                SetTextColor(hdc, COLORREF(0x00FF_FFFF));
            }
            let text = wide("Agents Anywhere 正在共享屏幕 · 点击结束共享");
            unsafe {
                let _ = TextOutW(hdc, 16, 16, &text[..text.len() - 1]);
                let _ = EndPaint(hwnd, &paint);
            }
            LRESULT(0)
        }
        WM_LBUTTONUP => {
            STOP_REQUESTED.store(true, Ordering::Relaxed);
            let _ = unsafe { DestroyWindow(hwnd) };
            LRESULT(0)
        }
        WM_TIMER => {
            if stop_requested() {
                let _ = unsafe { DestroyWindow(hwnd) };
            }
            LRESULT(0)
        }
        WM_DESTROY => {
            OVERLAY_STOP.store(0, Ordering::Relaxed);
            unsafe { PostQuitMessage(0) };
            LRESULT(0)
        }
        WM_CLOSE => {
            let _ = unsafe { DestroyWindow(hwnd) };
            LRESULT(0)
        }
        _ => unsafe { DefWindowProcW(hwnd, message, wparam, lparam) },
    }
}

pub struct OverlayHandle {
    hwnd: Arc<AtomicIsize>,
    thread: Option<JoinHandle<()>>,
}

impl OverlayHandle {
    pub fn show() -> Option<Self> {
        clear_stop_request();
        let (sender, receiver) = mpsc::channel::<isize>();
        let hwnd = Arc::new(AtomicIsize::new(0));
        let thread_hwnd = Arc::clone(&hwnd);
        let thread = std::thread::Builder::new()
            .name("rd-sharing-overlay".to_string())
            .spawn(move || unsafe {
                REGISTER_CLASS.call_once(|| {
                    let instance = GetModuleHandleW(None)
                        .ok()
                        .map(|module| HINSTANCE(module.0));
                    let class_name = wide("AgentsAnywhereSharingOverlay");
                    let window_class = WNDCLASSW {
                        style: CS_HREDRAW | CS_VREDRAW,
                        lpfnWndProc: Some(wndproc),
                        hInstance: instance.unwrap_or_default(),
                        hCursor: LoadCursorW(None, IDC_ARROW).unwrap_or_default(),
                        hbrBackground: CreateSolidBrush(COLORREF(0x0020_2020)),
                        lpszClassName: PCWSTR(class_name.as_ptr()),
                        ..Default::default()
                    };
                    let _ = RegisterClassW(&window_class);
                });
                let class_name = wide("AgentsAnywhereSharingOverlay");
                let title = wide("Agents Anywhere");
                let instance = GetModuleHandleW(None)
                    .ok()
                    .map(|module| HINSTANCE(module.0));
                let width = 410;
                let height = 56;
                let screen_width = windows::Win32::UI::WindowsAndMessaging::GetSystemMetrics(
                    windows::Win32::UI::WindowsAndMessaging::SM_CXSCREEN,
                );
                let hwnd = match CreateWindowExW(
                    WS_EX_TOPMOST | WS_EX_TOOLWINDOW | WS_EX_LAYERED,
                    PCWSTR(class_name.as_ptr()),
                    PCWSTR(title.as_ptr()),
                    WS_POPUP | WS_VISIBLE,
                    screen_width - width - 20,
                    20,
                    width,
                    height,
                    None,
                    None,
                    instance,
                    None,
                ) {
                    Ok(hwnd) => hwnd,
                    Err(error) => {
                        warn!("sharing overlay create failed: {error}");
                        let _ = sender.send(0);
                        return;
                    }
                };
                let _ = SetLayeredWindowAttributes(hwnd, COLORREF(0), 235, LWA_ALPHA);
                let _ = SetWindowPos(
                    hwnd,
                    Some(HWND_TOPMOST),
                    screen_width - width - 20,
                    20,
                    0,
                    0,
                    SWP_NOSIZE | SWP_NOACTIVATE,
                );
                let _ = ShowWindow(hwnd, SW_SHOWNOACTIVATE);
                let _ = UpdateWindow(hwnd);
                let _ = SetTimer(Some(hwnd), 1, 250, None);
                thread_hwnd.store(hwnd.0 as isize, Ordering::Relaxed);
                let _ = sender.send(hwnd.0 as isize);
                info!("sharing overlay visible");
                let mut message = MSG::default();
                loop {
                    let result = GetMessageW(&mut message, None, 0, 0);
                    if result.0 <= 0 {
                        break;
                    }
                    let _ = TranslateMessage(&message);
                    DispatchMessageW(&message);
                }
            })
            .ok()?;
        match receiver.recv_timeout(std::time::Duration::from_secs(2)) {
            Ok(0) | Err(_) => {
                let _ = thread.join();
                None
            }
            Ok(handle) => {
                hwnd.store(handle, Ordering::Relaxed);
                Some(Self {
                    hwnd,
                    thread: Some(thread),
                })
            }
        }
    }

    pub fn stop(&mut self) {
        let handle = self.hwnd.swap(0, Ordering::Relaxed);
        if handle != 0 {
            let _ = unsafe {
                PostMessageW(Some(HWND(handle as *mut _)), WM_CLOSE, WPARAM(0), LPARAM(0))
            };
        }
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

impl Drop for OverlayHandle {
    fn drop(&mut self) {
        self.stop();
    }
}

//! Windows input injection via `SendInput` (RD-P2-12).
//!
//! - Mouse move / button down / up / wheel through `INPUT_MOUSE`.
//! - Key down / up through `INPUT_KEYBOARD`.
//! - Unicode text through `KEYEVENTF_UNICODE` (paste path, per decisions §7.6 rule 3).
//! - Maintains pressed key/button sets and releases everything on `release_all`
//!   (startup panic-release, `host.stop`, EOF and crash paths all funnel here).

use std::collections::BTreeSet;
use std::sync::atomic::Ordering;

use log::warn;
use windows::Win32::UI::Input::KeyboardAndMouse::{
    SendInput, INPUT, INPUT_0, INPUT_KEYBOARD, INPUT_MOUSE, KEYBDINPUT, KEYBD_EVENT_FLAGS,
    KEYEVENTF_KEYUP, KEYEVENTF_UNICODE, MOUSEEVENTF_ABSOLUTE, MOUSEEVENTF_LEFTDOWN,
    MOUSEEVENTF_LEFTUP, MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP, MOUSEEVENTF_MOVE,
    MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP, MOUSEEVENTF_VIRTUALDESK, MOUSEEVENTF_WHEEL,
    MOUSEINPUT, MOUSE_EVENT_FLAGS, VIRTUAL_KEY,
};
use windows::Win32::UI::WindowsAndMessaging::{
    GetSystemMetrics, SM_CXSCREEN, SM_CXVIRTUALSCREEN, SM_CYSCREEN, SM_CYVIRTUALSCREEN,
    SM_XVIRTUALSCREEN, SM_YVIRTUALSCREEN,
};

use super::{
    ClipboardAction, InputEvent, InputEventError, InputSink, KeyAction, KeyMod, PointerAction,
    PointerButton, WheelUnit,
};

/// Lines per wheel notch for line-unit scroll (SPI_GETWHEELSCROLLLINES default).
const LINES_PER_NOTCH: i32 = 3;

/// Converts the protocol's `dy` (positive means scroll down, matching browser
/// `deltaY`) into `MOUSEEVENTF_WHEEL` data. Windows treats a positive wheel delta as
/// rotating away from the user, which scrolls up, so the sign flips exactly once here.
fn wheel_delta(dy: f64, unit: WheelUnit) -> i32 {
    let magnitude = match unit {
        WheelUnit::Pixel => dy.round() as i32,
        WheelUnit::Line => (dy * LINES_PER_NOTCH as f64).round() as i32,
    };
    -magnitude
}

/// W3C `KeyboardEvent.code` -> Windows virtual-key code, for the subset a remote
/// desktop controller needs. Unknown codes are logged and dropped.
fn w3c_code_to_vk(code: &str) -> Option<u16> {
    Some(match code {
        // Letters / digits (physical row is irrelevant for injection).
        c if c.len() == 4 && c.starts_with("Key") => c.as_bytes()[3].to_ascii_uppercase() as u16,
        c if c.len() == 6 && c.starts_with("Digit") => c.as_bytes()[5] as u16 - b'0' as u16 + 0x30,
        "Escape" => 0x1B,
        "Tab" => 0x09,
        "CapsLock" => 0x14,
        "ShiftLeft" | "ShiftRight" => 0x10,
        "ControlLeft" | "ControlRight" => 0x11,
        "AltLeft" | "AltRight" => 0x12,
        "MetaLeft" | "MetaRight" => 0x5B,
        "Space" => 0x20,
        "Enter" | "NumpadEnter" => 0x0D,
        "Backspace" => 0x08,
        "Delete" => 0x2E,
        "Insert" => 0x2D,
        "Home" => 0x24,
        "End" => 0x23,
        "PageUp" => 0x21,
        "PageDown" => 0x22,
        "ArrowUp" => 0x26,
        "ArrowDown" => 0x28,
        "ArrowLeft" => 0x25,
        "ArrowRight" => 0x27,
        "F1" => 0x70,
        "F2" => 0x71,
        "F3" => 0x72,
        "F4" => 0x73,
        "F5" => 0x74,
        "F6" => 0x75,
        "F7" => 0x76,
        "F8" => 0x77,
        "F9" => 0x78,
        "F10" => 0x79,
        "F11" => 0x7A,
        "F12" => 0x7B,
        "Minus" => 0xBD,
        "Equal" => 0xBB,
        "BracketLeft" => 0xDB,
        "BracketRight" => 0xDD,
        "Backslash" => 0xDC,
        "Semicolon" => 0xBA,
        "Quote" => 0xDE,
        "Comma" => 0xBC,
        "Period" => 0xBE,
        "Slash" => 0xBF,
        "Backquote" => 0xC0,
        "PrintScreen" => 0x2C,
        "ScrollLock" => 0x91,
        "Pause" => 0x13,
        _ => return None,
    })
}

fn mod_to_vk(m: KeyMod) -> u16 {
    match m {
        KeyMod::Shift => 0x10,
        KeyMod::Ctrl => 0x11,
        KeyMod::Alt => 0x12,
        KeyMod::Meta => 0x5B,
    }
}

fn button_down_flag(b: PointerButton) -> MOUSE_EVENT_FLAGS {
    match b {
        PointerButton::Left => MOUSEEVENTF_LEFTDOWN,
        PointerButton::Right => MOUSEEVENTF_RIGHTDOWN,
        PointerButton::Middle => MOUSEEVENTF_MIDDLEDOWN,
    }
}

fn button_up_flag(b: PointerButton) -> MOUSE_EVENT_FLAGS {
    match b {
        PointerButton::Left => MOUSEEVENTF_LEFTUP,
        PointerButton::Right => MOUSEEVENTF_RIGHTUP,
        PointerButton::Middle => MOUSEEVENTF_MIDDLEUP,
    }
}

fn button_name(b: PointerButton) -> String {
    match b {
        PointerButton::Left => "left",
        PointerButton::Right => "right",
        PointerButton::Middle => "middle",
    }
    .to_string()
}

/// Axis-aligned screen rectangle in virtual-desktop pixels.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct DisplayRect {
    x: i32,
    y: i32,
    width: i32,
    height: i32,
}

/// Map normalized `[0,1]` coordinates inside `display` onto the 0..65535
/// absolute space of the whole virtual desktop (`MOUSEEVENTF_VIRTUALDESK`).
///
/// Pure so the multi-monitor arithmetic can be unit-tested without Windows.
fn virtual_desktop_absolute(
    display: DisplayRect,
    virtual_origin_x: i32,
    virtual_origin_y: i32,
    virtual_width: i32,
    virtual_height: i32,
    x: f64,
    y: f64,
) -> (i32, i32) {
    const ABS_MAX: f64 = 65535.0;
    let width = display.width.max(1) as f64;
    let height = display.height.max(1) as f64;
    let pixel_x = display.x as f64 + x.clamp(0.0, 1.0) * (width - 1.0);
    let pixel_y = display.y as f64 + y.clamp(0.0, 1.0) * (height - 1.0);
    let span_x = (virtual_width.max(2) - 1) as f64;
    let span_y = (virtual_height.max(2) - 1) as f64;
    let abs_x = ((pixel_x - virtual_origin_x as f64) / span_x * ABS_MAX)
        .round()
        .clamp(0.0, ABS_MAX) as i32;
    let abs_y = ((pixel_y - virtual_origin_y as f64) / span_y * ABS_MAX)
        .round()
        .clamp(0.0, ABS_MAX) as i32;
    (abs_x, abs_y)
}

/// `SendInput`-based input sink for Windows.
#[derive(Debug, Default)]
pub struct WindowsInputSink {
    pressed_keys: BTreeSet<String>,
    pressed_buttons: BTreeSet<PointerButton>,
    /// The display this session captures; `None` means the primary display.
    display: Option<DisplayRect>,
    /// Set once we logged that the OS rejected injection, so a locked screen
    /// does not spam the log on every event.
    warned_rejected: std::sync::atomic::AtomicBool,
}

impl WindowsInputSink {
    pub fn new() -> Self {
        Self::default()
    }

    /// Virtual desktop bounds: `(x, y, width, height)`.
    fn virtual_desktop() -> (i32, i32, i32, i32) {
        // SAFETY: GetSystemMetrics takes no pointer arguments.
        unsafe {
            let x = GetSystemMetrics(SM_XVIRTUALSCREEN);
            let y = GetSystemMetrics(SM_YVIRTUALSCREEN);
            let width = GetSystemMetrics(SM_CXVIRTUALSCREEN);
            let height = GetSystemMetrics(SM_CYVIRTUALSCREEN);
            if width <= 0 || height <= 0 {
                // Headless or detached desktop: fall back to the primary screen.
                (
                    0,
                    0,
                    GetSystemMetrics(SM_CXSCREEN).max(1),
                    GetSystemMetrics(SM_CYSCREEN).max(1),
                )
            } else {
                (x, y, width, height)
            }
        }
    }

    /// Inject one batch and report whether the OS accepted all of it.
    ///
    /// `SendInput` returns 0 when the caller is not on the active input
    /// desktop (locked screen, UAC secure desktop, disconnected RDP session).
    /// That used to look like "clicks do nothing" with no diagnostic at all.
    fn inject(&self, inputs: &[INPUT]) -> bool {
        // SAFETY: `inputs` is a well-formed slice of INPUT records.
        let sent = unsafe { SendInput(inputs, std::mem::size_of::<INPUT>() as i32) };
        if sent as usize == inputs.len() {
            self.warned_rejected.store(false, Ordering::Relaxed);
            return true;
        }
        if !self.warned_rejected.swap(true, Ordering::Relaxed) {
            warn!(
                "SendInput accepted {sent}/{} events (last_error={}); the input desktop \
                 is not ours or the foreground window is at a higher integrity level \
                 (UIPI). Further failures are not logged.",
                inputs.len(),
                std::io::Error::last_os_error(),
            );
        }
        false
    }

    fn send_mouse(&self, flags: MOUSE_EVENT_FLAGS, data: i32, dx: i32, dy: i32) -> bool {
        let input = INPUT {
            r#type: INPUT_MOUSE,
            Anonymous: INPUT_0 {
                mi: MOUSEINPUT {
                    dx,
                    dy,
                    mouseData: data as u32,
                    dwFlags: flags,
                    time: 0,
                    dwExtraInfo: 0,
                },
            },
        };
        self.inject(&[input])
    }

    fn send_key(&self, vk: u16, key_up: bool) -> bool {
        let flags: KEYBD_EVENT_FLAGS = if key_up {
            KEYEVENTF_KEYUP
        } else {
            KEYBD_EVENT_FLAGS(0)
        };
        let input = INPUT {
            r#type: INPUT_KEYBOARD,
            Anonymous: INPUT_0 {
                ki: KEYBDINPUT {
                    wVk: VIRTUAL_KEY(vk),
                    wScan: 0,
                    dwFlags: flags,
                    time: 0,
                    dwExtraInfo: 0,
                },
            },
        };
        self.inject(&[input])
    }

    fn send_unicode(&self, c: char, key_up: bool) -> bool {
        let key_flags: KEYBD_EVENT_FLAGS = if key_up {
            KEYEVENTF_KEYUP
        } else {
            KEYBD_EVENT_FLAGS(0)
        };
        let flags = KEYEVENTF_UNICODE | key_flags;
        let mut units = [0u16; 2];
        let encoded = c.encode_utf16(&mut units);
        let mk_input = |scan: u16| INPUT {
            r#type: INPUT_KEYBOARD,
            Anonymous: INPUT_0 {
                ki: KEYBDINPUT {
                    wVk: VIRTUAL_KEY(0),
                    wScan: scan,
                    dwFlags: flags,
                    time: 0,
                    dwExtraInfo: 0,
                },
            },
        };
        if encoded.len() > 1 {
            // Surrogate pair: second unit carries KEYEVENTF_UNICODE too.
            let inputs = [mk_input(encoded[0]), mk_input(encoded[1])];
            self.inject(&inputs)
        } else {
            let inputs = [mk_input(encoded[0])];
            self.inject(&inputs)
        }
    }

    /// Normalized [0,1] coordinates of the captured display mapped onto the
    /// virtual desktop's 0..65535 `MOUSEEVENTF_ABSOLUTE` space.
    fn normalized_to_absolute(&self, x: f64, y: f64) -> (i32, i32) {
        let (virtual_x, virtual_y, virtual_width, virtual_height) = Self::virtual_desktop();
        let display = self.display.unwrap_or(DisplayRect {
            x: 0,
            y: 0,
            width: unsafe { GetSystemMetrics(SM_CXSCREEN).max(1) },
            height: unsafe { GetSystemMetrics(SM_CYSCREEN).max(1) },
        });
        virtual_desktop_absolute(
            display,
            virtual_x,
            virtual_y,
            virtual_width,
            virtual_height,
            x,
            y,
        )
    }
}

impl InputSink for WindowsInputSink {
    fn apply(&mut self, event: &InputEvent) -> Result<(), InputEventError> {
        match event {
            InputEvent::Pointer {
                action,
                x,
                y,
                button,
                ..
            } => {
                if !x.is_finite() || !y.is_finite() {
                    return Err(InputEventError::InvalidField("x/y"));
                }
                let (abs_x, abs_y) = self.normalized_to_absolute(*x, *y);
                // Move first so down/up land on the right position.
                let mut accepted = self.send_mouse(
                    MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK,
                    0,
                    abs_x,
                    abs_y,
                );
                match (action, button) {
                    (PointerAction::Down, Some(b)) => {
                        accepted &= self.send_mouse(button_down_flag(*b), 0, 0, 0);
                        self.pressed_buttons.insert(*b);
                    }
                    (PointerAction::Up, Some(b)) => {
                        accepted &= self.send_mouse(button_up_flag(*b), 0, 0, 0);
                        self.pressed_buttons.remove(b);
                    }
                    (PointerAction::Down | PointerAction::Up, None) => {
                        return Err(InputEventError::MissingField("b"));
                    }
                    (PointerAction::Move, _) => {}
                }
                if accepted {
                    Ok(())
                } else {
                    Err(InputEventError::Rejected)
                }
            }
            InputEvent::Wheel { dy, unit, .. } => {
                let delta = wheel_delta(*dy, *unit);
                if delta != 0 && !self.send_mouse(MOUSEEVENTF_WHEEL, delta, 0, 0) {
                    return Err(InputEventError::Rejected);
                }
                Ok(())
            }
            InputEvent::Key {
                action, code, mods, ..
            } => {
                let Some(vk) = w3c_code_to_vk(code) else {
                    warn!("no VK mapping for W3C code {code:?}; dropping");
                    return Ok(());
                };
                let key_up = matches!(action, KeyAction::Up);
                let mut accepted = true;
                // Hold declared modifiers for this event so combos inject correctly.
                if !key_up {
                    for m in mods {
                        accepted &= self.send_key(mod_to_vk(*m), false);
                    }
                }
                accepted &= self.send_key(vk, key_up);
                if key_up {
                    for m in mods {
                        accepted &= self.send_key(mod_to_vk(*m), true);
                    }
                    self.pressed_keys.remove(code);
                } else {
                    self.pressed_keys.insert(code.clone());
                }
                if accepted {
                    Ok(())
                } else {
                    Err(InputEventError::Rejected)
                }
            }
            InputEvent::Text { text, .. } => {
                let mut accepted = true;
                for c in text.chars() {
                    accepted &= self.send_unicode(c, false);
                    accepted &= self.send_unicode(c, true);
                }
                if accepted {
                    Ok(())
                } else {
                    Err(InputEventError::Rejected)
                }
            }
            InputEvent::Clipboard {
                action,
                data_base64,
                ..
            } => match action {
                ClipboardAction::Offer => {
                    // TODO(RD-P2-13/v1 clipboard): decode dataBase64 and place on the
                    // clipboard via OpenClipboard/SetClipboardData. Injection of text is
                    // currently covered by the `text` event.
                    if data_base64.is_some() {
                        warn!("clipboard offer received; clipboard injection not implemented yet");
                    }
                    Ok(())
                }
                ClipboardAction::Request => Ok(()),
            },
            InputEvent::Ping { .. } => Ok(()),
            InputEvent::ReleaseAll { .. } => {
                self.release_all();
                Ok(())
            }
        }
    }

    fn release_all(&mut self) -> (Vec<String>, Vec<String>) {
        let keys: Vec<String> = self.pressed_keys.iter().cloned().collect();
        let buttons: Vec<String> = self
            .pressed_buttons
            .iter()
            .map(|b| button_name(*b))
            .collect();
        for code in keys.clone() {
            if let Some(vk) = w3c_code_to_vk(&code) {
                self.send_key(vk, true);
            }
        }
        for button in self.pressed_buttons.clone() {
            self.send_mouse(button_up_flag(button), 0, 0, 0);
        }
        self.pressed_keys.clear();
        self.pressed_buttons.clear();
        (keys, buttons)
    }

    fn pressed_keys(&self) -> Vec<String> {
        self.pressed_keys.iter().cloned().collect()
    }

    fn pressed_buttons(&self) -> Vec<String> {
        self.pressed_buttons
            .iter()
            .map(|b| button_name(*b))
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn vk_mapping_covers_core_codes() {
        assert_eq!(w3c_code_to_vk("KeyA"), Some(0x41));
        assert_eq!(w3c_code_to_vk("Digit1"), Some(0x31));
        assert_eq!(w3c_code_to_vk("Escape"), Some(0x1B));
        assert_eq!(w3c_code_to_vk("ControlLeft"), Some(0x11));
        assert_eq!(w3c_code_to_vk("Space"), Some(0x20));
        assert_eq!(w3c_code_to_vk("NoSuchCode"), None);
    }

    #[test]
    fn normalized_to_absolute_clamps() {
        // Display equals the virtual desktop, so the mapping spans the full range.
        let display = DisplayRect {
            x: 0,
            y: 0,
            width: 1920,
            height: 1080,
        };
        assert_eq!(
            virtual_desktop_absolute(display, 0, 0, 1920, 1080, 0.0, 0.0),
            (0, 0)
        );
        assert_eq!(
            virtual_desktop_absolute(display, 0, 0, 1920, 1080, 1.0, 1.0),
            (65535, 65535)
        );
        let (x, _) = virtual_desktop_absolute(display, 0, 0, 1920, 1080, 2.0, -1.0);
        assert_eq!(x, 65535);
    }

    #[test]
    fn second_monitor_maps_into_its_own_rectangle() {
        // 1920x1080 primary at (0,0) plus a 2560x1440 monitor to its right.
        let display = DisplayRect {
            x: 1920,
            y: 0,
            width: 2560,
            height: 1440,
        };
        let (virtual_x, virtual_y, virtual_width, virtual_height) = (0, 0, 4480, 1440);
        // The left edge of the second monitor is at 1920/4479 of the virtual desktop.
        assert_eq!(
            virtual_desktop_absolute(
                display,
                virtual_x,
                virtual_y,
                virtual_width,
                virtual_height,
                0.0,
                0.0
            ),
            (28093, 0)
        );
        let (right, bottom) = virtual_desktop_absolute(
            display,
            virtual_x,
            virtual_y,
            virtual_width,
            virtual_height,
            1.0,
            1.0,
        );
        assert_eq!(right, 65535);
        assert_eq!(bottom, 65535);
    }

    #[test]
    fn virtual_desktop_absolute_handles_tiny_spans() {
        let display = DisplayRect {
            x: 0,
            y: 0,
            width: 1,
            height: 1,
        };
        // A degenerate display and virtual desktop must still produce in-range values.
        assert_eq!(
            virtual_desktop_absolute(display, 0, 0, 1, 1, 0.5, 0.5),
            (0, 0)
        );
        assert_eq!(
            virtual_desktop_absolute(display, 0, 0, 1, 1, 5.0, -5.0),
            (0, 0)
        );
    }

    #[test]
    fn wheel_direction_matches_the_protocol() {
        // Positive protocol dy = scroll down; Windows positive wheel = scroll up.
        assert_eq!(wheel_delta(120.0, WheelUnit::Pixel), -120);
        assert_eq!(wheel_delta(-120.0, WheelUnit::Pixel), 120);
        assert_eq!(wheel_delta(1.0, WheelUnit::Line), -LINES_PER_NOTCH);
        assert_eq!(wheel_delta(0.0, WheelUnit::Pixel), 0);
    }

    #[test]
    fn release_all_reports_and_clears() {
        // Track pressed sets only (no SendInput assertions here; injection correctness
        // requires an interactive session and is covered by RD-P2-12 acceptance tests).
        let mut sink = WindowsInputSink::new();
        sink.pressed_keys.insert("KeyA".to_string());
        sink.pressed_buttons.insert(PointerButton::Left);
        assert_eq!(sink.pressed_keys(), vec!["KeyA"]);
        assert_eq!(sink.pressed_buttons(), vec!["left"]);
        let (keys, buttons) = sink.release_all();
        assert_eq!(keys, vec!["KeyA"]);
        assert_eq!(buttons, vec!["left"]);
        assert!(sink.pressed_keys().is_empty());
        assert!(sink.pressed_buttons().is_empty());
    }
}

//! Windows input injection via `SendInput` (RD-P2-12).
//!
//! - Mouse move / button down / up / wheel through `INPUT_MOUSE`.
//! - Key down / up through `INPUT_KEYBOARD`.
//! - Unicode text through `KEYEVENTF_UNICODE` (paste path, per decisions §7.6 rule 3).
//! - Maintains pressed key/button sets and releases everything on `release_all`
//!   (startup panic-release, `host.stop`, EOF and crash paths all funnel here).

use std::collections::BTreeSet;

use log::warn;
use windows::Win32::UI::Input::KeyboardAndMouse::{
    SendInput, INPUT, INPUT_0, INPUT_KEYBOARD, INPUT_MOUSE, KEYBDINPUT, KEYBD_EVENT_FLAGS,
    KEYEVENTF_KEYUP, KEYEVENTF_UNICODE, MOUSEEVENTF_ABSOLUTE, MOUSEEVENTF_LEFTDOWN,
    MOUSEEVENTF_LEFTUP, MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP, MOUSEEVENTF_MOVE,
    MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP, MOUSEEVENTF_WHEEL, MOUSEINPUT, MOUSE_EVENT_FLAGS,
    VIRTUAL_KEY,
};
use windows::Win32::UI::WindowsAndMessaging::{GetSystemMetrics, SM_CXSCREEN, SM_CYSCREEN};

use super::{
    ClipboardAction, InputEvent, InputEventError, InputSink, KeyAction, KeyMod, PointerAction,
    PointerButton, WheelUnit,
};

/// Lines per wheel notch for line-unit scroll (SPI_GETWHEELSCROLLLINES default).
const LINES_PER_NOTCH: i32 = 3;

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

/// `SendInput`-based input sink for Windows.
#[derive(Debug, Default)]
pub struct WindowsInputSink {
    pressed_keys: BTreeSet<String>,
    pressed_buttons: BTreeSet<PointerButton>,
    /// Primary display size in pixels, refreshed on each pointer move.
    screen_width: i32,
    screen_height: i32,
}

impl WindowsInputSink {
    pub fn new() -> Self {
        Self::default()
    }

    fn refresh_screen_size(&mut self) {
        // SAFETY: GetSystemMetrics with SM_CXSCREEN/SM_CYSCREEN takes no pointer params.
        unsafe {
            self.screen_width = GetSystemMetrics(SM_CXSCREEN);
            self.screen_height = GetSystemMetrics(SM_CYSCREEN);
        }
    }

    fn send_mouse(&self, flags: MOUSE_EVENT_FLAGS, data: i32, dx: i32, dy: i32) {
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
        // SAFETY: `input` is a well-formed single-element INPUT array.
        unsafe {
            let _ = SendInput(&[input], std::mem::size_of::<INPUT>() as i32);
        }
    }

    fn send_key(&self, vk: u16, key_up: bool) {
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
        // SAFETY: `input` is a well-formed single-element INPUT array.
        unsafe {
            let _ = SendInput(&[input], std::mem::size_of::<INPUT>() as i32);
        }
    }

    fn send_unicode(&self, c: char, key_up: bool) {
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
            // SAFETY: `inputs` is a well-formed two-element INPUT array.
            unsafe {
                let _ = SendInput(&inputs, std::mem::size_of::<INPUT>() as i32);
            }
        } else {
            let inputs = [mk_input(encoded[0])];
            // SAFETY: `inputs` is a well-formed single-element INPUT array.
            unsafe {
                let _ = SendInput(&inputs, std::mem::size_of::<INPUT>() as i32);
            }
        }
    }

    /// Normalized [0,1] display coordinates mapped to `MOUSEEVENTF_ABSOLUTE` units
    /// (0..65535 across the primary display's width/height). v1 is single-display.
    fn normalized_to_absolute(&self, x: f64, y: f64) -> (i32, i32) {
        const ABS_MAX: f64 = 65535.0;
        let px = (x.clamp(0.0, 1.0) * ABS_MAX).round() as i32;
        let py = (y.clamp(0.0, 1.0) * ABS_MAX).round() as i32;
        (px, py)
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
                self.refresh_screen_size();
                let (abs_x, abs_y) = self.normalized_to_absolute(*x, *y);
                // Move first so down/up land on the right position.
                self.send_mouse(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE, 0, abs_x, abs_y);
                match (action, button) {
                    (PointerAction::Down, Some(b)) => {
                        self.send_mouse(button_down_flag(*b), 0, 0, 0);
                        self.pressed_buttons.insert(*b);
                    }
                    (PointerAction::Up, Some(b)) => {
                        self.send_mouse(button_up_flag(*b), 0, 0, 0);
                        self.pressed_buttons.remove(b);
                    }
                    (PointerAction::Down | PointerAction::Up, None) => {
                        return Err(InputEventError::MissingField("b"));
                    }
                    (PointerAction::Move, _) => {}
                }
                Ok(())
            }
            InputEvent::Wheel { dy, unit, .. } => {
                let delta = match unit {
                    WheelUnit::Pixel => dy.round() as i32,
                    WheelUnit::Line => (dy * LINES_PER_NOTCH as f64).round() as i32,
                };
                if delta != 0 {
                    self.send_mouse(MOUSEEVENTF_WHEEL, delta, 0, 0);
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
                // Hold declared modifiers for this event so combos inject correctly.
                if !key_up {
                    for m in mods {
                        self.send_key(mod_to_vk(*m), false);
                    }
                }
                self.send_key(vk, key_up);
                if key_up {
                    for m in mods {
                        self.send_key(mod_to_vk(*m), true);
                    }
                    self.pressed_keys.remove(code);
                } else {
                    self.pressed_keys.insert(code.clone());
                }
                Ok(())
            }
            InputEvent::Text { text, .. } => {
                for c in text.chars() {
                    self.send_unicode(c, false);
                    self.send_unicode(c, true);
                }
                Ok(())
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
        let sink = WindowsInputSink {
            screen_width: 1920,
            screen_height: 1080,
            ..WindowsInputSink::new()
        };
        let (x, y) = sink.normalized_to_absolute(0.0, 0.0);
        assert_eq!((x, y), (0, 0));
        let (x, y) = sink.normalized_to_absolute(1.0, 1.0);
        assert_eq!((x, y), (65535, 65535));
        let (x, _) = sink.normalized_to_absolute(2.0, -1.0);
        assert_eq!(x, 65535);
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

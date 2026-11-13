//! Input sink abstraction: converts remote-desktop input events into OS events.

pub mod fake;
#[cfg(windows)]
pub mod windows;

use thiserror::Error;

/// A parsed `input-event.schema.json` event (client -> host).
#[derive(Debug, Clone, PartialEq)]
pub enum InputEvent {
    Pointer {
        seq: u32,
        action: PointerAction,
        x: f64,
        y: f64,
        button: Option<PointerButton>,
        display: u8,
    },
    Wheel {
        seq: u32,
        dx: f64,
        dy: f64,
        unit: WheelUnit,
        display: u8,
    },
    Key {
        seq: u32,
        action: KeyAction,
        /// W3C `KeyboardEvent.code`, e.g. `KeyA`, `Escape`, `ControlLeft`.
        code: String,
        mods: Vec<KeyMod>,
    },
    Text {
        seq: u32,
        text: String,
    },
    Clipboard {
        seq: u32,
        action: ClipboardAction,
        data_base64: Option<String>,
    },
    Ping {
        at: u64,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PointerAction {
    Move,
    Down,
    Up,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum PointerButton {
    Left,
    Right,
    Middle,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WheelUnit {
    Pixel,
    Line,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KeyAction {
    Down,
    Up,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum KeyMod {
    Ctrl,
    Alt,
    Shift,
    Meta,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ClipboardAction {
    Offer,
    Request,
}

#[derive(Debug, Error, PartialEq, Eq)]
pub enum InputEventError {
    #[error("input event is missing field `{0}`")]
    MissingField(&'static str),
    #[error("input event has invalid field `{0}`")]
    InvalidField(&'static str),
    #[error("input event must be a JSON object")]
    NotAnObject,
}

impl InputEvent {
    /// Parse one event from the `input-event.schema.json` wire format.
    pub fn from_json(value: &serde_json::Value) -> Result<Self, InputEventError> {
        let obj = value.as_object().ok_or(InputEventError::NotAnObject)?;
        let t = obj
            .get("t")
            .and_then(serde_json::Value::as_str)
            .ok_or(InputEventError::MissingField("t"))?;
        let seq = || {
            obj.get("seq")
                .and_then(serde_json::Value::as_u64)
                .map(|v| v as u32)
                .ok_or(InputEventError::MissingField("seq"))
        };
        let ts = || obj.get("ts").and_then(serde_json::Value::as_u64);
        let _ = ts; // accepted on the wire, not needed for injection
        match t {
            "pointer" => {
                let a = match obj
                    .get("a")
                    .and_then(serde_json::Value::as_str)
                    .ok_or(InputEventError::MissingField("a"))?
                {
                    "move" => PointerAction::Move,
                    "down" => PointerAction::Down,
                    "up" => PointerAction::Up,
                    _ => return Err(InputEventError::InvalidField("a")),
                };
                let x = obj
                    .get("x")
                    .and_then(serde_json::Value::as_f64)
                    .ok_or(InputEventError::MissingField("x"))?;
                let y = obj
                    .get("y")
                    .and_then(serde_json::Value::as_f64)
                    .ok_or(InputEventError::MissingField("y"))?;
                let button = obj
                    .get("b")
                    .and_then(serde_json::Value::as_str)
                    .map(|b| match b {
                        "left" => PointerButton::Left,
                        "right" => PointerButton::Right,
                        "middle" => PointerButton::Middle,
                        _ => PointerButton::Left,
                    });
                let display = obj
                    .get("d")
                    .and_then(serde_json::Value::as_u64)
                    .unwrap_or(0) as u8;
                Ok(InputEvent::Pointer {
                    seq: seq()?,
                    action: a,
                    x,
                    y,
                    button,
                    display,
                })
            }
            "wheel" => {
                let dx = obj
                    .get("dx")
                    .and_then(serde_json::Value::as_f64)
                    .ok_or(InputEventError::MissingField("dx"))?;
                let dy = obj
                    .get("dy")
                    .and_then(serde_json::Value::as_f64)
                    .ok_or(InputEventError::MissingField("dy"))?;
                let unit = match obj
                    .get("unit")
                    .and_then(serde_json::Value::as_str)
                    .unwrap_or("pixel")
                {
                    "pixel" => WheelUnit::Pixel,
                    "line" => WheelUnit::Line,
                    _ => WheelUnit::Pixel,
                };
                let display = obj
                    .get("d")
                    .and_then(serde_json::Value::as_u64)
                    .unwrap_or(0) as u8;
                Ok(InputEvent::Wheel {
                    seq: seq()?,
                    dx,
                    dy,
                    unit,
                    display,
                })
            }
            "key" => {
                let a = match obj
                    .get("a")
                    .and_then(serde_json::Value::as_str)
                    .ok_or(InputEventError::MissingField("a"))?
                {
                    "down" => KeyAction::Down,
                    "up" => KeyAction::Up,
                    _ => return Err(InputEventError::InvalidField("a")),
                };
                let code = obj
                    .get("code")
                    .and_then(serde_json::Value::as_str)
                    .ok_or(InputEventError::MissingField("code"))?
                    .to_string();
                let mods = obj
                    .get("mods")
                    .and_then(serde_json::Value::as_array)
                    .map(|arr| {
                        arr.iter()
                            .filter_map(serde_json::Value::as_str)
                            .filter_map(|m| match m {
                                "ctrl" => Some(KeyMod::Ctrl),
                                "alt" => Some(KeyMod::Alt),
                                "shift" => Some(KeyMod::Shift),
                                "meta" => Some(KeyMod::Meta),
                                _ => None,
                            })
                            .collect()
                    })
                    .unwrap_or_default();
                Ok(InputEvent::Key {
                    seq: seq()?,
                    action: a,
                    code,
                    mods,
                })
            }
            "text" => {
                let text = obj
                    .get("s")
                    .and_then(serde_json::Value::as_str)
                    .ok_or(InputEventError::MissingField("s"))?
                    .to_string();
                Ok(InputEvent::Text { seq: seq()?, text })
            }
            "clipboard" => {
                let a = match obj
                    .get("a")
                    .and_then(serde_json::Value::as_str)
                    .ok_or(InputEventError::MissingField("a"))?
                {
                    "offer" => ClipboardAction::Offer,
                    "request" => ClipboardAction::Request,
                    _ => return Err(InputEventError::InvalidField("a")),
                };
                let data_base64 = obj
                    .get("dataBase64")
                    .and_then(serde_json::Value::as_str)
                    .map(str::to_string);
                Ok(InputEvent::Clipboard {
                    seq: seq()?,
                    action: a,
                    data_base64,
                })
            }
            "ping" => {
                let at = obj
                    .get("at")
                    .and_then(serde_json::Value::as_u64)
                    .ok_or(InputEventError::MissingField("at"))?;
                Ok(InputEvent::Ping { at })
            }
            _ => Err(InputEventError::InvalidField("t")),
        }
    }
}

/// Platform abstraction for injecting input events.
pub trait InputSink: Send {
    /// Apply one parsed event. The host maintains the pressed-key/button sets;
    /// the sink is responsible for OS-level injection.
    fn apply(&mut self, event: &InputEvent) -> Result<(), InputEventError>;

    /// Tell the sink which display the session captures, so normalized
    /// coordinates land on that monitor instead of the primary one.
    /// Default is a no-op for sinks that have no notion of a display.
    fn set_display(&mut self, _display: Option<&crate::capture::DisplayInfo>) {}

    /// Release every currently pressed key and mouse button (anti stuck-key).
    /// Returns `(released_keys, released_buttons)`.
    fn release_all(&mut self) -> (Vec<String>, Vec<String>);

    /// Currently pressed W3C key codes (as tracked by the sink).
    fn pressed_keys(&self) -> Vec<String>;

    /// Currently pressed mouse buttons.
    fn pressed_buttons(&self) -> Vec<String>;
}

/// Pick the platform input sink.
pub fn new_input_sink() -> Box<dyn InputSink> {
    #[cfg(windows)]
    {
        Box::new(windows::WindowsInputSink::new())
    }
    #[cfg(not(windows))]
    {
        Box::new(fake::FakeInputSink::new())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_each_branch() {
        let ev = InputEvent::from_json(&serde_json::json!({
            "v": 1, "seq": 44, "t": "pointer", "a": "down", "b": "left", "x": 0.5, "y": 0.25, "d": 0
        }))
        .unwrap();
        assert_eq!(
            ev,
            InputEvent::Pointer {
                seq: 44,
                action: PointerAction::Down,
                x: 0.5,
                y: 0.25,
                button: Some(PointerButton::Left),
                display: 0,
            }
        );

        let ev = InputEvent::from_json(&serde_json::json!({
            "v": 1, "seq": 46, "t": "wheel", "dx": 0, "dy": -120, "unit": "pixel", "d": 0
        }))
        .unwrap();
        assert!(matches!(ev, InputEvent::Wheel { seq: 46, dy, .. } if dy == -120.0));

        let ev = InputEvent::from_json(&serde_json::json!({
            "v": 1, "seq": 41, "ts": 1762944000123u64, "t": "key", "a": "down",
            "code": "KeyC", "mods": ["ctrl", "shift"]
        }))
        .unwrap();
        assert!(matches!(ev, InputEvent::Key { seq: 41, code, .. } if code == "KeyC"));

        let ev = InputEvent::from_json(&serde_json::json!({
            "v": 1, "seq": 43, "t": "text", "s": "你好"
        }))
        .unwrap();
        assert!(matches!(ev, InputEvent::Text { seq: 43, .. }));

        let ev = InputEvent::from_json(&serde_json::json!({
            "v": 1, "seq": 47, "t": "clipboard", "a": "offer", "mime": "text/plain",
            "size": 12, "dataBase64": "aGVsbG8gd29ybGQ="
        }))
        .unwrap();
        assert!(matches!(ev, InputEvent::Clipboard { seq: 47, .. }));

        let ev = InputEvent::from_json(&serde_json::json!({
            "v": 1, "t": "ping", "at": 1762944000200u64
        }))
        .unwrap();
        assert!(matches!(ev, InputEvent::Ping { at: 1762944000200 }));
    }

    #[test]
    fn rejects_invalid_events() {
        assert_eq!(
            InputEvent::from_json(&serde_json::json!(42)),
            Err(InputEventError::NotAnObject)
        );
        assert_eq!(
            InputEvent::from_json(&serde_json::json!({"t": "key"})),
            Err(InputEventError::MissingField("a"))
        );
        assert_eq!(
            InputEvent::from_json(&serde_json::json!({"t": "bogus"})),
            Err(InputEventError::InvalidField("t"))
        );
    }
}

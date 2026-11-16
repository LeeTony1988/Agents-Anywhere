//! Input sink that records events instead of injecting them (tests / non-Windows).

use std::collections::BTreeSet;

use super::{InputEvent, InputEventError, InputSink, KeyAction, PointerAction, PointerButton};

#[derive(Debug, Default)]
pub struct FakeInputSink {
    pub events: Vec<InputEvent>,
    pressed_keys: BTreeSet<String>,
    pressed_buttons: BTreeSet<PointerButton>,
}

impl FakeInputSink {
    pub fn new() -> Self {
        Self::default()
    }
}

impl InputSink for FakeInputSink {
    fn apply(&mut self, event: &InputEvent) -> Result<(), InputEventError> {
        match event {
            InputEvent::Pointer {
                action,
                button,
                x,
                y,
                ..
            } => {
                if !x.is_finite()
                    || !(0.0..=1.0).contains(x)
                    || !y.is_finite()
                    || !(0.0..=1.0).contains(y)
                {
                    return Err(InputEventError::InvalidField("x/y"));
                }
                match (action, button) {
                    (PointerAction::Down, Some(b)) => {
                        self.pressed_buttons.insert(*b);
                    }
                    (PointerAction::Up, Some(b)) => {
                        self.pressed_buttons.remove(b);
                    }
                    (PointerAction::Down | PointerAction::Up, None) => {
                        return Err(InputEventError::MissingField("b"));
                    }
                    _ => {}
                }
            }
            InputEvent::Key { action, code, .. } => match action {
                KeyAction::Down => {
                    self.pressed_keys.insert(code.clone());
                }
                KeyAction::Up => {
                    self.pressed_keys.remove(code);
                }
            },
            InputEvent::Text { .. }
            | InputEvent::Wheel { .. }
            | InputEvent::Clipboard { .. }
            | InputEvent::Ping { .. } => {}
            InputEvent::ReleaseAll { .. } => {
                self.release_all();
            }
        }
        self.events.push(event.clone());
        Ok(())
    }

    fn release_all(&mut self) -> (Vec<String>, Vec<String>) {
        let keys: Vec<String> = self.pressed_keys.iter().cloned().collect();
        let buttons: Vec<String> = self
            .pressed_buttons
            .iter()
            .map(|b| format!("{b:?}").to_lowercase())
            .collect();
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
            .map(|b| format!("{b:?}").to_lowercase())
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn records_and_tracks_pressed() {
        let mut sink = FakeInputSink::new();
        sink.apply(&InputEvent::Key {
            seq: 1,
            action: KeyAction::Down,
            code: "KeyA".into(),
            mods: vec![],
        })
        .unwrap();
        sink.apply(&InputEvent::Pointer {
            seq: 2,
            action: PointerAction::Down,
            x: 0.5,
            y: 0.5,
            button: Some(PointerButton::Left),
            display: 0,
        })
        .unwrap();
        assert_eq!(sink.pressed_keys(), vec!["KeyA"]);
        assert_eq!(sink.pressed_buttons(), vec!["left"]);
        assert_eq!(sink.events.len(), 2);
        let (keys, buttons) = sink.release_all();
        assert_eq!(keys, vec!["KeyA"]);
        assert_eq!(buttons, vec!["left"]);
        assert!(sink.pressed_keys().is_empty());
        assert!(sink.pressed_buttons().is_empty());
    }
}

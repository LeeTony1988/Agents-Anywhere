//! 12-byte little-endian `aa.pointer` binary frame.
//!
//! Layout (see `contracts/remote-desktop/1.0/pointer-frame.md`):
//!
//! | offset | size | field   |
//! | ------ | ---- | ------- |
//! | 0      | 1    | type    | fixed 0x01 (pointer-move)
//! | 1      | 1    | flags   | bit0 = button-state change
//! | 2      | 2    | buttons | u16 LE bitmask (1=left 2=right 4=middle)
//! | 4      | 2    | x       | u16 LE normalized 0..=65535
//! | 6      | 2    | y       | u16 LE normalized 0..=65535
//! | 8      | 4    | seq     | u32 LE, wraps at 2^32

use thiserror::Error;

pub const POINTER_FRAME_LEN: usize = 12;
pub const POINTER_TYPE: u8 = 0x01;

pub const BUTTON_LEFT: u16 = 0x0001;
pub const BUTTON_RIGHT: u16 = 0x0002;
pub const BUTTON_MIDDLE: u16 = 0x0004;
const BUTTON_MASK: u16 = BUTTON_LEFT | BUTTON_RIGHT | BUTTON_MIDDLE;

pub const FLAG_BUTTONS_CHANGED: u8 = 0x01;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PointerFrame {
    /// Always `0x01` after decoding; other type values cause the whole frame to be ignored.
    pub frame_type: u8,
    /// bit0: buttons changed relative to previous frame; bits 1..=7 are ignored on decode.
    pub flags: u8,
    /// Pressed-button state (left | right | middle); reserved bits stripped on decode.
    pub buttons: u16,
    /// Normalized x in `0..=65535`.
    pub x: u16,
    /// Normalized y in `0..=65535`.
    pub y: u16,
    /// Sender sequence number; `0xFFFF_FFFF` wraps to 0.
    pub seq: u32,
}

#[derive(Debug, Error, PartialEq, Eq)]
pub enum PointerFrameError {
    #[error("pointer frame must be exactly 12 bytes, got {0}")]
    BadLength(usize),
    #[error("unsupported pointer frame type 0x{0:02X}")]
    BadType(u8),
}

/// Clamp helper per pointer-frame.md §2: values < 0 clamp to 0, > 65535 clamp to 65535.
/// NaN / infinity must be discarded by the caller before calling this.
pub fn clamp_axis(value: f64) -> u16 {
    if !value.is_finite() {
        return 0;
    }
    let rounded = value.round();
    if rounded <= 0.0 {
        0
    } else if rounded >= 65535.0 {
        65535
    } else {
        rounded as u16
    }
}

pub fn encode_pointer_frame(frame: &PointerFrame) -> [u8; POINTER_FRAME_LEN] {
    let mut out = [0u8; POINTER_FRAME_LEN];
    out[0] = POINTER_TYPE;
    out[1] = frame.flags & FLAG_BUTTONS_CHANGED;
    out[2..4].copy_from_slice(&(frame.buttons & BUTTON_MASK).to_le_bytes());
    out[4..6].copy_from_slice(&frame.x.to_le_bytes());
    out[6..8].copy_from_slice(&frame.y.to_le_bytes());
    out[8..12].copy_from_slice(&frame.seq.to_le_bytes());
    out
}

pub fn decode_pointer_frame(bytes: &[u8]) -> Result<PointerFrame, PointerFrameError> {
    if bytes.len() != POINTER_FRAME_LEN {
        return Err(PointerFrameError::BadLength(bytes.len()));
    }
    if bytes[0] != POINTER_TYPE {
        return Err(PointerFrameError::BadType(bytes[0]));
    }
    Ok(PointerFrame {
        frame_type: bytes[0],
        flags: bytes[1] & FLAG_BUTTONS_CHANGED,
        buttons: u16::from_le_bytes([bytes[2], bytes[3]]) & BUTTON_MASK,
        x: u16::from_le_bytes([bytes[4], bytes[5]]),
        y: u16::from_le_bytes([bytes[6], bytes[7]]),
        seq: u32::from_le_bytes([bytes[8], bytes[9], bytes[10], bytes[11]]),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// §4.1 normal move, no buttons.
    #[test]
    fn vector_4_1_normal_move() {
        let expected: [u8; 12] = [
            0x01, 0x00, 0x00, 0x00, 0x00, 0x80, 0x00, 0x40, 0x01, 0x00, 0x00, 0x00,
        ];
        let frame = PointerFrame {
            frame_type: POINTER_TYPE,
            flags: 0x00,
            buttons: 0x0000,
            x: 0x8000,
            y: 0x4000,
            seq: 1,
        };
        assert_eq!(encode_pointer_frame(&frame), expected);
        let decoded = decode_pointer_frame(&expected).unwrap();
        assert_eq!(decoded.flags, 0x00);
        assert_eq!(decoded.buttons, 0x0000);
        assert_eq!(decoded.x, 0x8000);
        assert_eq!(decoded.y, 0x4000);
        assert_eq!(decoded.seq, 1);
    }

    /// §4.2 seq wrap 0xFFFFFFFF -> 0, left button down.
    #[test]
    fn vector_4_2_seq_wrap() {
        let expected: [u8; 12] = [
            0x01, 0x01, 0x01, 0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00,
        ];
        let frame = PointerFrame {
            frame_type: POINTER_TYPE,
            flags: 0x01,
            buttons: BUTTON_LEFT,
            x: 0xFFFF,
            y: 0xFFFF,
            seq: 0,
        };
        assert_eq!(encode_pointer_frame(&frame), expected);
        let decoded = decode_pointer_frame(&expected).unwrap();
        assert_eq!(decoded.flags, 0x01);
        assert_eq!(decoded.buttons, BUTTON_LEFT);
        assert_eq!(decoded.seq, 0);
    }

    /// §4.3 out-of-range x/y clamped.
    #[test]
    fn vector_4_3_axis_clamp() {
        let expected: [u8; 12] = [
            0x01, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0x00, 0x00, 0x2A, 0x00, 0x00, 0x00,
        ];
        let frame = PointerFrame {
            frame_type: POINTER_TYPE,
            flags: 0x00,
            buttons: 0x0000,
            x: clamp_axis(70000.0),
            y: clamp_axis(-12.0),
            seq: 42,
        };
        assert_eq!(frame.x, 0xFFFF);
        assert_eq!(frame.y, 0x0000);
        assert_eq!(encode_pointer_frame(&frame), expected);
        let decoded = decode_pointer_frame(&expected).unwrap();
        assert_eq!(decoded.x, 0xFFFF);
        assert_eq!(decoded.y, 0x0000);
        assert_eq!(decoded.seq, 42);
    }

    /// §4.4 all three buttons held.
    #[test]
    fn vector_4_4_all_buttons() {
        let expected: [u8; 12] = [
            0x01, 0x01, 0x07, 0x00, 0x00, 0x00, 0xFF, 0x7F, 0xEF, 0xBE, 0xAD, 0xDE,
        ];
        let frame = PointerFrame {
            frame_type: POINTER_TYPE,
            flags: 0x01,
            buttons: BUTTON_LEFT | BUTTON_RIGHT | BUTTON_MIDDLE,
            x: 0x0000,
            y: 0x7FFF,
            seq: 0xDEADBEEF,
        };
        assert_eq!(encode_pointer_frame(&frame), expected);
        let decoded = decode_pointer_frame(&expected).unwrap();
        assert_eq!(decoded.buttons, 0x0007);
        assert_eq!(decoded.seq, 0xDEADBEEF);
    }

    /// §4.5 button release (buttons back to zero).
    #[test]
    fn vector_4_5_button_release() {
        let expected: [u8; 12] = [
            0x01, 0x01, 0x00, 0x00, 0x34, 0x12, 0x78, 0x56, 0x2B, 0x00, 0x00, 0x00,
        ];
        let frame = PointerFrame {
            frame_type: POINTER_TYPE,
            flags: 0x01,
            buttons: 0x0000,
            x: 0x1234,
            y: 0x5678,
            seq: 43,
        };
        assert_eq!(encode_pointer_frame(&frame), expected);
        let decoded = decode_pointer_frame(&expected).unwrap();
        assert_eq!(decoded.buttons, 0x0000);
        assert_eq!(decoded.x, 0x1234);
        assert_eq!(decoded.y, 0x5678);
        assert_eq!(decoded.seq, 43);
    }

    #[test]
    fn rejects_wrong_type_and_length() {
        let mut bad = [0x02u8; 12];
        assert_eq!(
            decode_pointer_frame(&bad),
            Err(PointerFrameError::BadType(0x02))
        );
        bad[0] = POINTER_TYPE;
        assert_eq!(
            decode_pointer_frame(&bad[..11]),
            Err(PointerFrameError::BadLength(11))
        );
        let _ = bad;
    }

    #[test]
    fn clamp_edges() {
        assert_eq!(clamp_axis(0.0), 0);
        assert_eq!(clamp_axis(65535.0), 65535);
        assert_eq!(clamp_axis(65534.6), 65535);
        assert_eq!(clamp_axis(-0.4), 0);
        assert_eq!(clamp_axis(f64::NAN), 0);
    }
}

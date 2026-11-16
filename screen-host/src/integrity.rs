//! Best-effort process integrity label for diagnostics.
//!
//! Windows UIPI blocks `SendInput` from a lower-integrity process to a higher-integrity
//! window. The Connector uses this label to refuse a screen host that was started inside a
//! low-integrity sandbox instead of pretending that input was applied.

/// Maps a Windows integrity RID to a stable lowercase label.
pub fn integrity_label(rid: u32) -> &'static str {
    match rid {
        0x0000..=0x0FFF => "untrusted",
        0x1000..=0x1FFF => "low",
        0x2000..=0x2FFF => "medium",
        0x3000..=0x3FFF => "high",
        _ => "system",
    }
}

#[cfg(windows)]
pub fn current_process_integrity() -> &'static str {
    use windows::Win32::Foundation::HANDLE;
    use windows::Win32::Security::{
        GetSidSubAuthority, GetSidSubAuthorityCount, GetTokenInformation, TokenIntegrityLevel,
        TOKEN_MANDATORY_LABEL, TOKEN_QUERY,
    };
    use windows::Win32::System::Threading::{GetCurrentProcess, OpenProcessToken};

    // SAFETY: all pointers refer to buffers we own, and lengths are checked before use.
    unsafe {
        let mut token = HANDLE::default();
        if OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token).is_err() {
            return "unknown";
        }
        let mut needed = 0u32;
        let _ = GetTokenInformation(token, TokenIntegrityLevel, None, 0, &mut needed);
        if needed == 0 {
            return "unknown";
        }
        // TOKEN_MANDATORY_LABEL contains pointers and must be 8-byte aligned; a
        // `Vec<u8>` only guarantees byte alignment. Keep the allocation in u64 words.
        let words = (needed as usize + 7) / 8;
        let mut buffer = vec![0u64; words];
        if GetTokenInformation(
            token,
            TokenIntegrityLevel,
            Some(buffer.as_mut_ptr() as *mut core::ffi::c_void),
            needed,
            &mut needed,
        )
        .is_err()
        {
            return "unknown";
        }
        let label = &*(buffer.as_ptr() as *const TOKEN_MANDATORY_LABEL);
        if label.Label.Sid.is_invalid() {
            return "unknown";
        }
        let count = *GetSidSubAuthorityCount(label.Label.Sid);
        if count == 0 {
            return "unknown";
        }
        let rid = *GetSidSubAuthority(label.Label.Sid, u32::from(count - 1));
        integrity_label(rid)
    }
}

#[cfg(not(windows))]
pub fn current_process_integrity() -> &'static str {
    "unknown"
}

#[cfg(test)]
mod tests {
    use super::integrity_label;

    #[test]
    fn maps_all_integrity_bands() {
        assert_eq!(integrity_label(0x0000), "untrusted");
        assert_eq!(integrity_label(0x1000), "low");
        assert_eq!(integrity_label(0x2000), "medium");
        assert_eq!(integrity_label(0x3000), "high");
        assert_eq!(integrity_label(0x4000), "system");
    }
}

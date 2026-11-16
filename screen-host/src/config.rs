//! Host configuration from environment variables and CLI flags.
//!
//! Defaults follow `docs/remote-desktop-decisions.md` D5:
//! maxFps 30, auto bitrate, VP8 software encoder, display "0".

use serde::{Deserialize, Serialize};

/// Quality bounds from decisions D5 / D6 (`remoteDesktopMaxFps` 5–60,
/// `remoteDesktopMaxBitrateKbps` 0 or 500–100000; 0 means automatic).
pub const MIN_FPS: u32 = 5;
pub const MAX_FPS: u32 = 60;
pub const MIN_BITRATE_KBPS: u32 = 500;
pub const MAX_BITRATE_KBPS: u32 = 100_000;
pub const MIN_WIDTH: u32 = 320;
pub const MAX_WIDTH: u32 = 3840;

pub const DEFAULT_MAX_FPS: u32 = 30;
/// `0` asks the host to derive the encoder ceiling from the actual capture size
/// and frame rate; it is not a 0 kbps cap.
pub const DEFAULT_MAX_BITRATE_KBPS: u32 = 0;
pub const DEFAULT_MAX_WIDTH: u32 = 1920;

/// Automatic bitrate budget: 0.15 bit per pixel per frame (3/20), about
/// 9.3 Mbps for 1080p30 and 37 Mbps for 4K30. Screen content usually encodes
/// below this; WebRTC bandwidth estimation remains the real limiter.
const AUTO_BITRATE_BITS_PER_PIXEL_NUM: u64 = 3;
const AUTO_BITRATE_BITS_PER_PIXEL_DEN: u64 = 20;
const MIN_AUTO_BITRATE_KBPS: u64 = 2_000;
const MAX_AUTO_BITRATE_KBPS: u64 = 100_000;
pub const DEFAULT_DISPLAY_ID: &str = "0";
pub const DEFAULT_ENCODER: &str = "libwebrtc-sw";

/// Effective quality (always fully populated and clamped).
#[derive(Debug, Clone, Copy, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Quality {
    pub max_fps: u32,
    pub max_bitrate_kbps: u32,
    pub max_width: u32,
}

impl Default for Quality {
    fn default() -> Self {
        Self {
            max_fps: DEFAULT_MAX_FPS,
            max_bitrate_kbps: DEFAULT_MAX_BITRATE_KBPS,
            max_width: DEFAULT_MAX_WIDTH,
        }
    }
}

impl Quality {
    /// Clamp out-of-range values into the supported window (decisions D5).
    pub fn clamped(mut self) -> Self {
        self.max_fps = self.max_fps.clamp(MIN_FPS, MAX_FPS);
        self.max_bitrate_kbps = if self.max_bitrate_kbps == 0 {
            0
        } else {
            self.max_bitrate_kbps
                .clamp(MIN_BITRATE_KBPS, MAX_BITRATE_KBPS)
        };
        self.max_width = self.max_width.clamp(MIN_WIDTH, MAX_WIDTH);
        self
    }

    /// Encoder ceiling for a capture of `width`×`height` at `max_fps`.
    ///
    /// An explicit `maxBitrateKbps` still wins (backward compatibility). With the
    /// default `0` the ceiling scales with resolution and frame rate, so a larger
    /// desktop or a higher frame rate is not silently held at the old 20 Mbps
    /// wall. This is a ceiling for libwebrtc, not a target: the actual send rate
    /// is whatever the bandwidth estimator and the content allow.
    pub fn effective_bitrate_kbps(&self, width: u32, height: u32) -> u32 {
        if self.max_bitrate_kbps > 0 {
            return self.max_bitrate_kbps;
        }
        let pixels = u64::from(width.max(2)) * u64::from(height.max(2));
        let fps = u64::from(self.max_fps.max(1));
        let kbps = pixels
            .saturating_mul(fps)
            .saturating_mul(AUTO_BITRATE_BITS_PER_PIXEL_NUM)
            / (AUTO_BITRATE_BITS_PER_PIXEL_DEN * 1000);
        kbps.clamp(MIN_AUTO_BITRATE_KBPS, MAX_AUTO_BITRATE_KBPS) as u32
    }
}

impl<'de> serde::Deserialize<'de> for Quality {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        // Deserializing a full Quality from a patch means "start from defaults,
        // apply what is present, clamp". Host-side patching goes through
        // [`QualityPatch::apply`] with the current value as base.
        QualityPatch::deserialize(deserializer).map(|patch| patch.apply(Quality::default()))
    }
}

/// Sparse quality update (`host.updateQuality` / `host.start` params). Unlike
/// [`Quality`], missing fields stay `None` so callers can patch one knob at a time;
/// out-of-range values are preserved here and clamped when applied.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct QualityPatch {
    pub max_fps: Option<u32>,
    pub max_bitrate_kbps: Option<u32>,
    pub max_width: Option<u32>,
}

impl QualityPatch {
    /// Apply the patch over `base`, clamping into the supported window.
    pub fn apply(self, base: Quality) -> Quality {
        Quality {
            max_fps: self.max_fps.unwrap_or(base.max_fps),
            max_bitrate_kbps: self.max_bitrate_kbps.unwrap_or(base.max_bitrate_kbps),
            max_width: self.max_width.unwrap_or(base.max_width),
        }
        .clamped()
    }
}

impl<'de> serde::Deserialize<'de> for QualityPatch {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        #[derive(serde::Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct RawQuality {
            max_fps: Option<u32>,
            max_bitrate_kbps: Option<u32>,
            max_width: Option<u32>,
        }
        let raw = RawQuality::deserialize(deserializer)?;
        Ok(QualityPatch {
            max_fps: raw.max_fps,
            max_bitrate_kbps: raw.max_bitrate_kbps,
            max_width: raw.max_width,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct HostConfig {
    pub protocol_version: u8,
    pub quality: Quality,
    pub display: String,
    pub encoder: String,
}

impl Default for HostConfig {
    fn default() -> Self {
        Self {
            protocol_version: crate::protocol::PROTOCOL_VERSION,
            quality: Quality::default(),
            display: DEFAULT_DISPLAY_ID.to_string(),
            encoder: DEFAULT_ENCODER.to_string(),
        }
    }
}

impl HostConfig {
    /// Load from environment variables; missing values keep the defaults.
    ///
    /// - `AA_SCREEN_PROTOCOL_VERSION`
    /// - `AA_SCREEN_MAX_FPS` / `AA_SCREEN_MAX_BITRATE_KBPS` / `AA_SCREEN_MAX_WIDTH`
    /// - `AA_SCREEN_DISPLAY`
    /// - `AA_SCREEN_ENCODER`
    pub fn from_env() -> Self {
        let mut cfg = Self::default();
        if let Ok(v) = std::env::var("AA_SCREEN_PROTOCOL_VERSION") {
            if let Ok(v) = v.parse::<u8>() {
                cfg.protocol_version = v;
            }
        }
        if let Ok(v) = std::env::var("AA_SCREEN_MAX_FPS") {
            if let Ok(v) = v.parse::<u32>() {
                cfg.quality.max_fps = v;
            }
        }
        if let Ok(v) = std::env::var("AA_SCREEN_MAX_BITRATE_KBPS") {
            if let Ok(v) = v.parse::<u32>() {
                cfg.quality.max_bitrate_kbps = v;
            }
        }
        if let Ok(v) = std::env::var("AA_SCREEN_MAX_WIDTH") {
            if let Ok(v) = v.parse::<u32>() {
                cfg.quality.max_width = v;
            }
        }
        if let Ok(v) = std::env::var("AA_SCREEN_DISPLAY") {
            if !v.is_empty() {
                cfg.display = v;
            }
        }
        if let Ok(v) = std::env::var("AA_SCREEN_ENCODER") {
            if !v.is_empty() {
                cfg.encoder = v;
            }
        }
        cfg.quality = cfg.quality.clamped();
        cfg
    }

    /// Apply simple `--key=value` style CLI overrides (used by `--once` and tests).
    pub fn apply_arg(&mut self, arg: &str) {
        let Some((key, value)) = arg.split_once('=') else {
            return;
        };
        match key.trim_start_matches('-') {
            "max-fps" => {
                if let Ok(v) = value.parse() {
                    self.quality.max_fps = v;
                }
            }
            "max-bitrate-kbps" => {
                if let Ok(v) = value.parse() {
                    self.quality.max_bitrate_kbps = v;
                }
            }
            "max-width" => {
                if let Ok(v) = value.parse() {
                    self.quality.max_width = v;
                }
            }
            "display" => self.display = value.to_string(),
            "encoder" => self.encoder = value.to_string(),
            _ => {}
        }
        self.quality = self.quality.clamped();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_match_decisions() {
        let cfg = HostConfig::default();
        assert_eq!(cfg.protocol_version, 1);
        assert_eq!(cfg.quality.max_fps, 30);
        assert_eq!(cfg.quality.max_bitrate_kbps, 0);
        assert_eq!(cfg.quality.max_width, 1920);
        assert_eq!(cfg.display, "0");
        assert_eq!(cfg.encoder, "libwebrtc-sw");
    }

    #[test]
    fn quality_clamps_out_of_range() {
        let q = Quality {
            max_fps: 120,
            max_bitrate_kbps: 100,
            max_width: 8000,
        }
        .clamped();
        assert_eq!(q.max_fps, 60);
        assert_eq!(q.max_bitrate_kbps, 500);
        assert_eq!(q.max_width, 3840);

        let q = Quality {
            max_fps: 1,
            max_bitrate_kbps: 1_000_000,
            max_width: 100,
        }
        .clamped();
        assert_eq!(q.max_fps, 5);
        assert_eq!(q.max_bitrate_kbps, MAX_BITRATE_KBPS);
        assert_eq!(q.max_width, 320);

        let auto = Quality {
            max_fps: 30,
            max_bitrate_kbps: 0,
            max_width: 1920,
        }
        .clamped();
        assert_eq!(auto.max_bitrate_kbps, 0);
    }

    #[test]
    fn auto_bitrate_scales_with_resolution_and_fps() {
        let q = Quality {
            max_fps: 30,
            max_bitrate_kbps: 0,
            max_width: 1920,
        };
        // 1920×1080×30×0.15 bit/pixel ≈ 9.3 Mbps.
        assert_eq!(q.effective_bitrate_kbps(1920, 1080), 9_331);
        // A larger desktop gets a larger encoder budget instead of the old fixed
        // 20 Mbps wall: 2560×1440@30 ≈ 16.6 Mbps.
        assert_eq!(q.effective_bitrate_kbps(2560, 1440), 16_588);
        assert_eq!(q.effective_bitrate_kbps(3840, 2160), 37_324);

        let fast = Quality { max_fps: 60, ..q };
        assert_eq!(fast.effective_bitrate_kbps(1920, 1080), 18_662);

        let explicit = Quality {
            max_bitrate_kbps: 4_000,
            ..q
        };
        assert_eq!(explicit.effective_bitrate_kbps(3840, 2160), 4_000);
    }

    #[test]
    fn env_override_and_cli_override() {
        std::env::set_var("AA_SCREEN_MAX_FPS", "24");
        let mut cfg = HostConfig::from_env();
        assert_eq!(cfg.quality.max_fps, 24);
        std::env::remove_var("AA_SCREEN_MAX_FPS");

        cfg.apply_arg("--max-width=1280");
        cfg.apply_arg("--display=1");
        cfg.apply_arg("encoder=mf-hw");
        assert_eq!(cfg.quality.max_width, 1280);
        assert_eq!(cfg.display, "1");
        assert_eq!(cfg.encoder, "mf-hw");
        cfg.apply_arg("not-a-kv");
        cfg.apply_arg("--unknown=1");
    }

    #[test]
    fn quality_patch_partial_update() {
        let base = Quality {
            max_fps: 30,
            max_bitrate_kbps: 4000,
            max_width: 1920,
        };
        let patch: QualityPatch = serde_json::from_str(r#"{"maxFps": 24}"#).unwrap();
        let applied = patch.apply(base);
        assert_eq!(applied.max_fps, 24);
        assert_eq!(applied.max_bitrate_kbps, 4000);
        assert_eq!(applied.max_width, 1920);
        // Empty patch is a no-op (base is already in range, so clamping changes nothing).
        let patch: QualityPatch = serde_json::from_str("{}").unwrap();
        assert_eq!(patch.apply(base), base);
    }

    #[test]
    fn quality_full_deserialize_clamps() {
        let q: Quality =
            serde_json::from_str(r#"{"maxFps": 500, "maxBitrateKbps": 10, "maxWidth": 10}"#)
                .unwrap();
        assert_eq!(q.max_fps, MAX_FPS);
        assert_eq!(q.max_bitrate_kbps, MIN_BITRATE_KBPS);
        assert_eq!(q.max_width, MIN_WIDTH);
    }
}

//! aa-screen-host: remote-desktop screen host speaking HostControl NDJSON over stdio.
//!
//! - stdout: protocol frames only (one JSON object per line, flushed).
//! - stderr: logs only (env_logger).
//! - A single inbound frame larger than 4 MiB or containing invalid JSON produces an
//!   `error` frame with code `rd_host_protocol_error` and exit code 78.
//! - EOF / shutdown frame triggers release_all + graceful exit 0.

use std::io::{BufRead, Write};
use std::sync::mpsc::{self, RecvTimeoutError};
use std::time::{Duration, Instant};

use aa_screen_host::capture::{self, CaptureSource};
use aa_screen_host::config::HostConfig;
use aa_screen_host::host::{EventSink, Host};
use aa_screen_host::input;
use aa_screen_host::protocol::{
    protocol_error, Arch, Hello, HostControlMessage, Platform, Request, ERR_PROTOCOL,
    PROTOCOL_VERSION,
};
use aa_screen_host::publish;

/// `EX_CONFIG` from sysexits(3): handshake/protocol failure.
const EXIT_PROTOCOL_ERROR: i32 = 78;

struct StdioEventSink {
    out: std::io::Stdout,
}

impl StdioEventSink {
    fn new() -> Self {
        Self {
            out: std::io::stdout(),
        }
    }
}

impl EventSink for StdioEventSink {
    fn emit(&mut self, msg: HostControlMessage) {
        write_frame(&mut self.out, &msg);
    }
}

fn write_frame(out: &mut impl Write, msg: &HostControlMessage) {
    if let Err(e) = serde_json::to_writer(&mut *out, msg) {
        log::error!("failed to serialize protocol frame: {e}");
        return;
    }
    let _ = out.write_all(b"\n");
    let _ = out.flush();
}

fn hello_frame(config: &HostConfig) -> HostControlMessage {
    let platform = if cfg!(windows) {
        Platform::Windows
    } else if cfg!(target_os = "macos") {
        Platform::Macos
    } else if cfg!(unix) {
        Platform::Linux
    } else {
        Platform::Unknown
    };
    let arch = if cfg!(target_arch = "x86_64") {
        Arch::X8664
    } else if cfg!(target_arch = "aarch64") {
        Arch::Aarch64
    } else {
        Arch::Unknown
    };
    HostControlMessage::Hello(Hello {
        v: PROTOCOL_VERSION,
        protocol_version: config.protocol_version,
        host_version: env!("CARGO_PKG_VERSION").to_string(),
        platform,
        arch,
        pid: std::process::id(),
        capabilities: serde_json::json!({
            "capture": cfg!(windows),
            "wgc": cfg!(feature = "wgc"),
            "input": cfg!(windows),
            "publish": if cfg!(feature = "livekit") { vec!["livekit"] } else { vec!["stub"] },
            "codecs": ["vp8"],
            // A low-integrity host cannot pass the Windows UIPI boundary to reach a
            // normal user's windows; the Connector refuses it instead of showing a
            // picture that can never be controlled.
            "integrity": aa_screen_host::integrity::current_process_integrity(),
        }),
    })
}

fn print_usage() {
    eprintln!(
        "aa-screen-host {}\n\
         \n\
         USAGE:\n  \
         aa-screen-host [OPTIONS]\n\
         \n\
         OPTIONS:\n  \
         --version        Print version and exit\n  \
         --stdio          Run the NDJSON control loop over stdin/stdout (default)\n  \
         --once           Read one request line, answer it, then shut down\n  \
         --max-fps=N      Default quality override\n  \
         --max-bitrate-kbps=N\n  \
         --max-width=N\n  \
         --display=ID\n  \
         --encoder=NAME\n\
         \n\
         ENV:\n  \
         AA_SCREEN_MAX_FPS / AA_SCREEN_MAX_BITRATE_KBPS / AA_SCREEN_MAX_WIDTH\n  \
         AA_SCREEN_DISPLAY / AA_SCREEN_ENCODER / AA_SCREEN_PROTOCOL_VERSION\n",
        env!("CARGO_PKG_VERSION")
    );
}

fn main() {
    // Logs go to stderr only; stdout stays protocol-pure.
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info"))
        .target(env_logger::Target::Stderr)
        .format_timestamp_millis()
        .init();

    log::info!(
        "process integrity: {}",
        aa_screen_host::integrity::current_process_integrity()
    );
    let mut config = HostConfig::from_env();
    let mut once = false;
    let args = std::env::args().skip(1);
    for arg in args {
        match arg.as_str() {
            "--version" => {
                println!("aa-screen-host {}", env!("CARGO_PKG_VERSION"));
                return;
            }
            "--stdio" => {}
            "--once" => once = true,
            "-h" | "--help" => {
                print_usage();
                return;
            }
            other if other.starts_with('-') => config.apply_arg(other),
            other => {
                eprintln!("ignoring unknown argument: {other}");
            }
        }
    }

    let host = Host::new(
        &config,
        capture::new_capture_source(config.quality),
        publish::new_publisher(),
        input::new_input_sink(),
        Box::new(StdioEventSink::new()),
    );
    let mut app = App {
        host,
        once,
        last_stats: Instant::now(),
    };

    // Greet immediately so the connector learns the wire is alive.
    let hello = hello_frame(&config);
    write_frame(&mut std::io::stdout(), &hello);

    std::process::exit(app.run());
}

struct App {
    host: Host,
    once: bool,
    /// Writes a `stats` event at most once per [`STATS_INTERVAL`].
    last_stats: Instant,
}

/// How often the host reports encoder/RTP stats to the Connector.
const STATS_INTERVAL: Duration = Duration::from_secs(2);

/// How often the host drains the media data-channel input queue while idle on stdin.
/// The old 200 ms timeout added up to 200 ms to every pointer move; 8 ms keeps the
/// pointer plane at roughly the same cadence as the 120 Hz client throttle.
const INPUT_POLL_INTERVAL: Duration = Duration::from_millis(8);

impl App {
    fn run(&mut self) -> i32 {
        let mut out = std::io::stdout();
        let mut answered_any = false;
        let (sender, receiver) = mpsc::channel();
        std::thread::Builder::new()
            .name("rd-stdin-reader".to_string())
            .spawn(move || {
                let stdin = std::io::stdin();
                for line in stdin.lock().lines() {
                    if sender.send(line).is_err() {
                        break;
                    }
                }
            })
            .ok();

        loop {
            let line = match receiver.recv_timeout(INPUT_POLL_INTERVAL) {
                Ok(Ok(line)) => line,
                Ok(Err(error)) => {
                    log::error!("stdin read error: {error}");
                    break;
                }
                Err(RecvTimeoutError::Timeout) => {
                    if aa_screen_host::overlay::stop_requested() {
                        log::info!("local sharing overlay requested stop");
                        break;
                    }
                    // Idle window: apply any input that arrived on the data channel
                    // and report the publisher stats the client needs for the quality
                    // overlay.
                    self.host.apply_channel_input();
                    self.emit_stats_if_due();
                    continue;
                }
                Err(RecvTimeoutError::Disconnected) => break,
            };
            if let Some(code) = self.handle_line(&line, &mut out, &mut answered_any) {
                return code;
            }
            self.host.apply_channel_input();
            self.emit_stats_if_due();
            if self.once && answered_any {
                break;
            }
        }

        // EOF (or local overlay stop): release everything and exit cleanly.
        self.host.shutdown();
        0
    }

    /// Emits a stats event at most once per [`STATS_INTERVAL`].
    fn emit_stats_if_due(&mut self) {
        if self.last_stats.elapsed() < STATS_INTERVAL {
            return;
        }
        self.host.emit_stats();
        self.last_stats = Instant::now();
    }

    /// Handle one NDJSON line; returns an exit code only on a terminal frame.
    fn handle_line(
        &mut self,
        line: &str,
        out: &mut std::io::Stdout,
        answered_any: &mut bool,
    ) -> Option<i32> {
        if line.len() + 1 > aa_screen_host::protocol::MAX_FRAME_BYTES {
            write_frame(
                out,
                &protocol_error(
                    ERR_PROTOCOL,
                    format!(
                        "frame exceeds {} bytes",
                        aa_screen_host::protocol::MAX_FRAME_BYTES
                    ),
                ),
            );
            self.host.shutdown();
            return Some(EXIT_PROTOCOL_ERROR);
        }
        if line.trim().is_empty() {
            return None;
        }

        let parsed: Result<HostControlMessage, _> = serde_json::from_str(line);
        let msg = match parsed {
            Ok(message) => message,
            Err(error) => {
                write_frame(
                    out,
                    &protocol_error(ERR_PROTOCOL, format!("invalid JSON frame: {error}")),
                );
                self.host.shutdown();
                return Some(EXIT_PROTOCOL_ERROR);
            }
        };

        match msg {
            HostControlMessage::Request(Request {
                id, method, params, ..
            }) => {
                let response = self.host.handle_request(id, method, &params);
                write_frame(out, &response);
                *answered_any = true;
            }
            HostControlMessage::Shutdown(shutdown) => {
                log::info!(
                    "shutdown frame received: {}",
                    shutdown.reason.as_deref().unwrap_or("unspecified")
                );
                self.host.shutdown();
                // `Host::shutdown` already emits the terminal `stopped` state
                // when a session was running; emitting another one here would
                // duplicate the last state event.
                return Some(0);
            }
            other => {
                log::warn!("ignoring unexpected inbound frame: {other:?}");
            }
        }
        None
    }
}

#[allow(dead_code)]
fn assert_send() {
    fn is_send<T: Send>() {}
    is_send::<Host>();
    is_send::<Box<dyn CaptureSource>>();
}

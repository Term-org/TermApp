//! Helpers shared by the command line tools of the Term apps.

use std::env;
use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::OnceLock;
use std::thread::sleep;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

pub struct Icons {
    pub ok: &'static str,
    pub err: &'static str,
    pub warn: &'static str,
    pub inst: &'static str,
    pub avail: &'static str,
    pub upg: &'static str,
    pub frozen: &'static str,
    pub pkg: &'static str,
    pub info: &'static str,
    pub src: &'static str,
    pub doc: &'static str,
    pub ver: &'static str,
    pub sep: &'static str,
}

const NERD: Icons = Icons {
    ok: "\u{f00c}",
    err: "\u{f00d}",
    warn: "\u{f071}",
    inst: "\u{f058}",
    avail: "\u{f10c}",
    upg: "\u{f0aa}",
    frozen: "\u{f2dc}",
    pkg: "\u{f1b2}",
    info: "\u{f05a}",
    src: "\u{f1c0}",
    doc: "\u{f02d}",
    ver: "\u{f02b}",
    sep: "\u{2502}",
};

const ASCII: Icons = Icons {
    ok: "+",
    err: "x",
    warn: "!",
    inst: "*",
    avail: "-",
    upg: "^",
    frozen: "#",
    pkg: ">",
    info: "i",
    src: "@",
    doc: "?",
    ver: "v",
    sep: "|",
};

/// Nerd Font icons, or plain text when TN_ASCII=1 (set by the app when no font is installed).
pub fn icons() -> &'static Icons {
    static CELL: OnceLock<&'static Icons> = OnceLock::new();
    CELL.get_or_init(|| {
        if var("TN_ASCII").as_deref() == Some("1") {
            &ASCII
        } else {
            &NERD
        }
    })
}

pub fn ok(message: &str) {
    println!("{} {}", icons().ok, message);
}

pub fn err(message: &str) {
    eprintln!("{} {}", icons().err, message);
}

pub fn warn(message: &str) {
    eprintln!("{} {}", icons().warn, message);
}

const B64: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

pub fn base64(data: &[u8]) -> String {
    let mut out = String::with_capacity((data.len() + 2) / 3 * 4);
    for chunk in data.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = *chunk.get(1).unwrap_or(&0) as u32;
        let b2 = *chunk.get(2).unwrap_or(&0) as u32;
        let n = (b0 << 16) | (b1 << 8) | b2;
        out.push(B64[((n >> 18) & 63) as usize] as char);
        out.push(B64[((n >> 12) & 63) as usize] as char);
        out.push(if chunk.len() > 1 { B64[((n >> 6) & 63) as usize] as char } else { '=' });
        out.push(if chunk.len() > 2 { B64[(n & 63) as usize] as char } else { '=' });
    }
    out
}

/// An environment variable that is set and not empty.
pub fn var(name: &str) -> Option<String> {
    env::var(name).ok().filter(|v| !v.is_empty())
}

pub fn home() -> PathBuf {
    PathBuf::from(var("HOME").unwrap_or_else(|| "/".to_string()))
}

pub fn tmpdir() -> PathBuf {
    PathBuf::from(var("TMPDIR").unwrap_or_else(|| "/tmp".to_string()))
}

pub fn prefix() -> Option<PathBuf> {
    var("PREFIX").map(PathBuf::from)
}

/// A token that is unique enough to tell concurrent calls apart.
pub fn token() -> String {
    let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default();
    format!("{}-{}-{}", std::process::id(), now.as_secs(), now.subsec_nanos())
}

fn emit(text: &str) {
    if let Ok(mut tty) = OpenOptions::new().write(true).open("/dev/tty") {
        if tty.write_all(text.as_bytes()).is_ok() {
            let _ = tty.flush();
            return;
        }
    }
    let mut stdout = io::stdout();
    let _ = stdout.write_all(text.as_bytes());
    let _ = stdout.flush();
}

/// Sends a command to the app: ESC ] 1337 ; TermCmd=name ; key=base64(value) ... BEL
pub fn osc(name: &str, args: &[(&str, &str)]) {
    let mut text = format!("\x1b]1337;TermCmd={}", name);
    for (key, value) in args {
        text.push(';');
        text.push_str(key);
        text.push('=');
        text.push_str(&base64(value.as_bytes()));
    }
    text.push('\x07');
    emit(&text);
}

/// Sends a raw 1337 payload, for example "TermVnc=open;host=...;port=...".
pub fn osc_raw(payload: &str) {
    emit(&format!("\x1b]1337;{}\x07", payload));
}

/// Polls until the condition holds or the time is up.
pub fn wait_until<F: FnMut() -> bool>(seconds: u64, mut done: F) -> bool {
    let deadline = Instant::now() + Duration::from_secs(seconds);
    loop {
        if done() {
            return true;
        }
        if Instant::now() >= deadline {
            return false;
        }
        sleep(Duration::from_millis(250));
    }
}

/// Removes control characters (including ESC) from text that comes from outside.
pub fn sanitize(text: &str) -> String {
    text.chars().filter(|c| *c == '\n' || *c == '\t' || !c.is_control()).collect()
}

pub fn is_tty(fd: i32) -> bool {
    unsafe { libc::isatty(fd) == 1 }
}

pub fn which(name: &str) -> Option<PathBuf> {
    let path = env::var("PATH").ok()?;
    for dir in path.split(':') {
        if dir.is_empty() {
            continue;
        }
        let candidate = Path::new(dir).join(name);
        if let Ok(meta) = fs::metadata(&candidate) {
            if meta.is_file() && meta.permissions().mode() & 0o111 != 0 {
                return Some(candidate);
            }
        }
    }
    None
}

/// Runs a program and returns what it printed; None when it could not be started.
pub fn capture(program: &str, args: &[&str]) -> Option<String> {
    let output = Command::new(program)
        .args(args)
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .output()
        .ok()?;
    Some(String::from_utf8_lossy(&output.stdout).into_owned())
}

/// Reads key=value from a properties file.
pub fn read_property(path: &Path, key: &str) -> Option<String> {
    let content = fs::read_to_string(path).ok()?;
    content.lines().find_map(|line| {
        let line = line.trim();
        if line.starts_with('#') {
            return None;
        }
        let (k, v) = line.split_once('=')?;
        if k.trim() == key {
            Some(v.trim().to_string())
        } else {
            None
        }
    })
}
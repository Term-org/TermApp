use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::PathBuf;
use std::process::{Command, Stdio};

use termcore::capture;

use crate::apt;

const PROTECTED: &[&str] = &[
    "bash", "dash", "coreutils", "apt", "dpkg", "findutils", "grep", "sed", "gawk", "curl", "tar",
    "gzip", "util-linux", "openssl", "ca-certificates", "termux-core", "termux-exec", "termux-tools",
    "libc++",
];

fn dir() -> PathBuf {
    apt::prefix_dir().join("var/lib/tn/frozen")
}

pub fn is_frozen(pkg: &str) -> bool {
    dir().join(pkg).exists()
}

fn is_protected(pkg: &str) -> bool {
    if PROTECTED.contains(&pkg) {
        return true;
    }
    capture("dpkg-query", &["--showformat=${Essential}", "-W", pkg])
        .map(|s| s.trim() == "yes")
        .unwrap_or(false)
}

fn hold(pkg: &str, on: bool) {
    let _ = Command::new("apt-mark")
        .args([if on { "hold" } else { "unhold" }, pkg])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();
}

/// Takes the execute permission from the programs of a package and holds the package.
/// Libraries stay as they are, so other packages keep working.
pub fn freeze(pkg: &str) -> Result<String, String> {
    if !apt::is_installed(pkg) {
        return Err(format!("{} is not installed", pkg));
    }
    if is_frozen(pkg) {
        return Err(format!("{} is already frozen", pkg));
    }
    if is_protected(pkg) {
        return Err(format!("{} is needed by the system and cannot be frozen", pkg));
    }

    let list = capture("dpkg", &["-L", pkg]).unwrap_or_default();
    let mut programs: Vec<(String, u32)> = Vec::new();
    for path in list.lines() {
        let meta = match fs::symlink_metadata(path) {
            Ok(m) => m,
            Err(_) => continue,
        };
        if !meta.file_type().is_file() {
            continue;
        }
        let mode = meta.permissions().mode() & 0o7777;
        if mode & 0o111 == 0 || path.ends_with(".so") || path.contains(".so.") {
            continue;
        }
        programs.push((path.to_string(), mode));
    }
    if programs.is_empty() {
        return Err(format!("{} has no programs to freeze", pkg));
    }

    // The record is written first, so a failure never leaves programs without a way back.
    let record: String = programs.iter().map(|(p, m)| format!("{:o}\t{}\n", m, p)).collect();
    fs::create_dir_all(dir()).map_err(|e| e.to_string())?;
    fs::write(dir().join(pkg), record).map_err(|e| e.to_string())?;
    for (path, mode) in &programs {
        let _ = fs::set_permissions(path, fs::Permissions::from_mode(mode & !0o111));
    }
    hold(pkg, true);
    Ok(format!("{} is frozen", pkg))
}

pub fn unfreeze(pkg: &str) -> Result<String, String> {
    let state = dir().join(pkg);
    let record = fs::read_to_string(&state).map_err(|_| format!("{} is not frozen", pkg))?;
    for line in record.lines() {
        if let Some((mode, path)) = line.split_once('\t') {
            if let Ok(mode) = u32::from_str_radix(mode, 8) {
                if fs::metadata(path).is_ok() {
                    let _ = fs::set_permissions(path, fs::Permissions::from_mode(mode));
                }
            }
        }
    }
    let _ = fs::remove_file(&state);
    hold(pkg, false);
    Ok(format!("{} is unfrozen", pkg))
}
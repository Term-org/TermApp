use std::fs;
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::Ordering;

use termcore::{err, is_tty, ok, tmpdir, token, var, warn, which};

use crate::apt::{apt_dir, list_dir};
use crate::ASSUME_YES;

fn key_dir() -> PathBuf {
    apt_dir().join("keyrings")
}

fn tcp_base() -> String {
    var("TN_TCP_URL").unwrap_or_else(|| "https://term-org.github.io/TCP".to_string())
}

fn confirm(question: &str) -> bool {
    if ASSUME_YES.load(Ordering::Relaxed) {
        return true;
    }
    if !is_tty(0) {
        err("a confirmation is needed; run the command again with -y");
        return false;
    }
    print!("{} [y/N] ", question);
    let _ = io::stdout().flush();
    let mut answer = String::new();
    if io::stdin().read_line(&mut answer).is_err() {
        return false;
    }
    matches!(answer.trim(), "y" | "Y" | "yes" | "YES")
}

fn valid_label(text: &str) -> bool {
    let mut chars = text.chars();
    match chars.next() {
        Some(c) if c.is_ascii_lowercase() || c.is_ascii_digit() => {}
        _ => return false,
    }
    chars.all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_' || c == '-')
}

fn valid_word(text: &str) -> bool {
    !text.is_empty() && text.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '_' || c == '-')
}

fn url_ok(url: &str) -> bool {
    Command::new("curl")
        .args(["-fsL", "--max-time", "25", "-o", "/dev/null", url])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

fn list_url(path: &Path) -> Option<String> {
    let content = fs::read_to_string(path).ok()?;
    content.lines().find_map(|line| {
        let line = line.trim();
        if !line.starts_with("deb ") && !line.starts_with("deb\t") {
            return None;
        }
        line.split_whitespace()
            .find(|t| t.starts_with("https://") || t.starts_with("http://"))
            .map(String::from)
    })
}

fn list() -> i32 {
    let main = apt_dir().join("sources.list");
    if main.exists() {
        let url = list_url(&main).unwrap_or_else(|| "(none)".to_string());
        println!("  {:<12} {:<10} {}", "tp", "active", url);
    }
    let mut files: Vec<PathBuf> = match fs::read_dir(list_dir()) {
        Ok(read) => read.flatten().map(|e| e.path()).collect(),
        Err(_) => Vec::new(),
    };
    files.sort();
    let mut has_tcp = false;
    for path in files {
        let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("").to_string();
        let (label, state) = if let Some(l) = name.strip_suffix(".list.disabled") {
            (l.to_string(), "inactive")
        } else if let Some(l) = name.strip_suffix(".list") {
            (l.to_string(), "active")
        } else {
            continue;
        };
        if label == "tcp" {
            has_tcp = true;
        }
        println!("  {:<12} {:<10} {}", label, state, list_url(&path).unwrap_or_default());
    }
    if !has_tcp {
        println!("  {:<12} {:<10} {}", "tcp", "not added", "(tn tcp:activate)");
    }
    0
}

fn add(args: &[String], allow_reserved: bool) -> i32 {
    let mut url = String::new();
    let mut label = String::new();
    let mut suite = "stable".to_string();
    let mut component = "main".to_string();
    let mut key = String::new();

    let mut i = 0;
    while i < args.len() {
        let value = args.get(i + 1).cloned().unwrap_or_default();
        match args[i].as_str() {
            "-l" | "-label" | "--label" => {
                if value.is_empty() {
                    err("-l needs a label");
                    return 1;
                }
                label = value;
                i += 2;
            }
            "-s" | "--suite" => {
                if value.is_empty() {
                    err("-s needs a suite");
                    return 1;
                }
                suite = value;
                i += 2;
            }
            "-m" | "--component" => {
                if value.is_empty() {
                    err("-m needs a component");
                    return 1;
                }
                component = value;
                i += 2;
            }
            "-k" | "--key" => {
                if value.is_empty() {
                    err("-k needs a key (a link or a file)");
                    return 1;
                }
                key = value;
                i += 2;
            }
            "-y" | "--yes" => {
                ASSUME_YES.store(true, Ordering::Relaxed);
                i += 1;
            }
            other if other.starts_with('-') => {
                err(&format!("unknown option: {}", other));
                return 1;
            }
            other => {
                if !url.is_empty() {
                    err(&format!("unexpected argument: {}", other));
                    return 1;
                }
                url = other.to_string();
                i += 1;
            }
        }
    }

    if url.is_empty() {
        err("usage: tn -c add <link> -l <label>");
        return 1;
    }
    if label.is_empty() {
        err("the repository needs a label: -l <label>");
        return 1;
    }
    if !url.starts_with("https://") {
        err("the link must start with https://");
        return 1;
    }
    let url = url.trim_end_matches('/').to_string();
    if !valid_label(&label) {
        err("invalid label (use a-z, 0-9, _ and -)");
        return 1;
    }
    if !allow_reserved && matches!(label.as_str(), "tp" | "tcp" | "gp") {
        err(&format!("the label '{}' is reserved", label));
        return 1;
    }
    if list_dir().join(format!("{}.list", label)).exists()
        || list_dir().join(format!("{}.list.disabled", label)).exists()
    {
        err(&format!("a repository with the label '{}' already exists", label));
        return 1;
    }
    if !valid_word(&suite) || !valid_word(&component) {
        err("invalid suite or component");
        return 1;
    }
    if which("curl").is_none() {
        err("curl was not found. Run: tn doctor");
        return 1;
    }

    let base = format!("{}/dists/{}", url, suite);
    let in_release = url_ok(&format!("{}/InRelease", base));
    if !in_release && !url_ok(&format!("{}/Release", base)) {
        err(&format!("no apt repository was found at {} (suite {})", url, suite));
        return 1;
    }
    let signed = in_release || url_ok(&format!("{}/Release.gpg", base));

    let options: String;
    if !key.is_empty() {
        let temp = tmpdir().join(format!("tn-key.{}", token()));
        if key.starts_with("https://") {
            let target = temp.to_string_lossy().into_owned();
            let fetched = Command::new("curl")
                .args(["-fsSL", "--max-time", "30", key.as_str(), "-o", target.as_str()])
                .status()
                .map(|s| s.success())
                .unwrap_or(false);
            if !fetched {
                let _ = fs::remove_file(&temp);
                err("unable to download the key");
                return 1;
            }
        } else if fs::copy(&key, &temp).is_err() {
            err(&format!("key file not found: {}", key));
            return 1;
        }
        let data = fs::read(&temp).unwrap_or_default();
        if data.is_empty() {
            let _ = fs::remove_file(&temp);
            err("the key is empty");
            return 1;
        }
        let extension = if String::from_utf8_lossy(&data).contains("BEGIN PGP PUBLIC KEY BLOCK") { "asc" } else { "gpg" };
        let _ = fs::create_dir_all(key_dir());
        let dest = key_dir().join(format!("{}.{}", label, extension));
        if fs::write(&dest, &data).is_err() {
            let _ = fs::remove_file(&temp);
            err("unable to store the key");
            return 1;
        }
        let _ = fs::remove_file(&temp);
        options = format!("[signed-by={}] ", dest.display());
    } else if signed {
        err("the repository is signed; give its public key with -k <link or file>");
        return 1;
    } else {
        warn("this repository is not signed, so its packages cannot be verified");
        if !confirm("Add it anyway?") {
            err("cancelled");
            return 1;
        }
        options = "[trusted=yes] ".to_string();
    }

    let _ = fs::create_dir_all(list_dir());
    let line = format!("deb {}{} {} {}\n", options, url, suite, component);
    if fs::write(list_dir().join(format!("{}.list", label)), line).is_err() {
        err("unable to write the repository file");
        return 1;
    }
    ok(&format!("Added repository '{}'. Refresh the lists with: tn gp:update", label));
    0
}

fn remove(label: &str) -> i32 {
    if label.is_empty() {
        err("usage: tn -c remove <label>");
        return 1;
    }
    if !valid_label(label) {
        err("invalid label");
        return 1;
    }
    if label == "tp" {
        err("the main repository (tp) cannot be removed");
        return 1;
    }
    let active = list_dir().join(format!("{}.list", label));
    let inactive = list_dir().join(format!("{}.list.disabled", label));
    if !active.exists() && !inactive.exists() {
        err(&format!("no repository with the label '{}'", label));
        return 1;
    }
    let _ = fs::remove_file(active);
    let _ = fs::remove_file(inactive);
    let _ = fs::remove_file(key_dir().join(format!("{}.gpg", label)));
    let _ = fs::remove_file(key_dir().join(format!("{}.asc", label)));
    ok(&format!("Removed repository '{}'. Refresh the lists with: tn gp:update", label));
    0
}

fn tcp_add() -> i32 {
    let app = match var("TERM_APP_ID") {
        Some(a) => a,
        None => {
            err("TERM_APP_ID is not set");
            return 1;
        }
    };
    println!(
        "The community repository (TCP) contains packages built from recipes that third\n\
         parties contributed. Recipes are reviewed before they are built, but they are not\n\
         part of the main repository, and package scripts run on this device when you\n\
         install a package."
    );
    if !confirm("Activate the community repository?") {
        err("cancelled");
        return 1;
    }
    let base = tcp_base();
    let args = vec![
        format!("{}/{}", base, app),
        "-l".to_string(),
        "tcp".to_string(),
        "-k".to_string(),
        format!("{}/term-community.gpg", base),
        "-y".to_string(),
    ];
    add(&args, true)
}

pub fn toggle(label: &str, activate: bool) -> i32 {
    if !valid_label(label) {
        err(&format!("invalid repository label: {}", label));
        return 1;
    }
    if label == "tp" {
        if activate {
            ok("The main repository (tp) is always active");
            return 0;
        }
        err("the main repository (tp) cannot be turned off");
        return 1;
    }

    let active = list_dir().join(format!("{}.list", label));
    let inactive = list_dir().join(format!("{}.list.disabled", label));

    if activate {
        if active.exists() {
            ok(&format!("'{}' is already active", label));
            return 0;
        }
        if inactive.exists() {
            if fs::rename(&inactive, &active).is_err() {
                err(&format!("unable to activate '{}'", label));
                return 1;
            }
            ok(&format!("Activated '{}'. Refresh the lists with: tn gp:update", label));
            return 0;
        }
        if label == "tcp" {
            return tcp_add();
        }
        err(&format!("no repository with the label '{}'", label));
        return 1;
    }

    if active.exists() {
        if fs::rename(&active, &inactive).is_err() {
            err(&format!("unable to turn off '{}'", label));
            return 1;
        }
        ok(&format!("Turned off '{}'. Refresh the lists with: tn gp:update", label));
        return 0;
    }
    if inactive.exists() {
        ok(&format!("'{}' is already off", label));
        return 0;
    }
    err(&format!("no repository with the label '{}'", label));
    1
}

pub fn community(args: &[String]) -> i32 {
    match args.first().map(String::as_str) {
        Some("add") => add(&args[1..], false),
        Some("list") => list(),
        Some("remove") => remove(args.get(1).map(String::as_str).unwrap_or("")),
        _ => {
            err("usage: tn -c add <link> -l <label> | tn -c list | tn -c remove <label>");
            1
        }
    }
}
use std::fs;
use std::path::PathBuf;
use std::process::Command;

use termcore::{capture, err, prefix, sanitize};

use crate::freeze;

pub fn prefix_dir() -> PathBuf {
    prefix().unwrap_or_else(|| PathBuf::from("/"))
}

pub fn apt_dir() -> PathBuf {
    prefix_dir().join("etc/apt")
}

pub fn list_dir() -> PathBuf {
    apt_dir().join("sources.list.d")
}

pub fn installed_version(pkg: &str) -> Option<String> {
    let text = capture("dpkg-query", &["--showformat=${db:Status-Abbrev}\t${Version}", "-W", pkg])?;
    let (status, version) = text.split_once('\t')?;
    if status.starts_with("ii") {
        Some(version.trim().to_string())
    } else {
        None
    }
}

pub fn is_installed(pkg: &str) -> bool {
    installed_version(pkg).is_some()
}

pub fn first_record(pkg: &str) -> Option<String> {
    let out = capture("apt-cache", &["show", pkg])?;
    let record = out.split("\n\n").next()?.trim().to_string();
    if record.is_empty() {
        None
    } else {
        Some(record)
    }
}

pub fn record_field(record: &str, key: &str) -> Option<String> {
    let prefix = format!("{}: ", key);
    record
        .lines()
        .find_map(|line| line.strip_prefix(prefix.as_str()).map(|v| v.trim().to_string()))
}

fn description(record: &str) -> String {
    let mut out: Vec<String> = Vec::new();
    let mut inside = false;
    for line in record.lines() {
        if let Some(rest) = line.strip_prefix("Description: ") {
            inside = true;
            out.push(rest.to_string());
        } else if inside && line.starts_with(' ') {
            let text = &line[1..];
            out.push(if text == "." { String::new() } else { text.to_string() });
        } else {
            inside = false;
        }
    }
    out.join("\n")
}

fn row(out: &mut String, label: &str, value: &str) {
    out.push_str(&format!("{:<12} {}\n", label, value));
}

pub fn label_for_url(url: &str) -> String {
    let main = apt_dir().join("sources.list");
    if fs::read_to_string(&main).map(|t| t.contains(url)).unwrap_or(false) {
        return "tp".to_string();
    }
    if let Ok(read) = fs::read_dir(list_dir()) {
        let mut files: Vec<PathBuf> = read.flatten().map(|e| e.path()).collect();
        files.sort();
        for path in files {
            let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("").to_string();
            if !(name.ends_with(".list") || name.ends_with(".list.disabled")) {
                continue;
            }
            let label = name.trim_end_matches(".disabled").trim_end_matches(".list").to_string();
            if fs::read_to_string(&path).map(|t| t.contains(url)).unwrap_or(false) {
                return label;
            }
        }
    }
    "unknown".to_string()
}

pub fn details_text(pkg: &str) -> Result<String, String> {
    let installed = installed_version(pkg);
    let record = match first_record(pkg) {
        Some(r) => r,
        None => {
            if let Some(version) = installed {
                let mut out = String::new();
                row(&mut out, "Name:", pkg);
                row(&mut out, "Status:", &format!("installed ({}), not available in any repository", version));
                return Ok(out);
            }
            return Err(format!(
                "No information about {}.\nRefresh the package lists with: tn gp:update\n",
                pkg
            ));
        }
    };

    let candidate = record_field(&record, "Version").unwrap_or_default();
    let mut status = "not installed".to_string();
    if let Some(version) = &installed {
        status = "installed".to_string();
        if *version != candidate {
            status = format!("installed, update available ({})", candidate);
        }
    }
    if freeze::is_frozen(pkg) {
        status.push_str(", frozen");
    }

    let mut out = String::new();
    row(&mut out, "Name:", pkg);
    row(&mut out, "Status:", &status);
    row(&mut out, "Version:", &candidate);
    if let Some(version) = &installed {
        row(&mut out, "Installed:", version);
    }
    if let Some(size) = record_field(&record, "Installed-Size") {
        row(&mut out, "Size:", &format!("{} KiB installed", size));
    }
    for (label, key) in [("Section:", "Section"), ("Maintainer:", "Maintainer"), ("Homepage:", "Homepage"), ("Depends:", "Depends")] {
        if let Some(value) = record_field(&record, key) {
            row(&mut out, label, &value);
        }
    }
    out.push('\n');
    out.push_str(&description(&record));
    out.push('\n');
    Ok(out)
}

fn find_url(line: &str) -> Option<String> {
    let start = line.find("https://").or_else(|| line.find("http://"))?;
    Some(line[start..].split_whitespace().next()?.to_string())
}

pub fn source_text(pkg: &str) -> String {
    let policy = capture("apt-cache", &["policy", pkg]).unwrap_or_default();
    if !policy.contains("Candidate:") {
        return format!("Unknown package: {}\n", pkg);
    }
    let mut out = String::new();
    for line in policy.lines() {
        match find_url(line) {
            Some(url) => out.push_str(&format!("{}  [{}]\n", line, label_for_url(&url))),
            None => {
                out.push_str(line);
                out.push('\n');
            }
        }
    }
    out
}

pub fn readme_text(pkg: &str) -> String {
    let mut found: Option<PathBuf> = None;
    if let Ok(read) = fs::read_dir(prefix_dir().join("share/doc").join(pkg)) {
        let mut files: Vec<PathBuf> = read.flatten().map(|e| e.path()).collect();
        files.sort();
        found = files.into_iter().find(|p| {
            p.is_file()
                && p.file_name()
                    .and_then(|n| n.to_str())
                    .map(|n| n.to_lowercase().starts_with("readme"))
                    .unwrap_or(false)
        });
    }
    if found.is_none() && is_installed(pkg) {
        if let Some(list) = capture("dpkg", &["-L", pkg]) {
            found = list
                .lines()
                .find(|l| {
                    l.rsplit('/').next().map(|n| n.to_lowercase().starts_with("readme")).unwrap_or(false)
                })
                .map(PathBuf::from)
                .filter(|p| p.is_file());
        }
    }

    if let Some(path) = found {
        let shown = path
            .strip_prefix(prefix_dir())
            .map(|p| p.display().to_string())
            .unwrap_or_else(|_| path.display().to_string());
        let body = if path.extension().and_then(|e| e.to_str()) == Some("gz") {
            let name = path.to_string_lossy().into_owned();
            capture("gzip", &["-dc", name.as_str()]).unwrap_or_default()
        } else {
            let mut data = fs::read(&path).unwrap_or_default();
            data.truncate(60000);
            String::from_utf8_lossy(&data).into_owned()
        };
        return format!("{}\n\n{}", shown, body);
    }

    let mut out = if is_installed(pkg) {
        "This package does not ship a README.\n".to_string()
    } else {
        "Install the package to read its README.\n".to_string()
    };
    if let Some(homepage) = first_record(pkg).and_then(|r| record_field(&r, "Homepage")) {
        out.push_str(&format!("\nHomepage: {}\n", homepage));
    }
    out
}

pub fn versions_text(pkg: &str) -> String {
    let installed = installed_version(pkg);
    let madison = capture("apt-cache", &["madison", pkg]).unwrap_or_default();
    if madison.trim().is_empty() && installed.is_none() {
        return format!("No version information for {}.\n", pkg);
    }
    let mut out = "* = installed\n\n".to_string();
    let mut found = false;
    for line in madison.lines() {
        let parts: Vec<&str> = line.splitn(3, '|').collect();
        if parts.len() < 3 {
            continue;
        }
        let version = parts[1].trim();
        let source = parts[2].trim();
        let url = source.split_whitespace().next().unwrap_or("");
        let mark = if installed.as_deref() == Some(version) {
            found = true;
            "*"
        } else {
            " "
        };
        out.push_str(&format!("{} {}  [{}]\n", mark, version, label_for_url(url)));
    }
    if let Some(version) = &installed {
        if !found {
            out.push_str(&format!("* {}  [installed, not in any repository]\n", version));
        }
    }
    out
}

// ------------------------------------------------------------------ actions

pub fn run_apt(args: &[&str]) -> i32 {
    match Command::new("apt").args(args).status() {
        Ok(status) => status.code().unwrap_or(1),
        Err(_) => {
            err("apt was not found. Run: tn doctor");
            1
        }
    }
}

fn with_names<'a>(first: &'a str, names: &'a [String]) -> Vec<&'a str> {
    let mut args = vec![first];
    args.extend(names.iter().map(String::as_str));
    args
}

pub fn install(names: &[String]) -> i32 {
    let code = run_apt(&with_names("install", names));
    if code != 0 {
        err("the installation failed; if a package was not found, refresh the lists with: tn gp:update");
    }
    code
}

pub fn remove(names: &[String], purge: bool) -> i32 {
    for name in names {
        if freeze::is_frozen(name) {
            err(&format!("{} is frozen; run tn {}:unfreeze first", name, name));
            return 1;
        }
    }
    run_apt(&with_names(if purge { "purge" } else { "remove" }, names))
}

pub fn upgrade(names: &[String]) -> i32 {
    for name in names {
        if !is_installed(name) {
            err(&format!("{} is not installed (install it with: tn {}:install)", name, name));
            return 1;
        }
        if freeze::is_frozen(name) {
            err(&format!("{} is frozen; run tn {}:unfreeze first", name, name));
            return 1;
        }
    }
    let mut args = vec!["install", "--only-upgrade"];
    args.extend(names.iter().map(String::as_str));
    run_apt(&args)
}

pub fn details(names: &[String]) -> i32 {
    let mut code = 0;
    for (i, name) in names.iter().enumerate() {
        if i > 0 {
            println!();
        }
        match details_text(name) {
            Ok(text) => print!("{}", sanitize(&text)),
            Err(text) => {
                print!("{}", sanitize(&text));
                code = 1;
            }
        }
    }
    code
}

pub fn files(names: &[String]) -> i32 {
    let mut code = 0;
    for name in names {
        match Command::new("dpkg").args(["-L", name.as_str()]).status() {
            Ok(status) if status.success() => {}
            _ => code = 1,
        }
    }
    code
}

pub fn search(terms: &[String]) -> i32 {
    let mut args = vec!["search"];
    args.extend(terms.iter().map(String::as_str));
    match capture("apt-cache", &args) {
        Some(out) => {
            print!("{}", sanitize(&out));
            0
        }
        None => {
            err("apt-cache was not found. Run: tn doctor");
            1
        }
    }
}

pub fn global(action: &str) -> i32 {
    match action {
        "update" => run_apt(&["update"]),
        "upgrade" => {
            if run_apt(&["update"]) != 0 {
                err("unable to refresh the package lists");
                return 1;
            }
            run_apt(&["upgrade"])
        }
        "list" => run_apt(&["list", "--installed"]),
        "clean" => run_apt(&["clean"]),
        "autoremove" => run_apt(&["autoremove"]),
        other => {
            err(&format!("unknown action for gp: {} (update, upgrade, list, clean, autoremove)", other));
            1
        }
    }
}
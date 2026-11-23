use std::collections::BTreeSet;
use std::fs;
use std::path::PathBuf;

use termcore::{home, osc, var};

const USAGE: &str = "term-theme - list and apply color themes

Usage:
  term-theme list         List the available themes
  term-theme show NAME    Preview a theme
  term-theme set NAME     Apply a theme to every session
  term-theme reset        Go back to the default colors
  term-theme current      Show the theme that is in use

Your own themes go in ~/.term/themes/NAME.properties. A theme is a list of
foreground, background, cursor and color0 to color15 entries, for example:
  background=#1A1B26
  color1=#F7768E
";

fn system_dir() -> PathBuf {
    PathBuf::from(var("TERM_SYSTEM_DIR").unwrap_or_else(|| "/nonexistent".to_string())).join("themes")
}

fn user_dir() -> PathBuf {
    home().join(".term").join("themes")
}

fn target() -> PathBuf {
    home().join(".term").join("colors.properties")
}

fn theme_file(name: &str) -> Result<PathBuf, String> {
    let valid = !name.is_empty()
        && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '_' || c == '-');
    if !valid {
        return Err(format!("invalid theme name: {}", name));
    }
    for dir in [user_dir(), system_dir()] {
        let file = dir.join(format!("{}.properties", name));
        if file.is_file() {
            return Ok(file);
        }
    }
    Err(format!("no such theme: {} (try: term-theme list)", name))
}

fn value_of(content: &str, key: &str) -> Option<String> {
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

fn rgb(value: &str) -> Option<(u8, u8, u8)> {
    let hex = value.trim().trim_start_matches('#');
    if hex.len() != 6 || !hex.chars().all(|c| c.is_ascii_hexdigit()) {
        return None;
    }
    Some((
        u8::from_str_radix(&hex[0..2], 16).ok()?,
        u8::from_str_radix(&hex[2..4], 16).ok()?,
        u8::from_str_radix(&hex[4..6], 16).ok()?,
    ))
}

fn preview(content: &str) {
    let bg = value_of(content, "background").and_then(|v| rgb(&v)).unwrap_or((0, 0, 0));
    let fg = value_of(content, "foreground").and_then(|v| rgb(&v)).unwrap_or((229, 229, 229));
    println!(
        "\x1b[48;2;{};{};{}m\x1b[38;2;{};{};{}m {:<28} \x1b[0m",
        bg.0, bg.1, bg.2, fg.0, fg.1, fg.2, "The quick brown fox jumps"
    );
    for row in [0usize, 8] {
        let mut line = String::new();
        for i in 0..8 {
            match value_of(content, &format!("color{}", row + i)).and_then(|v| rgb(&v)) {
                Some((r, g, b)) => line.push_str(&format!("\x1b[48;2;{};{};{}m   \x1b[0m", r, g, b)),
                None => line.push_str("   "),
            }
        }
        println!("{}", line);
    }
}

fn list() {
    let mut names = BTreeSet::new();
    for (dir, custom) in [(system_dir(), false), (user_dir(), true)] {
        if let Ok(read) = fs::read_dir(dir) {
            for entry in read.flatten() {
                let file = entry.file_name().to_string_lossy().into_owned();
                if let Some(stem) = file.strip_suffix(".properties") {
                    names.insert(if custom { format!("{} (custom)", stem) } else { stem.to_string() });
                }
            }
        }
    }
    for name in names {
        println!("{}", name);
    }
}

fn current() {
    let target = match fs::read(target()) {
        Ok(data) => data,
        Err(_) => {
            println!("default (no colors.properties)");
            return;
        }
    };
    for dir in [user_dir(), system_dir()] {
        if let Ok(read) = fs::read_dir(dir) {
            let mut files: Vec<PathBuf> = read.flatten().map(|e| e.path()).collect();
            files.sort();
            for file in files {
                if file.extension().and_then(|e| e.to_str()) == Some("properties")
                    && fs::read(&file).map(|d| d == target).unwrap_or(false)
                {
                    println!("{}", file.file_stem().and_then(|s| s.to_str()).unwrap_or("?"));
                    return;
                }
            }
        }
    }
    println!("custom (colors.properties)");
}

pub fn run(args: Vec<String>) -> i32 {
    match args.first().map(String::as_str) {
        Some("list") => {
            list();
            0
        }
        Some("show") if args.len() == 2 => match theme_file(&args[1]) {
            Ok(file) => {
                preview(&fs::read_to_string(file).unwrap_or_default());
                0
            }
            Err(message) => {
                eprintln!("term-theme: {}", message);
                1
            }
        },
        Some("set") if args.len() == 2 => {
            let file = match theme_file(&args[1]) {
                Ok(f) => f,
                Err(message) => {
                    eprintln!("term-theme: {}", message);
                    return 1;
                }
            };
            let content = fs::read_to_string(&file).unwrap_or_default();
            let has_color = content.lines().any(|l| {
                let l = l.trim_start();
                l.starts_with("foreground") || l.starts_with("background") || l.starts_with("cursor") || l.starts_with("color")
            });
            if !has_color {
                eprintln!("term-theme: {} does not contain any color", file.display());
                return 1;
            }
            let dest = target();
            if let Some(parent) = dest.parent() {
                let _ = fs::create_dir_all(parent);
            }
            if fs::write(&dest, content).is_err() {
                eprintln!("term-theme: unable to write {}", dest.display());
                return 1;
            }
            osc("theme", &[]);
            println!("Theme applied: {}", args[1]);
            0
        }
        Some("reset") => {
            let _ = fs::remove_file(target());
            osc("theme", &[]);
            println!("Default colors restored");
            0
        }
        Some("current") => {
            current();
            0
        }
        Some("-h") | Some("--help") | Some("help") => {
            print!("{}", USAGE);
            0
        }
        _ => {
            eprint!("{}", USAGE);
            2
        }
    }
}
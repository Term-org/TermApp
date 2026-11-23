use std::fs;
use std::io::{self, Read, Write};
use std::path::PathBuf;

use termcore::{is_tty, osc, tmpdir, token, var, wait_until};

const USAGE: &str = "term-clip - read and write the Term clipboard

Usage:
  term-clip set [TEXT...]   Copy TEXT, or the standard input when no text is given
  term-clip get [N]         Print entry N (1 = newest, the default)
  term-clip list            List the stored entries
  term-clip clear           Remove all entries

Examples:
  git diff | term-clip set
  term-clip get | wc -l
";

fn history_dir() -> Option<PathBuf> {
    var("DOCS").map(|d| PathBuf::from(d).join(".term_clipboard_history"))
}

// Newest first.
fn entries(dir: &PathBuf) -> Vec<PathBuf> {
    let mut found: Vec<PathBuf> = match fs::read_dir(dir) {
        Ok(read) => read
            .flatten()
            .map(|e| e.path())
            .filter(|p| p.extension().and_then(|e| e.to_str()) == Some("txt"))
            .collect(),
        Err(_) => Vec::new(),
    };
    found.sort();
    found.reverse();
    found
}

fn set(rest: &[String]) -> i32 {
    let text: Vec<u8> = if !rest.is_empty() {
        rest.join(" ").into_bytes()
    } else if is_tty(0) {
        eprintln!("term-clip: give the text as arguments or pipe it into the command");
        return 2;
    } else {
        let mut data = Vec::new();
        if io::stdin().read_to_end(&mut data).is_err() {
            return 1;
        }
        data
    };
    if text.is_empty() {
        eprintln!("term-clip: nothing to copy");
        return 1;
    }

    let path = tmpdir().join(format!("term-clip.{}", token()));
    if fs::write(&path, &text).is_err() {
        eprintln!("term-clip: unable to write {}", path.display());
        return 1;
    }
    let shown = path.to_string_lossy().into_owned();
    osc("clip", &[("action", "set"), ("path", shown.as_str())]);

    // The app deletes the file once it has copied the text.
    if wait_until(10, || !path.exists()) {
        0
    } else {
        let _ = fs::remove_file(&path);
        eprintln!("term-clip: the app did not respond");
        1
    }
}

pub fn run(args: Vec<String>) -> i32 {
    let action = args.first().map(String::as_str).unwrap_or("");
    let rest: &[String] = if args.is_empty() { &[] } else { &args[1..] };

    match action {
        "set" => set(rest),
        "get" => {
            let n: usize = match rest.first() {
                None => 1,
                Some(v) => match v.parse() {
                    Ok(n) if n > 0 => n,
                    _ => {
                        eprintln!("term-clip: N must be a positive number");
                        return 2;
                    }
                },
            };
            let dir = match history_dir() {
                Some(d) => d,
                None => {
                    eprintln!("term-clip: DOCS is not set");
                    return 1;
                }
            };
            match entries(&dir).get(n - 1) {
                Some(file) => match fs::read(file) {
                    Ok(data) => {
                        let _ = io::stdout().write_all(&data);
                        0
                    }
                    Err(_) => 1,
                },
                None => {
                    eprintln!("term-clip: there is no entry {}", n);
                    1
                }
            }
        }
        "list" => {
            let dir = match history_dir() {
                Some(d) => d,
                None => {
                    eprintln!("term-clip: DOCS is not set");
                    return 1;
                }
            };
            let all = entries(&dir);
            if all.is_empty() {
                println!("The Term clipboard is empty.");
                return 0;
            }
            for (i, file) in all.iter().enumerate() {
                let data = fs::read(file).unwrap_or_default();
                let snippet: String = String::from_utf8_lossy(&data[..data.len().min(60)])
                    .chars()
                    .map(|c| if c == '\n' || c == '\r' || c == '\t' { ' ' } else { c })
                    .collect();
                println!("{:>2}  {:>9} bytes  {}", i + 1, data.len(), snippet);
            }
            0
        }
        "clear" => {
            let dir = match history_dir() {
                Some(d) => d,
                None => {
                    eprintln!("term-clip: DOCS is not set");
                    return 1;
                }
            };
            osc("clip", &[("action", "clear")]);
            if wait_until(5, || entries(&dir).is_empty()) {
                println!("Cleared.");
                0
            } else {
                eprintln!("term-clip: the app did not respond");
                1
            }
        }
        "-h" | "--help" | "help" => {
            print!("{}", USAGE);
            0
        }
        _ => {
            eprint!("{}", USAGE);
            2
        }
    }
}
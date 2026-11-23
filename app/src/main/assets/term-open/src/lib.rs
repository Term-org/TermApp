use std::fs;

use termcore::osc;

const USAGE: &str = "term-open - open a link or a file with another app

Usage: term-open [-t MIME] TARGET

TARGET is a link (http, https, mailto, tel or geo) or a file inside the app
storage or the shared storage. The app asks for confirmation before opening it.
";

pub fn run(args: Vec<String>) -> i32 {
    let mut mime = String::new();
    let mut rest: Vec<String> = Vec::new();
    let mut i = 0;
    while i < args.len() {
        let arg = args[i].clone();
        match arg.as_str() {
            "-h" | "--help" => {
                print!("{}", USAGE);
                return 0;
            }
            "-t" | "--type" => match args.get(i + 1) {
                Some(value) if !value.is_empty() => {
                    mime = value.clone();
                    i += 2;
                    continue;
                }
                _ => {
                    eprintln!("term-open: -t needs a MIME type");
                    return 2;
                }
            },
            "--" => {
                rest.extend(args[i + 1..].iter().cloned());
                break;
            }
            s if s.starts_with('-') && s.len() > 1 => {
                eprintln!("term-open: unknown option: {}", s);
                return 2;
            }
            _ => rest.push(arg.clone()),
        }
        i += 1;
    }

    if rest.len() != 1 {
        eprint!("{}", USAGE);
        return 2;
    }
    let target = rest[0].clone();
    let lower = target.to_lowercase();
    let is_link = ["http://", "https://", "mailto:", "tel:", "geo:"].iter().any(|p| lower.starts_with(p));

    let resolved: String;
    if is_link {
        resolved = target;
    } else if target.contains("://") {
        eprintln!("term-open: unsupported link type: {}", target);
        return 2;
    } else {
        match fs::canonicalize(&target) {
            Ok(path) if path.is_file() => resolved = path.to_string_lossy().into_owned(),
            Ok(_) => {
                eprintln!("term-open: not a regular file: {}", target);
                return 1;
            }
            Err(_) => {
                eprintln!("term-open: no such file: {}", target);
                return 1;
            }
        }
    }

    osc("open", &[("target", resolved.as_str()), ("type", mime.as_str())]);
    println!("Asked the app to open: {} (confirm on the screen)", resolved);
    0
}
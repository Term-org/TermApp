use std::fs;
use std::io::{self, Read};

use termcore::{is_tty, osc, tmpdir, token};

const USAGE: &str = "term-share - share text or a file with another app

Usage:
  term-share [-t TITLE] [-m MIME] FILE
  command | term-share [-t TITLE]

With a FILE inside the app storage or the shared storage, the file is shared.
Without one, the text from the standard input is shared. The app asks for
confirmation before the share sheet opens.
";

pub fn run(args: Vec<String>) -> i32 {
    let mut title = String::new();
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
            "-t" | "--title" => match args.get(i + 1) {
                Some(value) if !value.is_empty() => {
                    title = value.clone();
                    i += 2;
                    continue;
                }
                _ => {
                    eprintln!("term-share: -t needs a title");
                    return 2;
                }
            },
            "-m" | "--mime" => match args.get(i + 1) {
                Some(value) if !value.is_empty() => {
                    mime = value.clone();
                    i += 2;
                    continue;
                }
                _ => {
                    eprintln!("term-share: -m needs a MIME type");
                    return 2;
                }
            },
            s if s.starts_with('-') && s.len() > 1 => {
                eprintln!("term-share: unknown option: {}", s);
                return 2;
            }
            _ => rest.push(arg.clone()),
        }
        i += 1;
    }
    if rest.len() > 1 {
        eprint!("{}", USAGE);
        return 2;
    }

    let kind: &str;
    let path: String;
    if let Some(file) = rest.first() {
        match fs::canonicalize(file) {
            Ok(p) if p.is_file() => path = p.to_string_lossy().into_owned(),
            _ => {
                eprintln!("term-share: not a regular file: {}", file);
                return 1;
            }
        }
        kind = "file";
    } else {
        if is_tty(0) {
            eprintln!("term-share: nothing to share; give a file or pipe text into the command");
            return 2;
        }
        let mut data = Vec::new();
        if io::stdin().read_to_end(&mut data).is_err() || data.is_empty() {
            eprintln!("term-share: no text was received");
            return 1;
        }
        let temp = tmpdir().join(format!("term-share.{}", token()));
        if fs::write(&temp, &data).is_err() {
            eprintln!("term-share: unable to write {}", temp.display());
            return 1;
        }
        path = temp.to_string_lossy().into_owned();
        kind = "text";
    }

    osc(
        "share",
        &[
            ("kind", kind),
            ("path", path.as_str()),
            ("title", title.as_str()),
            ("type", mime.as_str()),
        ],
    );
    println!("Asked the app to share it (confirm on the screen)");
    0
}
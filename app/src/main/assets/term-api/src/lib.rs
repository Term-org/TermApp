use std::fs;

use termcore::{osc, tmpdir, token, wait_until};

const USAGE: &str = "term-api - use the Android features of the TermAPI app

Usage:
  term-api status                           Show the connection to TermAPI
  term-api shizuku status                   Show whether TermAPI may use Shizuku
  term-api shizuku authorize                Ask Shizuku to authorize TermAPI
  term-api shizuku exec [-t SECONDS] CMD... Run a command with the shell privileges of Shizuku

TermAPI must be installed and connected (Settings > Connect to TermAPI), and Shizuku
must be running. Quote the command when it contains characters the shell would use:
  term-api shizuku exec 'pm list packages | head -n 5'
";

pub fn run(args: Vec<String>) -> i32 {
    let (op, rest): (&str, &[String]) = match args.first().map(String::as_str) {
        Some("status") => ("status", &args[1..]),
        Some("shizuku") => match args.get(1).map(String::as_str) {
            Some("status") => ("shizuku-status", &args[2..]),
            Some("authorize") => ("shizuku-authorize", &args[2..]),
            Some("exec") => ("shizuku-exec", &args[2..]),
            _ => {
                eprint!("{}", USAGE);
                return 2;
            }
        },
        Some("-h") | Some("--help") | Some("help") => {
            print!("{}", USAGE);
            return 0;
        }
        _ => {
            eprint!("{}", USAGE);
            return 2;
        }
    };

    let mut command = String::new();
    let mut timeout: u64 = 60;
    let mut limit: u64 = 20;
    if op == "shizuku-authorize" {
        limit = 150;
    }
    if op == "shizuku-exec" {
        let mut words = rest;
        if words.first().map(String::as_str) == Some("-t") {
            timeout = match words.get(1).and_then(|v| v.parse::<u64>().ok()) {
                Some(n) => n,
                None => {
                    eprintln!("term-api: -t needs a number of seconds");
                    return 2;
                }
            };
            words = &words[2..];
        }
        if words.is_empty() {
            eprintln!("term-api: give the command to run");
            return 2;
        }
        command = words.join(" ");
        limit = timeout + 40;
    }

    let token = token();
    let base = tmpdir().join(format!("term-api.{}", token));
    let (done, out, errfile, code) = (
        base.with_extension("done"),
        base.with_extension("out"),
        base.with_extension("err"),
        base.with_extension("code"),
    );

    let timeout_text = timeout.to_string();
    osc(
        "api",
        &[
            ("op", op),
            ("token", token.as_str()),
            ("cmd", command.as_str()),
            ("timeout", timeout_text.as_str()),
        ],
    );

    let finished = wait_until(limit, || done.exists());
    if !finished {
        eprintln!("term-api: the app did not answer in time");
        for file in [&out, &errfile, &code, &done] {
            let _ = fs::remove_file(file);
        }
        return 1;
    }

    if let Ok(text) = fs::read_to_string(&out) {
        print!("{}", text);
    }
    if let Ok(text) = fs::read_to_string(&errfile) {
        eprint!("{}", text);
    }
    let exit = fs::read_to_string(&code)
        .ok()
        .and_then(|t| t.trim().parse::<i32>().ok())
        .unwrap_or(1);
    for file in [&out, &errfile, &code, &done] {
        let _ = fs::remove_file(file);
    }
    exit
}
use std::fs;

use termcore::{home, osc, token, wait_until};

const USAGE: &str = "term-shizuku - authorize rish (Shizuku) for this app

Usage:
  term-shizuku [authorize]   Ask Shizuku to authorize this app (default)
  term-shizuku status        Show the state without asking

Shizuku itself must be running. Start it once in the Shizuku app (wireless
debugging or root). After that, rish works:  rish -c id
";

pub fn run(args: Vec<String>) -> i32 {
    let action = match args.first().map(String::as_str) {
        None | Some("authorize") => "authorize",
        Some("status") => "status",
        Some("-h") | Some("--help") | Some("help") => {
            print!("{}", USAGE);
            return 0;
        }
        _ => {
            eprint!("{}", USAGE);
            return 2;
        }
    };

    let state_file = home().join(".term").join("shizuku.state");
    let token = token();
    osc("shizuku", &[("action", action), ("token", token.as_str())]);

    let limit = if action == "authorize" { 120 } else { 5 };
    let mut state = String::new();
    wait_until(limit, || {
        let content = fs::read_to_string(&state_file).unwrap_or_default();
        let mut lines = content.lines();
        let first = lines.next().unwrap_or("");
        let second = lines.next().unwrap_or("");
        if second == token {
            state = first.to_string();
            true
        } else {
            false
        }
    });

    match state.as_str() {
        "READY" => {
            println!("Shizuku is ready and rish is authorized for this app.");
            println!("Try:  rish -c id");
            return 0;
        }
        "NOT_RUNNING" => eprintln!("Shizuku is not running. Open the Shizuku app and start it (wireless debugging or root), then run this command again."),
        "NEEDS_PERMISSION" => eprintln!("Shizuku is running but this app is not authorized yet. Run: term-shizuku authorize"),
        "DENIED" => eprintln!("The authorization was denied. Allow this app in the Shizuku app, under the authorized apps."),
        "UNSUPPORTED" => eprintln!("This version of Shizuku is too old. Update the Shizuku app."),
        _ => eprintln!("term-shizuku: the app did not answer in time"),
    }
    1
}
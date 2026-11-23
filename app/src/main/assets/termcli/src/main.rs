use std::env;
use std::path::Path;
use std::process::exit;

// Keep this list identical to CLI_NAMES in TermScripts.kt.
const COMMANDS: [&str; 11] = [
    "tn",
    "terminal-vnc",
    "term-storage",
    "term-open",
    "term-share",
    "term-notify",
    "term-clip",
    "term-wakelock",
    "term-shizuku",
    "term-api",
    "term-theme",
];

fn dispatch(name: &str, args: Vec<String>) -> Option<i32> {
    Some(match name {
        "tn" => tn::run(args),
        "terminal-vnc" => terminal_vnc::run(args),
        "term-storage" => term_storage::run(args),
        "term-open" => term_open::run(args),
        "term-share" => term_share::run(args),
        "term-notify" => term_notify::run(args),
        "term-clip" => term_clip::run(args),
        "term-wakelock" => term_wakelock::run(args),
        "term-shizuku" => term_shizuku::run(args),
        "term-api" => term_api::run(args),
        "term-theme" => term_theme::run(args),
        _ => return None,
    })
}

fn main() {
    let mut args: Vec<String> = env::args().collect();
    if args.is_empty() {
        exit(2);
    }
    let invoked = Path::new(&args[0])
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("")
        .to_string();
    args.remove(0);

    // Run through a symlink such as term-open.
    if let Some(code) = dispatch(&invoked, args.clone()) {
        exit(code);
    }

    // Run as: termcli <command> [arguments]
    match args.first().map(String::as_str) {
        None | Some("-h") | Some("--help") => {
            eprintln!("termcli - the command line tools of the Term apps\n\nUsage: termcli <command> [arguments]\n\nCommands:");
            for name in COMMANDS {
                eprintln!("  {}", name);
            }
            exit(2);
        }
        Some("--list") => {
            for name in COMMANDS {
                println!("{}", name);
            }
            return;
        }
        Some(_) => {}
    }
    let name = args.remove(0);
    match dispatch(&name, args) {
        Some(code) => exit(code),
        None => {
            eprintln!("termcli: unknown command: {}", name);
            exit(127);
        }
    }
}
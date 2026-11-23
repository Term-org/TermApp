//! tn - the Term package manager.

mod apt;
mod freeze;
mod repo;
mod tui;

use std::sync::atomic::{AtomicBool, Ordering};

use termcore::{err, home, ok, var, which};

pub const VERSION: &str = "2.0.0";

pub(crate) static ASSUME_YES: AtomicBool = AtomicBool::new(false);

const USAGE: &str = r#"tn - Term package manager

  tn                               Open the interface

Packages (separate several names with ; or , and quote the ;):
  tn <package>:install             Install a package
  tn "<a>;<b>:install"             Install several packages
  tn <package>:remove              Uninstall a package
  tn <package>:purge               Uninstall and delete its configuration
  tn <package>:details             Show details
  tn <package>:upgrade             Upgrade a package
  tn <package>:freeze              Freeze a package (its programs stop working)
  tn <package>:unfreeze            Unfreeze it again
  tn <package>:files               List the files of a package
  tn <text>:search                 Search packages

All packages (gp = global packages):
  tn gp:update                     Refresh the package lists
  tn gp:upgrade                    Refresh the lists and upgrade everything
  tn gp:list                       List installed packages
  tn gp:clean                      Delete downloaded package files
  tn gp:autoremove                 Remove packages that are no longer needed

Repositories:
  tn -c add <link> -l <label>      Add a repository (also: tn community add ... -label ...)
        [-k <key url or file>] [-s <suite>] [-m <component>] [-y]
  tn -c list                       List the repositories
  tn -c remove <label>             Remove a repository
  tn <label>:activate              Turn a repository on
  tn <label>:non-active            Turn a repository off
  tn tcp:activate                  Add and turn on the community repository

Other:
  tn doctor                        Check the environment
  tn version                       Show the version
  tn -y ...                        Do not ask for confirmation
"#;

fn legacy_hint() {
    err("that command style was replaced");
    eprintln!("   tn <package>:install      tn \"<a>;<b>:install\"      tn <package>:remove");
    eprintln!("   tn <package>:details      tn <package>:upgrade      tn gp:upgrade");
    eprintln!("   tn -c add <link> -l <label>");
    eprintln!("Run \"tn help\" for everything.");
}

fn valid_pkg(name: &str) -> bool {
    let mut chars = name.chars();
    match chars.next() {
        Some(c) if c.is_ascii_lowercase() || c.is_ascii_digit() => {}
        _ => return false,
    }
    chars.all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '+' || c == '.' || c == '-')
}

fn doctor() -> i32 {
    let mut failed = false;
    println!("tn {} - environment check", VERSION);
    println!("  PREFIX = {}", apt::prefix_dir().display());
    println!("  HOME   = {}", home().display());
    if var("TN_ASCII").as_deref() == Some("1") {
        println!("  Icons  = plain text (put a Nerd Font at ~/.term/term.ttf to get icons)");
    } else {
        println!("  Icons  = Nerd Font");
    }
    println!();
    for tool in ["bash", "apt", "apt-cache", "dpkg", "dpkg-query", "curl", "tar", "gzip"] {
        if which(tool).is_some() {
            println!("  [ ok ] {}", tool);
        } else {
            println!("  [FAIL] {} ({} not found)", tool, tool);
            failed = true;
        }
    }

    if apt::apt_dir().is_dir() {
        println!("  [ ok ] apt configuration directory");
    } else {
        println!("  [FAIL] apt configuration directory ({} missing)", apt::apt_dir().display());
        failed = true;
    }

    let main_active = std::fs::read_to_string(apt::apt_dir().join("sources.list"))
        .map(|t| t.lines().any(|l| !l.trim().is_empty() && !l.trim_start().starts_with('#')))
        .unwrap_or(false);
    let extra_active = std::fs::read_dir(apt::list_dir())
        .map(|r| r.flatten().any(|e| e.path().extension().and_then(|x| x.to_str()) == Some("list")))
        .unwrap_or(false);
    if main_active || extra_active {
        println!("  [ ok ] at least one repository is active");
    } else {
        println!("  [FAIL] no repository is active (see: tn -c list)");
        failed = true;
    }

    println!();
    if failed {
        println!("Some checks failed.");
        1
    } else {
        println!("Everything looks good.");
        0
    }
}

fn run_spec(args: &[String]) -> i32 {
    let joined = args.join(";");
    let colon = match joined.rfind(':') {
        Some(i) => i,
        None => {
            err("no action given; use tn <packages>:<action>, for example tn git:install");
            return 1;
        }
    };
    let spec = joined[..colon].to_string();
    let action = joined[colon + 1..].to_string();
    if spec.is_empty() {
        err("no package given before ':'");
        return 1;
    }
    if action.is_empty() {
        err("no action given after ':'");
        return 1;
    }
    let names: Vec<String> = spec
        .split(|c: char| c == ';' || c == ',' || c == ' ')
        .filter(|s| !s.is_empty())
        .map(String::from)
        .collect();
    if names.is_empty() {
        err("no package given before ':'");
        return 1;
    }

    match action.as_str() {
        "search" => return apt::search(&names),
        "activate" | "non-active" => {
            if names.len() != 1 {
                err("give exactly one repository label");
                return 1;
            }
            return repo::toggle(&names[0], action == "activate");
        }
        _ => {}
    }

    if names.len() == 1 && names[0] == "gp" {
        return apt::global(&action);
    }
    for name in &names {
        if name == "gp" {
            err("gp means all packages and cannot be combined with other names");
            return 1;
        }
        if !valid_pkg(name) {
            err(&format!("invalid package name: {}", name));
            return 1;
        }
    }

    match action.as_str() {
        "install" => apt::install(&names),
        "remove" => apt::remove(&names, false),
        "purge" => apt::remove(&names, true),
        "details" => apt::details(&names),
        "upgrade" => apt::upgrade(&names),
        "files" => apt::files(&names),
        "freeze" | "unfreeze" => {
            let mut failed = 0;
            for name in &names {
                let result = if action == "freeze" { freeze::freeze(name) } else { freeze::unfreeze(name) };
                match result {
                    Ok(message) => ok(&message),
                    Err(message) => {
                        err(&message);
                        failed = 1;
                    }
                }
            }
            failed
        }
        other => {
            err(&format!("unknown action: {} (try: tn help)", other));
            1
        }
    }
}

pub fn run(args: Vec<String>) -> i32 {
    let mut args = args;
    while matches!(args.first().map(String::as_str), Some("-y") | Some("--yes")) {
        args.remove(0);
        ASSUME_YES.store(true, Ordering::Relaxed);
    }

    let first = match args.first() {
        Some(f) => f.clone(),
        None => return tui::run(),
    };
    match first.as_str() {
        "-h" | "--help" | "help" => {
            print!("{}", USAGE);
            return 0;
        }
        "-V" | "--version" | "version" => {
            println!("tn {}", VERSION);
            return 0;
        }
        "doctor" => return doctor(),
        "-c" | "community" => return repo::community(&args[1..]),
        "install" | "add" | "i" | "remove" | "rm" | "purge" | "search" | "show" | "info" | "update"
        | "upgrade" | "list" | "ls" | "files" | "owns" | "clean" | "autoremove" | "repo" => {
            legacy_hint();
            return 2;
        }
        _ => {}
    }
    run_spec(&args)
}
use std::fs;

use termcore::{home, osc, token, wait_until};

const USAGE: &str = "term-storage - give the terminal access to the shared storage of the device

Usage: term-storage

Links these folders into ~/storage once the permission is granted:
  shared      the whole shared storage
  downloads   Download
  dcim        DCIM
  pictures    Pictures
  music       Music
  movies      Movies
  external-N  app folders on removable storage, when present
";

pub fn run(args: Vec<String>) -> i32 {
    match args.first().map(String::as_str) {
        Some("-h") | Some("--help") => {
            print!("{}", USAGE);
            return 0;
        }
        Some(other) => {
            eprintln!("term-storage: unexpected argument: {}", other);
            return 2;
        }
        None => {}
    }

    let token = token();
    let marker = home().join("storage").join(".token");
    osc("storage", &[("token", token.as_str())]);
    println!("Waiting for the storage permission...");

    let granted = wait_until(120, || {
        fs::read_to_string(&marker).map(|t| t.trim() == token).unwrap_or(false)
    });
    if granted {
        println!("Done. The shared folders are linked in ~/storage.");
        0
    } else {
        eprintln!("term-storage: the permission was not granted. Run the command again and allow access.");
        1
    }
}
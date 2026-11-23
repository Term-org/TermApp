use std::net::{TcpStream, ToSocketAddrs};
use std::process::Command;
use std::time::Duration;

use termcore::{home, osc_raw, wait_until, which};

const USAGE: &str = "terminal-vnc - VNC server launcher for the built-in viewer

Usage:
  terminal-vnc [start] [-d NUMBER] [-g WIDTHxHEIGHT]
  terminal-vnc open   [-H HOST] [-p PORT]
  terminal-vnc stop   [-d NUMBER]
  terminal-vnc status [-d NUMBER]

Options:
  -d, --display NUMBER     VNC display number (default 1, port 5900 + NUMBER)
  -g, --geometry SIZE      Desktop size for a new server (default 1280x720)
  -H, --host HOST          Host used by \"open\" (default 127.0.0.1)
  -p, --port PORT          Port used by \"open\" (default 5900 + display)
  -h, --help               Show this help
";

fn port_open(host: &str, port: u16) -> bool {
    match (host, port).to_socket_addrs() {
        Ok(mut addresses) => {
            addresses.any(|a| TcpStream::connect_timeout(&a, Duration::from_secs(1)).is_ok())
        }
        Err(_) => false,
    }
}

fn valid_geometry(text: &str) -> bool {
    match text.split_once('x') {
        Some((w, h)) => {
            !w.is_empty()
                && !h.is_empty()
                && w.chars().all(|c| c.is_ascii_digit())
                && h.chars().all(|c| c.is_ascii_digit())
        }
        None => false,
    }
}

pub fn run(args: Vec<String>) -> i32 {
    let mut command = "start".to_string();
    let mut rest: &[String] = &args;
    if let Some(first) = args.first() {
        match first.as_str() {
            "start" | "open" | "stop" | "status" => {
                command = first.clone();
                rest = &args[1..];
            }
            "-h" | "--help" | "help" => {
                print!("{}", USAGE);
                return 0;
            }
            _ => {}
        }
    }

    let mut display: u32 = 1;
    let mut geometry = "1280x720".to_string();
    let mut host = "127.0.0.1".to_string();
    let mut port: Option<u16> = None;

    let mut i = 0;
    while i < rest.len() {
        let value = rest.get(i + 1).cloned().unwrap_or_default();
        match rest[i].as_str() {
            "-h" | "--help" => {
                print!("{}", USAGE);
                return 0;
            }
            "-d" | "--display" => match value.parse::<u32>() {
                Ok(n) if n <= 60000 => display = n,
                _ => {
                    eprintln!("terminal-vnc: display must be a number");
                    return 2;
                }
            },
            "-g" | "--geometry" => {
                if !valid_geometry(&value) {
                    eprintln!("terminal-vnc: geometry must look like 1280x720");
                    return 2;
                }
                geometry = value;
            }
            "-H" | "--host" => {
                if value.is_empty() {
                    eprintln!("terminal-vnc: missing host");
                    return 2;
                }
                host = value;
            }
            "-p" | "--port" => match value.parse::<u16>() {
                Ok(n) => port = Some(n),
                Err(_) => {
                    eprintln!("terminal-vnc: invalid port");
                    return 2;
                }
            },
            other => {
                eprintln!("terminal-vnc: unknown option: {}", other);
                eprint!("{}", USAGE);
                return 2;
            }
        }
        i += 2;
    }

    let port = match port {
        Some(p) => p,
        None => match u16::try_from(5900 + display) {
            Ok(p) => p,
            Err(_) => {
                eprintln!("terminal-vnc: the display number is too large");
                return 2;
            }
        },
    };

    match command.as_str() {
        "start" => {
            if port_open(&host, port) {
                println!("VNC server is already running on {}:{}", host, port);
            } else {
                if which("vncserver").is_none() {
                    eprintln!("terminal-vnc: vncserver was not found. Install a VNC server (TigerVNC) first.");
                    return 1;
                }
                if !home().join(".vnc").join("passwd").exists() {
                    println!("No VNC password is set yet. Create one now (maximum 8 characters are used):");
                    match Command::new("vncpasswd").status() {
                        Ok(s) if s.success() => {}
                        _ => return 1,
                    }
                }
                let screen = format!(":{}", display);
                let started = Command::new("vncserver")
                    .args([screen.as_str(), "-geometry", geometry.as_str(), "-localhost", "yes", "-SecurityTypes", "VncAuth"])
                    .status();
                if !matches!(started, Ok(s) if s.success()) {
                    return 1;
                }
                if !wait_until(10, || port_open(&host, port)) {
                    eprintln!("terminal-vnc: server did not start listening on {}:{}", host, port);
                    return 1;
                }
            }
            osc_raw(&format!("TermVnc=open;host={};port={}", host, port));
            println!("Opened the built-in viewer for {}:{}", host, port);
            0
        }
        "open" => {
            osc_raw(&format!("TermVnc=open;host={};port={}", host, port));
            0
        }
        "stop" => {
            if which("vncserver").is_some() {
                let screen = format!(":{}", display);
                let _ = Command::new("vncserver").args(["-kill", screen.as_str()]).status();
            } else {
                eprintln!("terminal-vnc: vncserver was not found");
            }
            osc_raw("TermVnc=close");
            0
        }
        _ => {
            if port_open(&host, port) {
                println!("VNC server is running on {}:{}", host, port);
                0
            } else {
                println!("VNC server is not running on {}:{}", host, port);
                1
            }
        }
    }
}
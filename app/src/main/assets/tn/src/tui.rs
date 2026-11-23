use std::collections::{BTreeMap, HashMap, HashSet};
use std::io::{self, Write};
use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};

use termcore::{capture, err, icons, is_tty, sanitize, which};

use crate::{apt, freeze, VERSION};

static RESIZED: AtomicBool = AtomicBool::new(false);

extern "C" fn on_winch(_signal: libc::c_int) {
    RESIZED.store(true, Ordering::Relaxed);
}

const TAB_NAMES: [&str; 4] = ["Details", "Source", "Readme", "Versions"];

struct Pkg {
    name: String,
    cand: String,
    inst: String,
    upg: bool,
    desc: String,
}

// ------------------------------------------------------------------ terminal

fn out(text: &str) {
    let mut stdout = io::stdout();
    let _ = stdout.write_all(text.as_bytes());
    let _ = stdout.flush();
}

struct Screen {
    saved: libc::termios,
}

impl Screen {
    fn new() -> Option<Screen> {
        let mut t: libc::termios = unsafe { std::mem::zeroed() };
        if unsafe { libc::tcgetattr(0, &mut t) } != 0 {
            return None;
        }
        Some(Screen { saved: t })
    }

    fn raw(&self) {
        let mut t = self.saved;
        t.c_lflag &= !(libc::ICANON | libc::ECHO | libc::ISIG | libc::IEXTEN);
        t.c_iflag &= !(libc::IXON | libc::ICRNL | libc::INLCR | libc::IGNCR);
        t.c_cc[libc::VMIN] = 1;
        t.c_cc[libc::VTIME] = 0;
        unsafe {
            libc::tcsetattr(0, libc::TCSANOW, &t);
        }
    }

    fn cooked(&self) {
        unsafe {
            libc::tcsetattr(0, libc::TCSANOW, &self.saved);
        }
    }

    fn enter(&self) {
        self.raw();
        out("\x1b[?1049h\x1b[2J\x1b[?25l");
    }

    fn leave(&self) {
        out("\x1b[0m\x1b[?25h\x1b[?1049l");
        self.cooked();
    }
}

impl Drop for Screen {
    fn drop(&mut self) {
        self.leave();
    }
}

fn terminal_size() -> (usize, usize) {
    let mut ws: libc::winsize = unsafe { std::mem::zeroed() };
    let done = unsafe { libc::ioctl(1, libc::TIOCGWINSZ, &mut ws) } == 0;
    if done && ws.ws_row >= 8 && ws.ws_col >= 40 {
        (ws.ws_row as usize, ws.ws_col as usize)
    } else {
        (24, 80)
    }
}

// ------------------------------------------------------------------ keys

enum Byte {
    Value(u8),
    Timeout,
    Eof,
}

fn read_byte(ms: i32) -> Byte {
    let mut fds = libc::pollfd { fd: 0, events: libc::POLLIN, revents: 0 };
    let ready = unsafe { libc::poll(&mut fds, 1, ms) };
    if ready <= 0 {
        return Byte::Timeout;
    }
    let mut buffer = [0u8; 1];
    let n = unsafe { libc::read(0, buffer.as_mut_ptr() as *mut libc::c_void, 1) };
    match n {
        1 => Byte::Value(buffer[0]),
        0 => Byte::Eof,
        _ => Byte::Timeout,
    }
}

enum Key {
    Char(char),
    Up,
    Down,
    Left,
    Right,
    PgUp,
    PgDn,
    Home,
    End,
    Esc,
    Enter,
    Backspace,
    Interrupt,
    Other,
    Tick,
    Eof,
}

fn escape() -> Key {
    let first = match read_byte(40) {
        Byte::Value(b) => b,
        _ => return Key::Esc,
    };
    if first != b'[' && first != b'O' {
        return Key::Other;
    }
    let second = match read_byte(40) {
        Byte::Value(b) => b,
        _ => return Key::Other,
    };
    match second {
        b'A' => Key::Up,
        b'B' => Key::Down,
        b'C' => Key::Right,
        b'D' => Key::Left,
        b'H' => Key::Home,
        b'F' => Key::End,
        b'1' | b'4' | b'5' | b'6' | b'7' | b'8' => {
            let _ = read_byte(40);
            match second {
                b'5' => Key::PgUp,
                b'6' => Key::PgDn,
                b'1' | b'7' => Key::Home,
                _ => Key::End,
            }
        }
        _ => Key::Other,
    }
}

fn read_key() -> Key {
    let byte = match read_byte(1000) {
        Byte::Value(b) => b,
        Byte::Timeout => return Key::Tick,
        Byte::Eof => return Key::Eof,
    };
    match byte {
        0x1b => escape(),
        b'\r' | b'\n' => Key::Enter,
        0x7f | 0x08 => Key::Backspace,
        0x03 => Key::Interrupt,
        0x20..=0x7e => Key::Char(byte as char),
        _ => Key::Other,
    }
}

fn prompt(label: &str, rows: usize) -> Option<String> {
    let mut text = String::new();
    loop {
        out(&format!("\x1b[{};1H\x1b[2K\x1b[?25h{}{}", rows, label, text));
        match read_key() {
            Key::Enter => {
                out("\x1b[?25l");
                return Some(text);
            }
            Key::Esc | Key::Interrupt | Key::Eof => {
                out("\x1b[?25l");
                return None;
            }
            Key::Backspace => {
                text.pop();
            }
            Key::Char(c) => text.push(c),
            _ => {}
        }
    }
}

// ------------------------------------------------------------------ data

fn load() -> Vec<Pkg> {
    let mut installed: HashMap<String, String> = HashMap::new();
    if let Some(text) = capture(
        "dpkg-query",
        &["--showformat=${Package}\t${Version}\t${db:Status-Abbrev}\n", "-W"],
    ) {
        for line in text.lines() {
            let parts: Vec<&str> = line.split('\t').collect();
            if parts.len() == 3 && parts[2].starts_with("ii") {
                installed.insert(parts[0].to_string(), parts[1].to_string());
            }
        }
    }

    let mut upgradable: HashSet<String> = HashSet::new();
    if let Some(text) = capture("apt", &["list", "--upgradable"]) {
        for line in text.lines() {
            if line.contains("[upgradable") {
                if let Some(name) = line.split('/').next() {
                    upgradable.insert(name.to_string());
                }
            }
        }
    }

    let mut map: BTreeMap<String, Pkg> = BTreeMap::new();
    if let Some(text) = capture("apt-cache", &["dumpavail"]) {
        for record in text.split("\n\n") {
            let (mut name, mut version, mut desc) = (String::new(), String::new(), String::new());
            for line in record.lines() {
                if let Some(v) = line.strip_prefix("Package: ") {
                    name = v.trim().to_string();
                } else if let Some(v) = line.strip_prefix("Version: ") {
                    version = v.trim().to_string();
                } else if let Some(v) = line.strip_prefix("Description: ") {
                    desc = v.trim().to_string();
                }
            }
            if !name.is_empty() && !map.contains_key(&name) {
                map.insert(
                    name.clone(),
                    Pkg {
                        inst: installed.get(&name).cloned().unwrap_or_default(),
                        upg: upgradable.contains(&name),
                        name,
                        cand: version,
                        desc,
                    },
                );
            }
        }
    }
    for (name, version) in &installed {
        map.entry(name.clone()).or_insert_with(|| Pkg {
            name: name.clone(),
            cand: version.clone(),
            inst: version.clone(),
            upg: false,
            desc: "(not in any repository)".to_string(),
        });
    }
    map.into_values().collect()
}

fn wrap(text: &str, width: usize) -> Vec<String> {
    let width = width.max(8);
    let mut lines: Vec<String> = Vec::new();
    for raw in text.lines() {
        let line = raw.replace('\t', "    ");
        if line.chars().count() <= width {
            lines.push(line);
            continue;
        }
        let mut current = String::new();
        for word in line.split(' ') {
            let mut chars: Vec<char> = word.chars().collect();
            let used = current.chars().count();
            if used > 0 && used + 1 + chars.len() > width {
                lines.push(std::mem::take(&mut current));
            }
            while chars.len() > width {
                if !current.is_empty() {
                    lines.push(std::mem::take(&mut current));
                }
                lines.push(chars.drain(..width).collect());
            }
            if !current.is_empty() {
                current.push(' ');
            }
            current.extend(chars);
        }
        lines.push(current);
    }
    lines
}

fn truncate(text: &str, n: usize) -> String {
    text.chars().take(n).collect()
}

fn pad(text: &str, n: usize) -> String {
    let cut = truncate(text, n);
    let len = cut.chars().count();
    format!("{}{}", cut, " ".repeat(n - len))
}

fn right_text(pkg: &str, tab: usize) -> String {
    match tab {
        0 => match apt::details_text(pkg) {
            Ok(t) | Err(t) => t,
        },
        1 => apt::source_text(pkg),
        2 => apt::readme_text(pkg),
        _ => apt::versions_text(pkg),
    }
}

fn row_icon(p: &Pkg) -> (&'static str, &'static str) {
    let ic = icons();
    if freeze::is_frozen(&p.name) {
        (ic.frozen, "\x1b[36m")
    } else if !p.inst.is_empty() && p.upg {
        (ic.upg, "\x1b[33m")
    } else if !p.inst.is_empty() {
        (ic.inst, "\x1b[32m")
    } else {
        (ic.avail, "\x1b[2m")
    }
}

// ------------------------------------------------------------------ state

struct App {
    pkgs: Vec<Pkg>,
    view: Vec<usize>,
    filter: String,
    sel: usize,
    top: usize,
    tab: usize,
    rtop: usize,
    rlines: Vec<String>,
    rkey: String,
    status: String,
    rows: usize,
    cols: usize,
    rw: usize,
    rh: usize,
}

impl App {
    fn new() -> App {
        let (rows, cols) = terminal_size();
        App {
            pkgs: Vec::new(),
            view: Vec::new(),
            filter: String::new(),
            sel: 0,
            top: 0,
            tab: 0,
            rtop: 0,
            rlines: Vec::new(),
            rkey: String::new(),
            status: String::new(),
            rows,
            cols,
            rw: 40,
            rh: 10,
        }
    }

    fn reload(&mut self) {
        self.pkgs = load();
        self.apply_filter();
    }

    fn apply_filter(&mut self) {
        let needle = self.filter.to_lowercase();
        self.view = (0..self.pkgs.len())
            .filter(|&i| {
                needle.is_empty()
                    || self.pkgs[i].name.to_lowercase().contains(&needle)
                    || self.pkgs[i].desc.to_lowercase().contains(&needle)
            })
            .collect();
        self.sel = 0;
        self.top = 0;
        self.rkey.clear();
    }

    fn current(&self) -> Option<&Pkg> {
        self.view.get(self.sel).map(|&i| &self.pkgs[i])
    }

    fn reselect(&mut self, name: Option<String>) {
        if let Some(name) = name {
            if let Some(pos) = self.view.iter().position(|&i| self.pkgs[i].name == name) {
                self.sel = pos;
            }
        }
    }

    fn move_sel(&mut self, delta: isize) {
        let max = self.view.len().saturating_sub(1) as isize;
        self.sel = (self.sel as isize + delta).clamp(0, max) as usize;
    }

    fn scroll(&mut self, delta: isize) {
        let max = self.rlines.len().saturating_sub(self.rh) as isize;
        self.rtop = (self.rtop as isize + delta).clamp(0, max) as usize;
    }

    fn change_tab(&mut self, delta: isize) {
        self.tab = ((self.tab as isize + delta + 4) % 4) as usize;
        self.rtop = 0;
    }

    fn fix_scroll(&mut self, body: usize) {
        if self.sel < self.top {
            self.top = self.sel;
        }
        if self.sel >= self.top + body {
            self.top = self.sel + 1 - body;
        }
    }

    fn ensure_right(&mut self) {
        let name = self.current().map(|p| p.name.clone());
        let key = format!("{}:{}:{}", name.clone().unwrap_or_default(), self.tab, self.rw);
        if key == self.rkey {
            return;
        }
        self.rkey = key;
        self.rtop = 0;
        self.rlines = match name {
            None => vec![
                "No packages to show.".to_string(),
                "Press r to refresh the package lists.".to_string(),
            ],
            Some(name) => wrap(&sanitize(&right_text(&name, self.tab)), self.rw.saturating_sub(2)),
        };
    }

    fn draw(&mut self) {
        let (rows, cols) = (self.rows, self.cols);
        let lw = (cols * 40 / 100).clamp(16, 44);
        let rw = cols.saturating_sub(lw + 1);
        let body = rows.saturating_sub(3).max(1);
        self.rw = rw;
        self.rh = body.saturating_sub(1);
        self.ensure_right();
        self.fix_scroll(body);

        let ic = icons();
        let mut b = String::from("\x1b[?25l");

        let mut header = format!("tn {}  {}/{} packages", VERSION, self.view.len(), self.pkgs.len());
        if !self.filter.is_empty() {
            header.push_str(&format!("  filter: {}", self.filter));
        }
        b.push_str(&format!(
            "\x1b[1;1H\x1b[7m {} {}\x1b[0m",
            ic.pkg,
            pad(&header, cols.saturating_sub(3))
        ));

        let tab_icons = [ic.info, ic.src, ic.doc, ic.ver];
        let mut tabs = String::new();
        for t in 0..4 {
            if t == self.tab {
                tabs.push_str(&format!("\x1b[7m {} {} \x1b[0m", tab_icons[t], TAB_NAMES[t]));
            } else if rw >= 46 {
                tabs.push_str(&format!(" {} {} ", tab_icons[t], TAB_NAMES[t]));
            } else {
                tabs.push_str(&format!(" {} ", tab_icons[t]));
            }
        }

        for r in 0..body {
            b.push_str(&format!("\x1b[{};1H", r + 2));
            let idx = self.top + r;
            if let Some(&i) = self.view.get(idx) {
                let p = &self.pkgs[i];
                let (icon, color) = row_icon(p);
                let text = pad(&format!("{} {}", p.name, p.cand), lw - 2);
                if idx == self.sel {
                    b.push_str(&format!("\x1b[7m{} {}\x1b[0m", icon, text));
                } else {
                    b.push_str(&format!("{}{}\x1b[0m {}", color, icon, text));
                }
            } else {
                b.push_str(&" ".repeat(lw));
            }
            b.push_str(ic.sep);
            if r == 0 {
                b.push_str(&format!(" {}\x1b[K", tabs));
            } else {
                let line = self.rlines.get(self.rtop + r - 1).map(String::as_str).unwrap_or("");
                b.push_str(&format!(" {}\x1b[K", truncate(line, rw.saturating_sub(2))));
            }
        }

        b.push_str(&format!(
            "\x1b[{};1H\x1b[K{}",
            rows - 1,
            truncate(&self.status, cols.saturating_sub(1))
        ));
        let hints = "i:install x:remove U:upgrade f:freeze z:unfreeze /:filter r:refresh h/l:tab ?:help q:quit";
        b.push_str(&format!("\x1b[{};1H\x1b[7m{}\x1b[0m", rows, pad(hints, cols)));
        out(&b);
    }
}

// ------------------------------------------------------------------ actions

fn help() {
    print!(
        "tn - keys\n\n\
         \x20 Up/Down, j/k     Move in the package list\n\
         \x20 g / G            First / last package\n\
         \x20 Left/Right, h/l  Switch tab (Details, Source, Readme, Versions)\n\
         \x20 1 2 3 4          Jump to a tab\n\
         \x20 PgUp/PgDn        Scroll the right pane\n\
         \x20 i                Install the package\n\
         \x20 x                Uninstall the package\n\
         \x20 U                Upgrade the package\n\
         \x20 f                Freeze the package (its programs stop working)\n\
         \x20 z                Unfreeze the package\n\
         \x20 /                Filter the list (empty text clears the filter)\n\
         \x20 Esc              Clear the filter\n\
         \x20 r                Refresh the package lists (apt update)\n\
         \x20 q                Quit\n"
    );
}

/// Leaves the interface, runs the action with the real terminal, then comes back.
fn external<F: FnOnce()>(app: &mut App, screen: &Screen, action: F) {
    let keep = app.current().map(|p| p.name.clone());
    screen.leave();
    action();
    print!("\nPress Enter to return to tn ");
    let _ = io::stdout().flush();
    let mut line = String::new();
    let _ = io::stdin().read_line(&mut line);
    let (rows, cols) = terminal_size();
    app.rows = rows;
    app.cols = cols;
    screen.enter();
    app.reload();
    app.rkey.clear();
    app.reselect(keep);
}

fn install(app: &mut App, screen: &Screen) {
    let name = match app.current() {
        Some(p) => p.name.clone(),
        None => return,
    };
    if apt::is_installed(&name) {
        app.status = format!("{} is already installed", name);
        return;
    }
    external(app, screen, move || {
        let _ = Command::new("apt").args(["install", name.as_str()]).status();
    });
}

fn remove(app: &mut App, screen: &Screen) {
    let name = match app.current() {
        Some(p) => p.name.clone(),
        None => return,
    };
    if !apt::is_installed(&name) {
        app.status = format!("{} is not installed", name);
        return;
    }
    if freeze::is_frozen(&name) {
        app.status = format!("{} is frozen; press z to unfreeze it first", name);
        return;
    }
    external(app, screen, move || {
        let _ = Command::new("apt").args(["remove", name.as_str()]).status();
    });
}

fn upgrade(app: &mut App, screen: &Screen) {
    let name = match app.current() {
        Some(p) => p.name.clone(),
        None => return,
    };
    if !apt::is_installed(&name) {
        app.status = format!("{} is not installed", name);
        return;
    }
    if freeze::is_frozen(&name) {
        app.status = format!("{} is frozen; press z to unfreeze it first", name);
        return;
    }
    external(app, screen, move || {
        let _ = Command::new("apt").args(["install", "--only-upgrade", name.as_str()]).status();
    });
}

fn freeze_action(app: &mut App, do_freeze: bool) {
    let name = match app.current() {
        Some(p) => p.name.clone(),
        None => return,
    };
    let result = if do_freeze { freeze::freeze(&name) } else { freeze::unfreeze(&name) };
    app.status = match result {
        Ok(message) | Err(message) => message,
    };
    app.rkey.clear();
}

/// Returns false when the interface should close.
fn handle(app: &mut App, screen: &Screen, key: Key) -> bool {
    app.status.clear();
    match key {
        Key::Char('q') | Key::Char('Q') | Key::Interrupt | Key::Eof => return false,
        Key::Up | Key::Char('k') => app.move_sel(-1),
        Key::Down | Key::Char('j') => app.move_sel(1),
        Key::Home | Key::Char('g') => app.sel = 0,
        Key::End | Key::Char('G') => app.sel = app.view.len().saturating_sub(1),
        Key::PgUp => app.scroll(-(app.rh as isize)),
        Key::PgDn => app.scroll(app.rh as isize),
        Key::Left | Key::Char('h') => app.change_tab(-1),
        Key::Right | Key::Char('l') => app.change_tab(1),
        Key::Char(c @ '1'..='4') => {
            app.tab = c as usize - '1' as usize;
            app.rtop = 0;
        }
        Key::Char('i') => install(app, screen),
        Key::Char('x') => remove(app, screen),
        Key::Char('U') => upgrade(app, screen),
        Key::Char('f') => freeze_action(app, true),
        Key::Char('z') => freeze_action(app, false),
        Key::Char('r') => external(app, screen, || {
            let _ = Command::new("apt").arg("update").status();
        }),
        Key::Char('/') => {
            if let Some(text) = prompt("filter> ", app.rows) {
                app.filter = text;
                app.apply_filter();
            }
        }
        Key::Char('?') => external(app, screen, help),
        Key::Esc => {
            if !app.filter.is_empty() {
                app.filter.clear();
                app.apply_filter();
            }
        }
        _ => {}
    }
    true
}

pub fn run() -> i32 {
    if !is_tty(0) || !is_tty(1) {
        err("the interface needs a terminal; run tn help for the command syntax");
        return 1;
    }
    for tool in ["apt-cache", "dpkg-query"] {
        if which(tool).is_none() {
            err(&format!("{} was not found. Run: tn doctor", tool));
            return 1;
        }
    }
    println!("Loading packages...");
    let screen = match Screen::new() {
        Some(s) => s,
        None => {
            err("unable to read the terminal settings");
            return 1;
        }
    };
    let mut app = App::new();
    app.reload();
    unsafe {
        libc::signal(libc::SIGWINCH, on_winch as *const () as libc::sighandler_t);
    }
    screen.enter();

    let mut dirty = true;
    loop {
        if RESIZED.swap(false, Ordering::Relaxed) {
            let (rows, cols) = terminal_size();
            app.rows = rows;
            app.cols = cols;
            app.rkey.clear();
            dirty = true;
        }
        if dirty {
            app.draw();
            dirty = false;
        }
        match read_key() {
            Key::Tick => {}
            key => {
                if !handle(&mut app, &screen, key) {
                    break;
                }
                dirty = true;
            }
        }
    }
    0
}
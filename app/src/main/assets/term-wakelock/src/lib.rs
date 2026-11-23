use termcore::osc;

const USAGE: &str = "term-wakelock - keep the CPU and Wi-Fi awake while the screen is off

Usage:
  term-wakelock on     Acquire the wake lock
  term-wakelock off    Release it

The lock also shows as a button in the notification of the app. It uses battery,
and it is released automatically when the last session closes.
";

pub fn run(args: Vec<String>) -> i32 {
    let state = match args.first().map(String::as_str) {
        Some("on") | Some("acquire") => "1",
        Some("off") | Some("release") => "0",
        Some("-h") | Some("--help") | Some("help") => {
            print!("{}", USAGE);
            return 0;
        }
        _ => {
            eprint!("{}", USAGE);
            return 2;
        }
    };
    osc("wakelock", &[("state", state)]);
    if state == "1" {
        println!("Wake lock requested. It is shown in the notification.");
    } else {
        println!("Wake lock released.");
    }
    0
}
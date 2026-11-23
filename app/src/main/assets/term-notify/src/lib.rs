use termcore::osc;

const USAGE: &str = "term-notify - show an Android notification

Usage:
  term-notify [-t TITLE] [-i ID] [--ongoing] [-c CONTENT | CONTENT...]
  term-notify --remove -i ID

Options:
  -t, --title TITLE     Title (default: the name of the app)
  -c, --content TEXT    Text of the notification
  -i, --id ID           A name; showing the same ID again replaces the notification
      --ongoing         The notification cannot be swiped away
      --remove          Remove the notification with the given ID

Example:
  make && term-notify -t Build \"Finished\" || term-notify -t Build \"Failed\"
";

pub fn run(args: Vec<String>) -> i32 {
    let mut title = String::new();
    let mut content = String::new();
    let mut id = String::new();
    let mut ongoing = false;
    let mut remove = false;
    let mut rest: Vec<String> = Vec::new();

    let mut i = 0;
    while i < args.len() {
        let arg = args[i].clone();
        let value = args.get(i + 1).cloned().unwrap_or_default();
        match arg.as_str() {
            "-h" | "--help" => {
                print!("{}", USAGE);
                return 0;
            }
            "-t" | "--title" => {
                title = value;
                i += 2;
                continue;
            }
            "-c" | "--content" => {
                content = value;
                i += 2;
                continue;
            }
            "-i" | "--id" => {
                id = value;
                i += 2;
                continue;
            }
            "--ongoing" => ongoing = true,
            "--remove" => remove = true,
            "--" => {
                rest.extend(args[i + 1..].iter().cloned());
                break;
            }
            s if s.starts_with('-') && s.len() > 1 => {
                eprintln!("term-notify: unknown option: {}", s);
                return 2;
            }
            _ => rest.push(arg.clone()),
        }
        i += 1;
    }
    if !rest.is_empty() {
        content = rest.join(" ");
    }

    if remove {
        if id.is_empty() {
            eprintln!("term-notify: --remove needs an ID (-i)");
            return 2;
        }
    } else if title.is_empty() && content.is_empty() {
        eprint!("{}", USAGE);
        return 2;
    }

    osc(
        "notify",
        &[
            ("title", title.as_str()),
            ("content", content.as_str()),
            ("id", id.as_str()),
            ("ongoing", if ongoing { "1" } else { "" }),
            ("remove", if remove { "1" } else { "" }),
        ],
    );
    0
}
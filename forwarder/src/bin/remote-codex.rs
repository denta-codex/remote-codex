use remote_codex_forwarder::events;
use std::path::PathBuf;
#[tokio::main]
async fn main() {
    let args: Vec<_> = std::env::args().skip(1).collect();
    if args != ["notify", "credential-requests-changed"] {
        eprintln!("Usage: remote-codex notify credential-requests-changed");
        std::process::exit(2);
    }
    let path = match std::env::var_os("REMOTE_CODEX_EVENT_SOCKET") {
        Some(path) => PathBuf::from(path),
        None => match std::env::var_os("XDG_RUNTIME_DIR") {
            Some(root) => PathBuf::from(root).join("remote-codex/events.sock"),
            None => {
                eprintln!("Configure XDG_RUNTIME_DIR or REMOTE_CODEX_EVENT_SOCKET");
                std::process::exit(2);
            }
        },
    };
    if events::publish(&path).await.is_err() {
        eprintln!("Event publication unavailable; no credential operation was performed.");
        std::process::exit(1);
    }
    println!("Event accepted for live notification; phone receipt is not confirmed.");
}

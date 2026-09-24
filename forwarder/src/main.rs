use remote_codex_forwarder::{Config, load_config_from_credential, serve};
use std::io;
use std::net::SocketAddr;
use std::path::Path;
use tokio::net::TcpListener;

#[tokio::main]
async fn main() -> io::Result<()> {
    let address = std::env::var("REMOTE_CODEX_LISTEN")
        .unwrap_or_else(|_| "127.0.0.1:8787".to_owned())
        .parse::<SocketAddr>()
        .map_err(|_| {
            io::Error::new(
                io::ErrorKind::InvalidInput,
                "listener must be a loopback IP",
            )
        })?;
    if !address.ip().is_loopback() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "listener must be a loopback IP",
        ));
    }
    let socket = std::env::var("CODEX_SOCKET").unwrap_or_default();
    let credential_directory = std::env::var("CREDENTIALS_DIRECTORY").unwrap_or_default();
    let config: Config = load_config_from_credential(socket, Path::new(&credential_directory))?;
    let listener = TcpListener::bind(address).await?;
    eprintln!("Remote Codex forwarder listening on loopback");
    serve(listener, config, shutdown_signal()).await
}

async fn shutdown_signal() {
    #[cfg(unix)]
    {
        let mut terminate =
            tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
                .expect("SIGTERM handler");
        tokio::select! {
            _ = tokio::signal::ctrl_c() => {}
            _ = terminate.recv() => {}
        }
    }
    #[cfg(not(unix))]
    {
        let _ = tokio::signal::ctrl_c().await;
    }
}

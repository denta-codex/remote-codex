//! Metadata-only, stateless local publication. This socket never transports secrets.
use crate::{check_peer_uid, current_uid};
use serde::Deserialize;
use std::{
    io,
    os::unix::fs::{DirBuilderExt, FileTypeExt, MetadataExt, PermissionsExt},
    path::{Path, PathBuf},
    time::Duration,
};
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt, BufReader},
    net::{UnixListener, UnixStream},
    sync::watch,
    time::timeout,
};

pub const NOTICE: &str = r#"{"method":"remoteCodex/credentialRequestsChanged","params":{}}"#;
pub const REQUEST: &[u8] = b"{\"version\":1,\"event\":\"credential_requests_changed\"}\n";
const ACCEPTED: &[u8] = b"{\"accepted\":true}\n";
const LIMIT: usize = 1024;
const DEADLINE: Duration = Duration::from_secs(1);

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Publication {
    version: u8,
    event: String,
}

pub struct Listener {
    listener: UnixListener,
    path: PathBuf,
    inode: u64,
}
impl Listener {
    pub async fn bind(path: &Path) -> io::Result<Self> {
        let parent = path
            .parent()
            .filter(|p| path.is_absolute() && !p.as_os_str().is_empty())
            .ok_or_else(|| {
                io::Error::other("event socket requires an absolute private directory")
            })?;
        match std::fs::DirBuilder::new().mode(0o700).create(parent) {
            Ok(()) => {}
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => {}
            Err(e) => return Err(e),
        }
        private_directory(parent)?;
        match std::fs::symlink_metadata(path) {
            Ok(meta) => {
                if !meta.file_type().is_socket()
                    || meta.uid() != current_uid()
                    || meta.mode() & 0o077 != 0
                {
                    return Err(io::Error::other("unsafe event socket"));
                }
                match timeout(DEADLINE, UnixStream::connect(path)).await {
                    Ok(Err(e))
                        if matches!(
                            e.kind(),
                            io::ErrorKind::ConnectionRefused | io::ErrorKind::NotFound
                        ) => {}
                    Ok(Ok(_)) => return Err(io::Error::other("event listener already active")),
                    Ok(Err(error)) => {
                        return Err(io::Error::new(error.kind(), "event listener unavailable"));
                    }
                    Err(_) => {
                        return Err(io::Error::new(
                            io::ErrorKind::TimedOut,
                            "event listener probe timed out",
                        ));
                    }
                }
                let current = std::fs::symlink_metadata(path)?;
                if current.ino() != meta.ino() || current.dev() != meta.dev() {
                    return Err(io::Error::other("event socket changed"));
                }
                std::fs::remove_file(path)?;
            }
            Err(e) if e.kind() == io::ErrorKind::NotFound => {}
            Err(e) => return Err(e),
        }
        let listener = UnixListener::bind(path)?;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))?;
        let inode = std::fs::symlink_metadata(path)?.ino();
        Ok(Self {
            listener,
            path: path.to_owned(),
            inode,
        })
    }
    pub async fn accept(&self) -> io::Result<UnixStream> {
        self.listener.accept().await.map(|(s, _)| s)
    }
}
impl Drop for Listener {
    fn drop(&mut self) {
        if std::fs::symlink_metadata(&self.path).is_ok_and(|m| {
            m.file_type().is_socket() && m.uid() == current_uid() && m.ino() == self.inode
        }) {
            let _ = std::fs::remove_file(&self.path);
        }
    }
}
fn private_directory(path: &Path) -> io::Result<()> {
    let meta = std::fs::symlink_metadata(path)?;
    if !meta.is_dir() || meta.uid() != current_uid() || meta.mode() & 0o077 != 0 {
        return Err(io::Error::other(
            "event directory must be private and owned",
        ));
    }
    Ok(())
}
async fn line(stream: &mut BufReader<UnixStream>) -> io::Result<Vec<u8>> {
    let mut result = Vec::new();
    loop {
        let bytes = stream.fill_buf().await?;
        if bytes.is_empty() {
            return Err(io::Error::other("incomplete event exchange"));
        }
        let count = bytes
            .iter()
            .position(|b| *b == b'\n')
            .map_or(bytes.len(), |i| i + 1);
        if result.len() + count > LIMIT {
            return Err(io::Error::other("event exchange exceeds limit"));
        }
        result.extend_from_slice(&bytes[..count]);
        stream.consume(count);
        if result.last() == Some(&b'\n') {
            return Ok(result);
        }
    }
}
pub async fn receive(stream: UnixStream, signals: watch::Sender<u64>) -> io::Result<()> {
    check_peer_uid(stream.peer_cred()?.uid(), current_uid())?;
    timeout(DEADLINE, async {
        let mut stream = BufReader::new(stream);
        let request: Publication = serde_json::from_slice(&line(&mut stream).await?)
            .map_err(|_| io::Error::other("invalid publication"))?;
        if request.version != 1 || request.event != "credential_requests_changed" {
            return Err(io::Error::other("unsupported publication"));
        }
        // A version change is an invalidation, not a request snapshot or delivery receipt.
        signals.send_modify(|n| *n = n.wrapping_add(1));
        stream.get_mut().write_all(ACCEPTED).await?;
        stream.get_mut().shutdown().await
    })
    .await
    .map_err(|_| io::Error::other("event exchange timed out"))?
}
pub async fn publish(path: &Path) -> io::Result<()> {
    private_directory(
        path.parent()
            .ok_or_else(|| io::Error::other("missing event directory"))?,
    )?;
    let meta = std::fs::symlink_metadata(path)?;
    if !meta.file_type().is_socket() || meta.uid() != current_uid() || meta.mode() & 0o077 != 0 {
        return Err(io::Error::other("unsafe event socket"));
    }
    timeout(DEADLINE, async {
        let socket = UnixStream::connect(path).await?;
        check_peer_uid(socket.peer_cred()?.uid(), current_uid())?;
        let mut stream = BufReader::new(socket);
        stream.get_mut().write_all(REQUEST).await?;
        if line(&mut stream).await? != ACCEPTED {
            return Err(io::Error::other("publication not accepted"));
        }
        Ok(())
    })
    .await
    .map_err(|_| io::Error::other("event exchange timed out"))?
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn stateless_publication_and_stale_socket_recovery() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("events/events.sock");
        let listener = Listener::bind(&path).await.unwrap();
        assert!(Listener::bind(&path).await.is_err());
        // The active-listener probe may have connected: ignore it before our publisher.
        let (signals, mut changes) = watch::channel(0);
        let server = tokio::spawn(async move {
            loop {
                if receive(listener.accept().await.unwrap(), signals.clone())
                    .await
                    .is_ok()
                {
                    break;
                }
            }
        });
        publish(&path).await.unwrap();
        changes.changed().await.unwrap();
        assert_eq!(*changes.borrow(), 1);
        server.await.unwrap();
        assert!(!path.exists());
        for _ in 0..256 {
            let stale = UnixListener::bind(&path).unwrap();
            drop(stale);
            let listener = Listener::bind(&path).await.unwrap();
            assert_eq!(std::fs::metadata(&path).unwrap().mode() & 0o777, 0o600);
            drop(listener);
        }
    }
    #[tokio::test]
    async fn rejects_symlinks_public_directories_and_unexpected_payloads() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("events/events.sock");
        let listener = Listener::bind(&path).await.unwrap();
        let (signals, changes) = watch::channel(0);
        for message in [
            b"{\"version\":1,\"event\":\"other\"}\n".as_slice(),
            b"{\"version\":1,\"event\":\"credential_requests_changed\",\"value\":\"fake\"}\n",
            b"{\"version\":1,\"version\":1,\"event\":\"credential_requests_changed\"}\n",
        ] {
            let mut client = UnixStream::connect(&path).await.unwrap();
            client.write_all(message).await.unwrap();
            assert!(
                receive(listener.accept().await.unwrap(), signals.clone())
                    .await
                    .is_err()
            );
            assert_eq!(*changes.borrow(), 0);
        }
        let mut client = UnixStream::connect(&path).await.unwrap();
        client.write_all(&vec![b'x'; LIMIT + 1]).await.unwrap();
        assert!(
            receive(listener.accept().await.unwrap(), signals)
                .await
                .is_err()
        );
        drop(listener);
        std::os::unix::fs::symlink("missing", &path).unwrap();
        assert!(Listener::bind(&path).await.is_err());
        std::fs::remove_file(&path).unwrap();
        std::fs::set_permissions(
            path.parent().unwrap(),
            std::fs::Permissions::from_mode(0o755),
        )
        .unwrap();
        assert!(Listener::bind(&path).await.is_err());
        assert!(check_peer_uid(current_uid().wrapping_add(1), current_uid()).is_err());
    }
}

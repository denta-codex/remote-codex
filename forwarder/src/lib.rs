use sha2::{Digest, Sha256};
use std::fs::OpenOptions;
use std::future::Future;
use std::io;
use std::os::unix::fs::OpenOptionsExt;
use std::os::unix::fs::{FileTypeExt, MetadataExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::Mutex;
use std::time::{Duration, Instant};
use subtle::ConstantTimeEq;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, UnixStream};
use tokio::sync::Semaphore;
use tokio::task::JoinSet;
use tokio::time::timeout;

const MAX_HEADER_BYTES: usize = 16 * 1024;
const MAX_HEADERS: usize = 64;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
const CLIENT_HEADER_TIMEOUT: Duration = Duration::from_secs(5);
const UPSTREAM_HEADER_TIMEOUT: Duration = Duration::from_secs(10);
const MAX_CONNECTIONS: usize = 8;
const MAX_MANIFEST_BYTES: u64 = 64 * 1024;
const MAX_APK_BYTES: u64 = 256 * 1024 * 1024;
const UPDATE_PREFIX: &str = "/remote-codex/v1/updates/releases/";
const UPDATE_MANIFEST: &str = "/remote-codex/v1/updates/stable/latest.json";

#[derive(Clone)]
pub struct Config {
    socket: PathBuf,
    expected_authorization: [u8; 32],
    update_root: Option<PathBuf>,
}

impl Config {
    pub fn new(socket: impl Into<PathBuf>, token: &str) -> io::Result<Self> {
        if token.len() < 43 || token.chars().any(char::is_whitespace) {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "credential must contain at least 43 non-whitespace characters",
            ));
        }
        let expected_authorization = Sha256::digest(format!("Bearer {token}")).into();
        Ok(Self {
            socket: socket.into(),
            expected_authorization,
            update_root: None,
        })
    }

    pub fn with_update_root(mut self, root: impl Into<PathBuf>) -> io::Result<Self> {
        let root = root.into();
        if !root.is_absolute() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "update root must be absolute",
            ));
        }
        self.update_root = Some(root);
        Ok(self)
    }
}

pub fn load_config_from_credential(
    socket: impl Into<PathBuf>,
    credential_directory: &Path,
) -> io::Result<Config> {
    if !credential_directory.is_absolute() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "systemd credential directory unavailable",
        ));
    }
    let path = credential_directory.join("connection-token");
    let metadata = std::fs::symlink_metadata(&path).map_err(|_| {
        io::Error::new(
            io::ErrorKind::PermissionDenied,
            "systemd connection credential missing or insecure",
        )
    })?;
    if !metadata.file_type().is_file() || metadata.permissions().mode() & 0o077 != 0 {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "systemd connection credential missing or insecure",
        ));
    }
    let raw = std::fs::read_to_string(path).map_err(|_| {
        io::Error::new(
            io::ErrorKind::PermissionDenied,
            "cannot read systemd connection credential",
        )
    })?;
    Config::new(socket, raw.trim())
}

fn current_uid() -> u32 {
    // SAFETY: geteuid has no preconditions and does not access memory.
    unsafe { libc::geteuid() }
}

pub fn check_socket(path: &Path) -> io::Result<PathBuf> {
    resolve_socket_for_uid(path, current_uid())
}

fn resolve_socket_for_uid(path: &Path, uid: u32) -> io::Result<PathBuf> {
    let denied = || {
        io::Error::new(
            io::ErrorKind::PermissionDenied,
            "control socket is not private and owned",
        )
    };
    let alias = std::fs::symlink_metadata(path)?;
    if alias.uid() != uid || !(alias.file_type().is_socket() || alias.file_type().is_symlink()) {
        return Err(denied());
    }
    let resolved = std::fs::canonicalize(path)?;
    let socket = std::fs::symlink_metadata(&resolved)?;
    if !socket.file_type().is_socket() || socket.uid() != uid || socket.mode() & 0o022 != 0 {
        return Err(denied());
    }
    // Validate both ends of the alias. A private directory protects socket replacement;
    // SO_PEERCRED below checks the actual connection after pathname resolution.
    for parent in [path.parent(), resolved.parent()] {
        let parent = parent.ok_or_else(denied)?;
        let metadata = std::fs::metadata(parent)?;
        if !metadata.is_dir() || metadata.uid() != uid || metadata.mode() & 0o077 != 0 {
            return Err(denied());
        }
    }
    Ok(resolved)
}

fn check_peer_uid(actual: u32, expected: u32) -> io::Result<()> {
    if actual != expected {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "control socket peer is not owned",
        ));
    }
    Ok(())
}

pub async fn serve<F>(listener: TcpListener, config: Config, shutdown: F) -> io::Result<()>
where
    F: Future<Output = ()>,
{
    let config = Arc::new(config);
    let slots = Arc::new(Semaphore::new(MAX_CONNECTIONS));
    let mut tasks = JoinSet::new();
    tokio::pin!(shutdown);
    loop {
        tokio::select! {
            _ = &mut shutdown => break,
            accepted = listener.accept() => {
                let (stream, _) = accepted?;
                let config = Arc::clone(&config);
                let slots = Arc::clone(&slots);
                tasks.spawn(async move {
                    let _ = handle_client(stream, config, slots).await;
                });
            }
            completed = tasks.join_next(), if !tasks.is_empty() => {
                let _ = completed;
            }
        }
    }
    tasks.abort_all();
    while tasks.join_next().await.is_some() {}
    Ok(())
}

struct UpgradeRequest {
    websocket_key: String,
}

async fn handle_client(
    mut client: TcpStream,
    config: Arc<Config>,
    slots: Arc<Semaphore>,
) -> io::Result<()> {
    let (request_head, client_tail) =
        match timeout(CLIENT_HEADER_TIMEOUT, read_head(&mut client)).await {
            Ok(Ok(value)) => value,
            _ => return Ok(()),
        };
    let request = match validate_request(&request_head, &config) {
        Ok(request) => request,
        Err(rejection) => {
            write_rejection(&mut client, rejection).await?;
            return Ok(());
        }
    };
    let _permit = match Arc::clone(&slots).try_acquire_owned() {
        Ok(permit) => permit,
        Err(_) => {
            write_rejection(
                &mut client,
                Rejection::new(503, "Service Unavailable", "Too many connections"),
            )
            .await?;
            return Ok(());
        }
    };

    let request = match request {
        ValidatedRequest::Update(update) => {
            serve_update(&mut client, &config, update).await?;
            return Ok(());
        }
        ValidatedRequest::WebSocket(request) => request,
    };

    let socket = match check_socket(&config.socket) {
        Ok(socket) => socket,
        Err(error) => {
            write_bad_gateway(
                &mut client,
                if error.kind() == io::ErrorKind::NotFound {
                    GatewayFailure::SocketUnavailable
                } else {
                    GatewayFailure::SocketValidation
                },
            )
            .await?;
            return Ok(());
        }
    };
    let mut upstream = match timeout(CONNECT_TIMEOUT, UnixStream::connect(&socket)).await {
        Ok(Ok(stream)) => stream,
        Ok(Err(_)) => {
            write_bad_gateway(&mut client, GatewayFailure::SocketConnect).await?;
            return Ok(());
        }
        Err(_) => {
            write_bad_gateway(&mut client, GatewayFailure::SocketTimeout).await?;
            return Ok(());
        }
    };
    if upstream
        .peer_cred()
        .and_then(|peer| check_peer_uid(peer.uid(), current_uid()))
        .is_err()
    {
        write_bad_gateway(&mut client, GatewayFailure::PeerOwner).await?;
        return Ok(());
    }
    let upstream_request = format!(
        "GET / HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {}\r\nSec-WebSocket-Version: 13\r\n\r\n",
        request.websocket_key
    );
    if upstream
        .write_all(upstream_request.as_bytes())
        .await
        .is_err()
    {
        write_bad_gateway(&mut client, GatewayFailure::UpstreamWrite).await?;
        return Ok(());
    }
    let (response_head, upstream_tail) =
        match timeout(UPSTREAM_HEADER_TIMEOUT, read_head(&mut upstream)).await {
            Ok(Ok(value)) => value,
            Ok(Err(_)) => {
                write_bad_gateway(&mut client, GatewayFailure::UpstreamRead).await?;
                return Ok(());
            }
            Err(_) => {
                write_bad_gateway(&mut client, GatewayFailure::UpstreamTimeout).await?;
                return Ok(());
            }
        };
    let accept = match validate_upstream_response(&response_head) {
        Ok(accept) => accept,
        Err(()) => {
            write_bad_gateway(&mut client, GatewayFailure::UpstreamUpgrade).await?;
            return Ok(());
        }
    };
    let response = format!(
        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {accept}\r\nCache-Control: no-store\r\n\r\n"
    );
    client.write_all(response.as_bytes()).await?;
    if !client_tail.is_empty() {
        upstream.write_all(&client_tail).await?;
    }
    if !upstream_tail.is_empty() {
        client.write_all(&upstream_tail).await?;
    }
    let _ = tokio::io::copy_bidirectional(&mut client, &mut upstream).await;
    Ok(())
}

enum ValidatedRequest {
    WebSocket(UpgradeRequest),
    Update(UpdateRequest),
}

enum UpdateRequest {
    Manifest,
    Apk(u64),
}

fn validate_request(head: &[u8], config: &Config) -> Result<ValidatedRequest, Rejection> {
    let mut headers = [httparse::EMPTY_HEADER; MAX_HEADERS];
    let mut request = httparse::Request::new(&mut headers);
    let parsed = request.parse(head).map_err(|_| Rejection::bad_request())?;
    if !parsed.is_complete() {
        return Err(Rejection::bad_request());
    }
    if request.method != Some("GET") {
        return Err(Rejection::new(404, "Not Found", "Not Found"));
    }
    let path = request.path.unwrap_or_default();
    let update = parse_update_path(path);
    if path != "/codex/rpc" && update.is_none() {
        return Err(Rejection::new(404, "Not Found", "Not Found"));
    }
    let authorization = unique_header(request.headers, "Authorization")
        .map_err(|_| Rejection::new(401, "Unauthorized", "Unauthorized"))?
        .unwrap_or_default();
    let actual: [u8; 32] = Sha256::digest(authorization.as_bytes()).into();
    if !bool::from(actual.ct_eq(&config.expected_authorization)) {
        return Err(Rejection::new(401, "Unauthorized", "Unauthorized"));
    }
    if let Some(update) = update {
        return Ok(ValidatedRequest::Update(update));
    }
    if header(request.headers, "Origin").is_some_and(|value| !value.is_empty()) {
        return Err(Rejection::new(
            403,
            "Forbidden",
            "Browser connections disabled",
        ));
    }
    let upgrade = header(request.headers, "Upgrade").unwrap_or_default();
    let connection = header(request.headers, "Connection").unwrap_or_default();
    if !upgrade.eq_ignore_ascii_case("websocket")
        || !connection
            .split(',')
            .any(|token| token.trim().eq_ignore_ascii_case("upgrade"))
    {
        return Err(Rejection::new(
            426,
            "Upgrade Required",
            "WebSocket required",
        ));
    }
    if header(request.headers, "Sec-WebSocket-Version") != Some("13") {
        return Err(Rejection::bad_request());
    }
    let websocket_key = unique_header(request.headers, "Sec-WebSocket-Key")
        .map_err(|_| Rejection::bad_request())?
        .filter(|value| !value.is_empty())
        .ok_or_else(Rejection::bad_request)?;
    Ok(ValidatedRequest::WebSocket(UpgradeRequest {
        websocket_key: websocket_key.to_owned(),
    }))
}

fn parse_update_path(path: &str) -> Option<UpdateRequest> {
    if path == UPDATE_MANIFEST {
        return Some(UpdateRequest::Manifest);
    }
    let suffix = path.strip_prefix(UPDATE_PREFIX)?;
    let version = suffix.strip_suffix("/remote-codex.apk")?;
    if version.is_empty()
        || version.len() > 10
        || version.starts_with('0')
        || !version.bytes().all(|byte| byte.is_ascii_digit())
    {
        return None;
    }
    version.parse().ok().map(UpdateRequest::Apk)
}

async fn serve_update(
    stream: &mut TcpStream,
    config: &Config,
    request: UpdateRequest,
) -> io::Result<()> {
    let Some(root) = config.update_root.as_ref() else {
        return write_rejection(stream, Rejection::new(404, "Not Found", "Not Found")).await;
    };
    let (path, content_type, maximum) = match request {
        UpdateRequest::Manifest => (
            root.join("stable/latest.json"),
            "application/json; charset=utf-8",
            MAX_MANIFEST_BYTES,
        ),
        UpdateRequest::Apk(version) => (
            root.join(format!("releases/{version}/remote-codex.apk")),
            "application/vnd.android.package-archive",
            MAX_APK_BYTES,
        ),
    };
    let file = match open_release_file(&path, maximum) {
        Ok(file) => file,
        Err(_) => {
            return write_rejection(stream, Rejection::new(404, "Not Found", "Not Found")).await;
        }
    };
    let length = file.metadata()?.len();
    let response = format!(
        "HTTP/1.1 200 OK\r\nCache-Control: private, no-store\r\nContent-Type: {content_type}\r\nContent-Length: {length}\r\nX-Content-Type-Options: nosniff\r\nX-Remote-Codex-Extension: updater-v1\r\nConnection: close\r\n\r\n"
    );
    stream.write_all(response.as_bytes()).await?;
    let mut file = tokio::fs::File::from_std(file);
    tokio::io::copy(&mut file, stream).await?;
    Ok(())
}

fn open_release_file(path: &Path, maximum: u64) -> io::Result<std::fs::File> {
    let file = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW)
        .open(path)?;
    let metadata = file.metadata()?;
    // Publishing owns these files as the service user. Reject mutable or unusual files.
    let current_uid = unsafe { libc::geteuid() };
    if !metadata.file_type().is_file()
        || metadata.uid() != current_uid
        || metadata.permissions().mode() & 0o022 != 0
        || metadata.len() == 0
        || metadata.len() > maximum
    {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "release file is missing or insecure",
        ));
    }
    Ok(file)
}

fn validate_upstream_response(head: &[u8]) -> Result<String, ()> {
    let mut headers = [httparse::EMPTY_HEADER; MAX_HEADERS];
    let mut response = httparse::Response::new(&mut headers);
    let parsed = response.parse(head).map_err(|_| ())?;
    if !parsed.is_complete() || response.code != Some(101) {
        return Err(());
    }
    if !header(response.headers, "Upgrade")
        .is_some_and(|value| value.eq_ignore_ascii_case("websocket"))
        || !header(response.headers, "Connection").is_some_and(|value| {
            value
                .split(',')
                .any(|token| token.trim().eq_ignore_ascii_case("upgrade"))
        })
        || header(response.headers, "Sec-WebSocket-Extensions").is_some()
    {
        return Err(());
    }
    unique_header(response.headers, "Sec-WebSocket-Accept")
        .map_err(|_| ())?
        .filter(|value| !value.is_empty())
        .map(str::to_owned)
        .ok_or(())
}

fn header<'a>(headers: &'a [httparse::Header<'a>], name: &str) -> Option<&'a str> {
    headers
        .iter()
        .find(|candidate| candidate.name.eq_ignore_ascii_case(name))
        .and_then(|candidate| std::str::from_utf8(candidate.value).ok())
        .map(str::trim)
}

fn unique_header<'a>(
    headers: &'a [httparse::Header<'a>],
    name: &str,
) -> Result<Option<&'a str>, ()> {
    let mut values = headers
        .iter()
        .filter(|candidate| candidate.name.eq_ignore_ascii_case(name));
    let value = values.next();
    if values.next().is_some() {
        return Err(());
    }
    value
        .map(|candidate| {
            std::str::from_utf8(candidate.value)
                .map(str::trim)
                .map_err(|_| ())
        })
        .transpose()
}

async fn read_head<S>(stream: &mut S) -> io::Result<(Vec<u8>, Vec<u8>)>
where
    S: AsyncRead + Unpin,
{
    let mut bytes = Vec::with_capacity(1024);
    let mut chunk = [0_u8; 2048];
    loop {
        let count = stream.read(&mut chunk).await?;
        if count == 0 {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "connection closed before headers",
            ));
        }
        bytes.extend_from_slice(&chunk[..count]);
        if let Some(end) = find_head_end(&bytes) {
            if end > MAX_HEADER_BYTES {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "headers too large",
                ));
            }
            return Ok((bytes[..end].to_vec(), bytes[end..].to_vec()));
        }
        if bytes.len() >= MAX_HEADER_BYTES {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "headers too large",
            ));
        }
    }
}

fn find_head_end(bytes: &[u8]) -> Option<usize> {
    bytes
        .windows(4)
        .position(|window| window == b"\r\n\r\n")
        .map(|position| position + 4)
}

struct Rejection {
    code: u16,
    reason: &'static str,
    message: &'static str,
}

impl Rejection {
    const fn new(code: u16, reason: &'static str, message: &'static str) -> Self {
        Self {
            code,
            reason,
            message,
        }
    }

    const fn bad_request() -> Self {
        Self::new(400, "Bad Request", "Bad Request")
    }
}

#[derive(Clone, Copy)]
enum GatewayFailure {
    SocketUnavailable,
    SocketValidation,
    SocketConnect,
    SocketTimeout,
    PeerOwner,
    UpstreamWrite,
    UpstreamRead,
    UpstreamTimeout,
    UpstreamUpgrade,
}

impl GatewayFailure {
    fn label(self) -> &'static str {
        match self {
            Self::SocketUnavailable => "socket_unavailable",
            Self::SocketValidation => "socket_validation",
            Self::SocketConnect => "socket_connect",
            Self::SocketTimeout => "socket_timeout",
            Self::PeerOwner => "peer_owner",
            Self::UpstreamWrite => "upstream_write",
            Self::UpstreamRead => "upstream_read",
            Self::UpstreamTimeout => "upstream_timeout",
            Self::UpstreamUpgrade => "upstream_upgrade",
        }
    }
}

fn should_log(last: &mut Option<Instant>, now: Instant) -> bool {
    if last.is_some_and(|previous| now.duration_since(previous) < Duration::from_secs(30)) {
        return false;
    }
    *last = Some(now);
    true
}

async fn write_bad_gateway(stream: &mut TcpStream, failure: GatewayFailure) -> io::Result<()> {
    static LAST_FAILURE: Mutex<Option<Instant>> = Mutex::new(None);
    if let Ok(mut last) = LAST_FAILURE.lock() {
        if should_log(&mut last, Instant::now()) {
            eprintln!(
                "Remote Codex upstream failure: reason={} status=502",
                failure.label()
            );
        }
    }
    write_rejection(
        stream,
        Rejection::new(502, "Bad Gateway", "Codex unavailable"),
    )
    .await
}

async fn write_rejection(stream: &mut TcpStream, rejection: Rejection) -> io::Result<()> {
    let body = format!("{}\n", rejection.message);
    let response = format!(
        "HTTP/1.1 {} {}\r\nCache-Control: no-store\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
        rejection.code,
        rejection.reason,
        body.len(),
        body
    );
    stream.write_all(response.as_bytes()).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use base64::Engine;
    use std::os::unix::fs::symlink;
    use std::process::{Child, Command, Stdio};
    use tempfile::tempdir;
    use tokio::net::UnixListener;

    const TOKEN: &str = "test-credential-0000000000000000000000000000000000000";

    #[test]
    fn validates_credentials_and_credential_files() {
        assert!(Config::new("/tmp/socket", "short").is_err());
        assert!(Config::new("/tmp/socket", &format!("{TOKEN} bad")).is_err());
        let directory = tempdir().unwrap();
        let credential = directory.path().join("connection-token");
        std::fs::write(&credential, format!("{TOKEN}\n")).unwrap();
        std::fs::set_permissions(&credential, std::fs::Permissions::from_mode(0o600)).unwrap();
        assert!(load_config_from_credential("/tmp/socket", directory.path()).is_ok());
        std::fs::set_permissions(&credential, std::fs::Permissions::from_mode(0o644)).unwrap();
        assert!(load_config_from_credential("/tmp/socket", directory.path()).is_err());
    }

    #[tokio::test]
    async fn rejects_invalid_requests_with_existing_status_contract() {
        let cases = [
            ("GET /codex/rpc HTTP/1.1\r\nHost: localhost\r\n\r\n", 401),
            (
                "GET /codex/rpc?token=secret HTTP/1.1\r\nHost: localhost\r\n\r\n",
                404,
            ),
            (
                "GET /codex/rpc HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer wrong\r\n\r\n",
                401,
            ),
            (
                concat!(
                    "GET /codex/rpc HTTP/1.1\r\nHost: localhost\r\n",
                    "Authorization: Bearer test-credential-0000000000000000000000000000000000000\r\n",
                    "Origin: https://evil.invalid\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n",
                    "Sec-WebSocket-Key: dGVzdC1rZXktMTIzNA==\r\nSec-WebSocket-Version: 13\r\n\r\n"
                ),
                403,
            ),
        ];
        for (request, expected) in cases {
            let response = request_once(request).await;
            assert!(
                response.starts_with(&format!("HTTP/1.1 {expected} ")),
                "{response}"
            );
            assert!(response.contains("Cache-Control: no-store"));
        }
    }

    #[test]
    fn accepts_only_namespaced_update_paths() {
        assert!(matches!(
            parse_update_path(UPDATE_MANIFEST),
            Some(UpdateRequest::Manifest)
        ));
        assert!(matches!(
            parse_update_path("/remote-codex/v1/updates/releases/6/remote-codex.apk"),
            Some(UpdateRequest::Apk(6))
        ));
        for path in [
            "/updates/stable/latest.json",
            "/remote-codex/v1/updates/releases/0/remote-codex.apk",
            "/remote-codex/v1/updates/releases/06/remote-codex.apk",
            "/remote-codex/v1/updates/releases/../remote-codex.apk",
            "/remote-codex/v1/updates/releases/6/../../secret",
            "/remote-codex/v1/updates/releases/6/remote-codex.apk?download=1",
        ] {
            assert!(parse_update_path(path).is_none(), "accepted {path}");
        }
    }

    #[tokio::test]
    async fn serves_authenticated_manifest_and_immutable_apk() {
        let directory = tempdir().unwrap();
        let stable = directory.path().join("stable");
        let release = directory.path().join("releases/6");
        std::fs::create_dir_all(&stable).unwrap();
        std::fs::create_dir_all(&release).unwrap();
        std::fs::write(stable.join("latest.json"), b"{\"schema\":1}\n").unwrap();
        std::fs::write(release.join("remote-codex.apk"), b"fixture-apk").unwrap();
        let config = Config::new("/does-not-exist", TOKEN)
            .unwrap()
            .with_update_root(directory.path())
            .unwrap();

        let unauthorized = request_once_with_config(
            "GET /remote-codex/v1/updates/stable/latest.json HTTP/1.1\r\nHost: localhost\r\n\r\n",
            config.clone(),
        )
        .await;
        assert!(unauthorized.starts_with("HTTP/1.1 401 Unauthorized"));

        let manifest = request_once_with_config(
            &format!(
                "GET {UPDATE_MANIFEST} HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {TOKEN}\r\n\r\n"
            ),
            config.clone(),
        )
        .await;
        assert!(manifest.starts_with("HTTP/1.1 200 OK"));
        assert!(manifest.contains("X-Remote-Codex-Extension: updater-v1"));
        assert!(manifest.ends_with("{\"schema\":1}\n"));

        let apk = request_once_with_config(
            &format!(
                "GET /remote-codex/v1/updates/releases/6/remote-codex.apk HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {TOKEN}\r\n\r\n"
            ),
            config.clone(),
        )
        .await;
        assert!(apk.starts_with("HTTP/1.1 200 OK"));
        assert!(apk.contains("application/vnd.android.package-archive"));
        assert!(apk.ends_with("fixture-apk"));

        let missing = request_once_with_config(
            &format!(
                "GET /remote-codex/v1/updates/releases/7/remote-codex.apk HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {TOKEN}\r\n\r\n"
            ),
            config,
        )
        .await;
        assert!(missing.starts_with("HTTP/1.1 404 Not Found"));
    }

    #[tokio::test]
    async fn validates_socket_and_forwards_upgraded_bytes_unchanged() {
        let directory = tempdir().unwrap();
        let socket = directory.path().join("stock.sock");
        let listener = UnixListener::bind(&socket).unwrap();
        assert!(check_socket(&socket).is_ok());
        let link = directory.path().join("link.sock");
        symlink(&socket, &link).unwrap();
        assert_eq!(check_socket(&link).unwrap(), socket);
        let regular = directory.path().join("regular");
        std::fs::write(&regular, b"x").unwrap();
        assert!(check_socket(&regular).is_err());

        let upstream = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let (head, tail) = read_head(&mut stream).await.unwrap();
            let request = String::from_utf8(head).unwrap();
            assert!(request.starts_with("GET / HTTP/1.1\r\n"));
            assert!(!request.to_ascii_lowercase().contains("authorization"));
            assert!(!request.to_ascii_lowercase().contains("cookie"));
            assert!(!request.to_ascii_lowercase().contains("extensions"));
            assert!(tail.is_empty());
            stream
                .write_all(
                    b"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: fixture\r\n\r\n",
                )
                .await
                .unwrap();
            let mut frame = [0_u8; 8];
            stream.read_exact(&mut frame).await.unwrap();
            stream.write_all(&frame).await.unwrap();
            frame
        });

        let tcp = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = tcp.local_addr().unwrap();
        let config = Arc::new(Config::new(&link, TOKEN).unwrap());
        let slots = Arc::new(Semaphore::new(MAX_CONNECTIONS));
        let server = tokio::spawn(async move {
            let (stream, _) = tcp.accept().await.unwrap();
            handle_client(stream, config, slots).await.unwrap();
        });
        let mut client = TcpStream::connect(address).await.unwrap();
        client.write_all(valid_request().as_bytes()).await.unwrap();
        let (head, tail) = read_head(&mut client).await.unwrap();
        assert!(
            String::from_utf8(head)
                .unwrap()
                .starts_with("HTTP/1.1 101 ")
        );
        assert!(tail.is_empty());
        let frame = [0x01, 0x02, 0x03, 0x04, 0x80, 0x7f, 0x00, 0xff];
        client.write_all(&frame).await.unwrap();
        let mut reply = [0_u8; 8];
        client.read_exact(&mut reply).await.unwrap();
        assert_eq!(reply, frame);
        drop(client);
        assert_eq!(upstream.await.unwrap(), frame);
        server.await.unwrap();
    }

    #[tokio::test]
    async fn rejects_unsafe_socket_aliases_and_peers() {
        let directory = tempdir().unwrap();
        let socket = directory.path().join("stock.sock");
        let _listener = UnixListener::bind(&socket).unwrap();
        let link = directory.path().join("link.sock");
        symlink(&socket, &link).unwrap();
        assert!(resolve_socket_for_uid(&link, current_uid() + 1).is_err());
        assert!(check_peer_uid(current_uid() + 1, current_uid()).is_err());
        assert!(check_peer_uid(current_uid(), current_uid()).is_ok());

        std::fs::set_permissions(&socket, std::fs::Permissions::from_mode(0o666)).unwrap();
        assert!(check_socket(&link).is_err());
        std::fs::set_permissions(&socket, std::fs::Permissions::from_mode(0o600)).unwrap();
        std::fs::set_permissions(directory.path(), std::fs::Permissions::from_mode(0o755)).unwrap();
        assert!(check_socket(&link).is_err());
        std::fs::set_permissions(directory.path(), std::fs::Permissions::from_mode(0o700)).unwrap();

        let regular = directory.path().join("regular");
        std::fs::write(&regular, b"private").unwrap();
        std::fs::remove_file(&link).unwrap();
        symlink(&regular, &link).unwrap();
        assert!(check_socket(&link).is_err());
        std::fs::remove_file(&link).unwrap();
        symlink(directory.path().join("missing"), &link).unwrap();
        let response =
            request_once_with_config(&valid_request(), Config::new(&link, TOKEN).unwrap()).await;
        assert!(response.starts_with("HTTP/1.1 502 Bad Gateway"));
        assert!(!response.contains("private"));
        assert!(!response.contains(TOKEN));
    }

    #[tokio::test]
    async fn resolves_replaced_socket_for_each_connection() {
        let directory = tempdir().unwrap();
        let link = directory.path().join("control.sock");
        let config = Config::new(&link, TOKEN).unwrap();
        for generation in 0..2 {
            let socket = directory.path().join(format!("stock-{generation}.sock"));
            let listener = UnixListener::bind(&socket).unwrap();
            if generation > 0 {
                std::fs::remove_file(&link).unwrap();
            }
            symlink(&socket, &link).unwrap();
            let upstream = tokio::spawn(async move {
                let (mut stream, _) = listener.accept().await.unwrap();
                read_head(&mut stream).await.unwrap();
                stream.write_all(b"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: fixture\r\n\r\n").await.unwrap();
            });
            let response = request_once_with_config(&valid_request(), config.clone()).await;
            assert!(response.starts_with("HTTP/1.1 101 "));
            upstream.await.unwrap();
            std::fs::remove_file(&socket).unwrap();
        }
    }

    #[test]
    fn bounds_upstream_failure_logging() {
        let start = Instant::now();
        let mut last = None;
        assert!(should_log(&mut last, start));
        assert!(!should_log(&mut last, start + Duration::from_secs(29)));
        assert!(should_log(&mut last, start + Duration::from_secs(30)));
    }

    #[tokio::test]
    async fn limits_active_connections_to_eight() {
        let directory = tempdir().unwrap();
        let socket = directory.path().join("stock.sock");
        let upstream_listener = UnixListener::bind(&socket).unwrap();
        let upstream = tokio::spawn(async move {
            let mut connections = JoinSet::new();
            for _ in 0..MAX_CONNECTIONS {
                let (mut stream, _) = upstream_listener.accept().await.unwrap();
                connections.spawn(async move {
                    let _ = read_head(&mut stream).await.unwrap();
                    stream
                        .write_all(
                            b"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: fixture\r\n\r\n",
                        )
                        .await
                        .unwrap();
                    std::future::pending::<()>().await;
                });
            }
            std::future::pending::<()>().await;
        });
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(serve(
            listener,
            Config::new(&socket, TOKEN).unwrap(),
            std::future::pending(),
        ));
        let mut clients = Vec::new();
        for _ in 0..MAX_CONNECTIONS {
            clients.push(RawWebSocket::connect(address).await.unwrap());
        }
        let mut ninth = TcpStream::connect(address).await.unwrap();
        ninth.write_all(valid_request().as_bytes()).await.unwrap();
        let (head, _) = read_head(&mut ninth).await.unwrap();
        assert!(head.starts_with(b"HTTP/1.1 503 Service Unavailable\r\n"));
        drop(clients);
        server.abort();
        upstream.abort();
    }

    async fn request_once(request: &str) -> String {
        request_once_with_config(request, Config::new("/does-not-exist", TOKEN).unwrap()).await
    }

    async fn request_once_with_config(request: &str, config: Config) -> String {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let config = Arc::new(config);
        let slots = Arc::new(Semaphore::new(MAX_CONNECTIONS));
        let server = tokio::spawn(async move {
            let (stream, _) = listener.accept().await.unwrap();
            handle_client(stream, config, slots).await.unwrap();
        });
        let mut client = TcpStream::connect(address).await.unwrap();
        client.write_all(request.as_bytes()).await.unwrap();
        client.shutdown().await.unwrap();
        let mut response = String::new();
        client.read_to_string(&mut response).await.unwrap();
        server.await.unwrap();
        response
    }

    fn valid_request() -> String {
        format!(
            "GET /codex/rpc HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {TOKEN}\r\nCookie: secret=value\r\nUpgrade: websocket\r\nConnection: keep-alive, Upgrade\r\nSec-WebSocket-Key: dGVzdC1rZXktMTIzNA==\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Extensions: permessage-deflate\r\n\r\n"
        )
    }

    struct ChildGuard(Child, PathBuf);

    impl Drop for ChildGuard {
        fn drop(&mut self) {
            let target = std::fs::canonicalize(&self.1).ok();
            let _ = self.0.kill();
            let _ = self.0.wait();
            if let Some(target) = target {
                if target.parent()
                    == Some(Path::new(&format!("/tmp/codex-daemon-{}", current_uid())))
                {
                    let lock = target.with_file_name(format!(
                        "{}.lock",
                        target.file_name().unwrap().to_string_lossy()
                    ));
                    let _ = std::fs::remove_file(lock);
                }
            }
        }
    }

    struct RawWebSocket {
        stream: TcpStream,
        buffered: Vec<u8>,
        sequence: u64,
        mask_seed: u32,
    }

    impl RawWebSocket {
        async fn connect(address: std::net::SocketAddr) -> io::Result<Self> {
            let mut stream = TcpStream::connect(address).await?;
            stream.write_all(valid_request().as_bytes()).await?;
            let (head, buffered) = read_head(&mut stream).await?;
            if !head.starts_with(b"HTTP/1.1 101 ") {
                return Err(io::Error::other("WebSocket upgrade failed"));
            }
            Ok(Self {
                stream,
                buffered,
                sequence: 0,
                mask_seed: 1,
            })
        }

        async fn initialize(&mut self) -> io::Result<serde_json::Value> {
            let result = self
                .call(
                    "initialize",
                    serde_json::json!({
                        "clientInfo": {"name": "remote-codex-rust-test", "version": "0.1.0"},
                        "capabilities": {"experimentalApi": true}
                    }),
                )
                .await?;
            self.send_json(
                &serde_json::json!({"method": "initialized", "params": {}}),
                256 * 1024,
            )
            .await?;
            Ok(result)
        }

        async fn call(
            &mut self,
            method: &str,
            params: serde_json::Value,
        ) -> io::Result<serde_json::Value> {
            self.sequence += 1;
            let id = self.sequence;
            self.send_json(
                &serde_json::json!({"id": id, "method": method, "params": params}),
                256 * 1024,
            )
            .await?;
            loop {
                let message = self.read_text().await?;
                let value: serde_json::Value = serde_json::from_slice(&message)
                    .map_err(|_| io::Error::other("invalid stock JSON"))?;
                if value.get("method").is_some()
                    || value.get("id").and_then(|v| v.as_u64()) != Some(id)
                {
                    continue;
                }
                if value.get("error").is_some() {
                    return Err(io::Error::other(format!("stock rejected {method}")));
                }
                return Ok(value
                    .get("result")
                    .cloned()
                    .unwrap_or_else(|| serde_json::json!({})));
            }
        }

        async fn send_json(
            &mut self,
            value: &serde_json::Value,
            fragment_size: usize,
        ) -> io::Result<()> {
            let bytes = serde_json::to_vec(value).map_err(io::Error::other)?;
            let chunk_count = bytes.len().div_ceil(fragment_size);
            for (index, chunk) in bytes.chunks(fragment_size).enumerate() {
                let opcode = if index == 0 { 0x1 } else { 0x0 };
                self.write_frame(index + 1 == chunk_count, opcode, chunk)
                    .await?;
            }
            Ok(())
        }

        async fn write_frame(&mut self, fin: bool, opcode: u8, payload: &[u8]) -> io::Result<()> {
            let mut header = Vec::with_capacity(14);
            header.push(if fin { 0x80 | opcode } else { opcode });
            match payload.len() {
                length if length < 126 => header.push(0x80 | length as u8),
                length if length <= u16::MAX as usize => {
                    header.push(0x80 | 126);
                    header.extend_from_slice(&(length as u16).to_be_bytes());
                }
                length => {
                    header.push(0x80 | 127);
                    header.extend_from_slice(&(length as u64).to_be_bytes());
                }
            }
            self.mask_seed = self
                .mask_seed
                .wrapping_mul(1_664_525)
                .wrapping_add(1_013_904_223);
            let mask = self.mask_seed.to_be_bytes();
            header.extend_from_slice(&mask);
            let mut masked = payload.to_vec();
            for (index, byte) in masked.iter_mut().enumerate() {
                *byte ^= mask[index % 4];
            }
            self.stream.write_all(&header).await?;
            self.stream.write_all(&masked).await
        }

        async fn read_text(&mut self) -> io::Result<Vec<u8>> {
            let mut message = Vec::new();
            let mut started = false;
            loop {
                let mut first = [0_u8; 2];
                self.read_exact_buffered(&mut first).await?;
                let fin = first[0] & 0x80 != 0;
                let opcode = first[0] & 0x0f;
                let masked = first[1] & 0x80 != 0;
                let mut length = u64::from(first[1] & 0x7f);
                if length == 126 {
                    let mut extended = [0_u8; 2];
                    self.read_exact_buffered(&mut extended).await?;
                    length = u64::from(u16::from_be_bytes(extended));
                } else if length == 127 {
                    let mut extended = [0_u8; 8];
                    self.read_exact_buffered(&mut extended).await?;
                    length = u64::from_be_bytes(extended);
                }
                if length > 100 * 1024 * 1024 {
                    return Err(io::Error::other("stock frame exceeds test limit"));
                }
                let mut mask = [0_u8; 4];
                if masked {
                    self.read_exact_buffered(&mut mask).await?;
                }
                let mut payload = vec![0_u8; length as usize];
                self.read_exact_buffered(&mut payload).await?;
                if masked {
                    for (index, byte) in payload.iter_mut().enumerate() {
                        *byte ^= mask[index % 4];
                    }
                }
                match opcode {
                    0x0 if started => message.extend_from_slice(&payload),
                    0x1 if !started => {
                        started = true;
                        message.extend_from_slice(&payload);
                    }
                    0x8 => return Err(io::Error::other("stock closed WebSocket")),
                    0x9 => {
                        self.write_frame(true, 0xa, &payload).await?;
                        continue;
                    }
                    0xa => continue,
                    _ => return Err(io::Error::other("unexpected stock WebSocket frame")),
                }
                if fin {
                    return Ok(message);
                }
            }
        }

        async fn read_exact_buffered(&mut self, output: &mut [u8]) -> io::Result<()> {
            let from_buffer = usize::min(output.len(), self.buffered.len());
            output[..from_buffer].copy_from_slice(&self.buffered[..from_buffer]);
            self.buffered.drain(..from_buffer);
            if from_buffer < output.len() {
                self.stream.read_exact(&mut output[from_buffer..]).await?;
            }
            Ok(())
        }
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn stock_control_socket_accepts_fragmented_twenty_mib_round_trip() {
        let Some(binary) = std::env::var_os("REMOTE_CODEX_TEST_CODEX") else {
            return;
        };
        let directory = tempdir().unwrap();
        let home = directory.path().join("codex");
        let workspace = directory.path().join("workspace");
        std::fs::create_dir_all(&home).unwrap();
        std::fs::create_dir_all(&workspace).unwrap();
        std::fs::write(
            home.join("config.toml"),
            concat!(
                "model = \"openai/gpt-5.6-sol\"\n",
                "model_provider = \"fixture\"\n",
                "approval_policy = \"on-request\"\n",
                "sandbox_mode = \"read-only\"\n",
                "cli_auth_credentials_store = \"file\"\n",
                "[model_providers.fixture]\n",
                "name = \"Fixture\"\n",
                "base_url = \"http://127.0.0.1:9/v1\"\n",
                "env_key = \"REMOTE_CODEX_FIXTURE_KEY\"\n",
                "wire_api = \"responses\"\n",
                "requires_openai_auth = false\n",
                "supports_websockets = false\n",
                "request_max_retries = 0\n",
                "stream_max_retries = 0\n"
            ),
        )
        .unwrap();
        let child = Command::new(binary)
            .args(["app-server", "--listen", "unix://"])
            .current_dir(directory.path())
            .env_clear()
            .env("HOME", directory.path())
            .env("CODEX_HOME", &home)
            .env("PATH", "/usr/bin:/bin")
            .env("LANG", "C.UTF-8")
            .env("REMOTE_CODEX_FIXTURE_KEY", "fake")
            .env("HTTP_PROXY", "http://127.0.0.1:9")
            .env("HTTPS_PROXY", "http://127.0.0.1:9")
            .env("NO_PROXY", "127.0.0.1,localhost")
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .unwrap();
        let _child = ChildGuard(
            child,
            home.join("app-server-control/app-server-control.sock"),
        );
        let socket = home.join("app-server-control/app-server-control.sock");
        timeout(Duration::from_secs(15), async {
            while check_socket(&socket).is_err() {
                tokio::time::sleep(Duration::from_millis(25)).await;
            }
        })
        .await
        .unwrap();

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(serve(
            listener,
            Config::new(&socket, TOKEN).unwrap(),
            std::future::pending(),
        ));
        let mut first = RawWebSocket::connect(address).await.unwrap();
        let initialized = first.initialize().await.unwrap();
        assert_eq!(
            initialized.get("codexHome").and_then(|v| v.as_str()),
            home.to_str()
        );

        let attachment_directory = home.join("attachments/remote-android/boundary");
        first
            .call(
                "fs/createDirectory",
                serde_json::json!({"path": attachment_directory, "recursive": true}),
            )
            .await
            .unwrap();
        let attachment_path = attachment_directory.join("20-mib.png");
        let mut attachment = vec![0_u8; 20 * 1024 * 1024];
        attachment[..27].copy_from_slice(b"remote-codex-image-boundary");
        let encoded = base64::engine::general_purpose::STANDARD.encode(&attachment);
        first
            .call(
                "fs/writeFile",
                serde_json::json!({"path": attachment_path, "dataBase64": encoded}),
            )
            .await
            .unwrap();
        assert_eq!(std::fs::read(&attachment_path).unwrap(), attachment);
        let read = first
            .call("fs/readFile", serde_json::json!({"path": attachment_path}))
            .await
            .unwrap();
        let round_trip = base64::engine::general_purpose::STANDARD
            .decode(read.get("dataBase64").and_then(|v| v.as_str()).unwrap())
            .unwrap();
        assert_eq!(round_trip, attachment);

        let started = first
            .call(
                "thread/start",
                serde_json::json!({
                    "cwd": workspace,
                    "historyMode": "paginated",
                    "ephemeral": false,
                    "threadSource": "agent_created_thread",
                    "projectId": null
                }),
            )
            .await
            .unwrap();
        let thread_id = started
            .pointer("/thread/id")
            .and_then(|value| value.as_str())
            .unwrap();
        let mut second = RawWebSocket::connect(address).await.unwrap();
        second.initialize().await.unwrap();
        let mut found = false;
        for _ in 0..30 {
            let read = second
                .call(
                    "thread/read",
                    serde_json::json!({"threadId": thread_id, "includeTurns": false}),
                )
                .await;
            found = read
                .ok()
                .and_then(|value| value.pointer("/thread/id").cloned())
                == Some(serde_json::Value::String(thread_id.to_owned()));
            if found {
                break;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        assert!(found, "second client did not read the server-owned task");
        server.abort();
    }
}

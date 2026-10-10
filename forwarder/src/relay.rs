//! Stock message payloads remain unchanged; only metadata notifications are added.
use crate::events;
use futures_util::{SinkExt, StreamExt};
use std::{io, time::Duration};
use tokio::{
    io::{AsyncRead, AsyncWrite},
    net::{TcpStream, UnixStream},
    sync::watch,
    time::timeout,
};
use tokio_tungstenite::{
    WebSocketStream,
    tungstenite::{
        Error, Message,
        protocol::frame::{
            Frame,
            coding::{Data, OpCode},
        },
        protocol::{Role, WebSocketConfig},
    },
};
const MESSAGE_LIMIT: usize = 100 * 1024 * 1024;
const WRITE_TIMEOUT: Duration = Duration::from_secs(10);
const FRAGMENT_SIZE: usize = 256 * 1024;

async fn write<S: AsyncRead + AsyncWrite + Unpin>(
    socket: &mut WebSocketStream<S>,
    message: Message,
) -> Result<(), Error> {
    let (bytes, kind) = match &message {
        Message::Text(text) => (text.as_bytes(), Data::Text),
        Message::Binary(bytes) => (bytes.as_ref(), Data::Binary),
        _ => return socket.send(message).await,
    };
    if bytes.len() <= FRAGMENT_SIZE {
        return socket.send(message).await;
    }
    // Stock's frame limit is smaller than its message limit. Preserve support for
    // large messages by regenerating bounded fragments, with one writer throughout.
    let count = bytes.len().div_ceil(FRAGMENT_SIZE);
    for (index, chunk) in bytes.chunks(FRAGMENT_SIZE).enumerate() {
        let opcode = OpCode::Data(if index == 0 { kind } else { Data::Continue });
        socket
            .send(Message::Frame(Frame::message(
                chunk.to_vec(),
                opcode,
                index + 1 == count,
            )))
            .await?;
    }
    Ok(())
}

fn reserved(message: &Message) -> bool {
    let bytes = match message {
        Message::Text(text) => text.as_bytes(),
        Message::Binary(bytes) => bytes.as_ref(),
        _ => return false,
    };
    serde_json::from_slice::<serde_json::Value>(bytes)
        .ok()
        .and_then(|m| {
            m.get("method")
                .and_then(|v| v.as_str())
                .map(|s| s.starts_with("remoteCodex/"))
        })
        .unwrap_or(false)
}

pub async fn forward(
    client: TcpStream,
    client_tail: Vec<u8>,
    upstream: UnixStream,
    upstream_tail: Vec<u8>,
    mut signals: watch::Receiver<u64>,
) -> io::Result<()> {
    let settings = WebSocketConfig::default()
        .max_message_size(Some(MESSAGE_LIMIT))
        .max_frame_size(Some(MESSAGE_LIMIT));
    let mut client =
        WebSocketStream::from_partially_read(client, client_tail, Role::Server, Some(settings))
            .await;
    let mut upstream =
        WebSocketStream::from_partially_read(upstream, upstream_tail, Role::Client, Some(settings))
            .await;
    // Once a complete message is being written, it owns the sink. Events cannot interleave frames.
    loop {
        tokio::select! {
            message = client.next() => {
                let Some(Ok(message)) = message else { break; };
                match message {
                    Message::Ping(_) | Message::Pong(_) => {
                        if !matches!(timeout(WRITE_TIMEOUT, client.flush()).await, Ok(Ok(()))) { break; }
                    },
                    Message::Close(_) => { let _ = timeout(WRITE_TIMEOUT, upstream.send(message)).await; break; },
                    message => {
                        // The app namespace is server-generated; never expose it to stock RPC.
                        if reserved(&message) { break; }
                        if !matches!(timeout(WRITE_TIMEOUT, write(&mut upstream, message)).await, Ok(Ok(()))) { break; }
                    }
                }
            },
            message = upstream.next() => {
                let Some(Ok(message)) = message else { break; };
                match message {
                    Message::Ping(_) | Message::Pong(_) => {
                        if !matches!(timeout(WRITE_TIMEOUT, upstream.flush()).await, Ok(Ok(()))) { break; }
                    },
                    Message::Close(_) => { let _ = timeout(WRITE_TIMEOUT, client.send(message)).await; break; },
                    message => if !matches!(timeout(WRITE_TIMEOUT, write(&mut client, message)).await, Ok(Ok(()))) { break; },
                }
            },
            changed = signals.changed() => {
                if changed.is_err() { break; }
                signals.borrow_and_update();
                if !matches!(timeout(WRITE_TIMEOUT, client.send(Message::Text(events::NOTICE.into()))).await, Ok(Ok(()))) { break; }
            }
        }
    }
    // Dropping these streams ends the connection; no mutation is ever buffered for reconnect.
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn reserved_namespace_includes_escaped_and_binary_methods() {
        for json in [
            r#"{"method":"remoteCodex/credentialRequestsChanged"}"#,
            r#"{"method":"\u0072emoteCodex/credentialRequestsChanged"}"#,
        ] {
            assert!(reserved(&Message::Text(json.into())));
            assert!(reserved(&Message::Binary(json.as_bytes().to_vec().into())));
        }
        assert!(!reserved(&Message::Text(
            r#"{"method":"fixture","params":{"text":"remoteCodex/"}}"#.into()
        )));
    }
}

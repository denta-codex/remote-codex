//! Application-owned Todo protocol. Stock Codex frames never enter this module.
use crate::{Config, UpgradeRequest};
use base64::{Engine, engine::general_purpose::STANDARD};
use futures_util::{SinkExt, StreamExt};
use remote_codex_todo::{ArchiveFilter, Change, Error, Status, Store};
use serde_json::{Value, json};
use std::{io, path::Path};
use tokio::{io::AsyncWriteExt, net::TcpStream};
use tokio_tungstenite::{
    WebSocketStream,
    tungstenite::{
        Message,
        handshake::derive_accept_key,
        protocol::{Role, WebSocketConfig},
    },
};

pub const PATH: &str = "/remote-codex/v1/todo";
const WIRE_LIMIT: usize = 1024 * 1024;

pub(crate) async fn serve(
    mut stream: TcpStream,
    tail: Vec<u8>,
    request: UpgradeRequest,
    config: &Config,
) -> io::Result<()> {
    if STANDARD
        .decode(&request.websocket_key)
        .ok()
        .is_none_or(|key| key.len() != 16)
    {
        stream
            .write_all(
                b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
            )
            .await?;
        return Ok(());
    }
    let accept = derive_accept_key(request.websocket_key.as_bytes());
    stream.write_all(format!("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {accept}\r\n\r\n").as_bytes()).await?;
    let settings = WebSocketConfig::default()
        .max_message_size(Some(WIRE_LIMIT))
        .max_frame_size(Some(WIRE_LIMIT));
    let mut socket =
        WebSocketStream::from_partially_read(stream, tail, Role::Server, Some(settings)).await;
    while let Some(Ok(message)) = socket.next().await {
        let text = match message {
            Message::Text(text) => text,
            Message::Ping(_) => {
                if socket.flush().await.is_err() {
                    break;
                }
                continue;
            }
            Message::Pong(_) => continue,
            _ => break,
        };
        let response = match parse(&text) {
            Err(response) => response,
            Ok((id, operation)) => {
                let permit = match config.todo_workers.clone().try_acquire_owned() {
                    Ok(permit) => permit,
                    Err(_) => {
                        let response = failure(id, -32001, "busy");
                        if socket
                            .send(Message::Text(response.to_string().into()))
                            .await
                            .is_err()
                        {
                            break;
                        }
                        continue;
                    }
                };
                let path = config.todo_database.clone();
                // The worker retains the permit even if its client disconnects. A dispatched
                // transaction is never aborted/replayed because delivery became uncertain.
                match tokio::task::spawn_blocking(move || {
                    let _permit = permit;
                    execute(&path, operation)
                })
                .await
                {
                    Ok(Ok(result)) => json!({"jsonrpc":"2.0", "id":id, "result":result}),
                    Ok(Err(error)) => failure(id, -32001, error.code()),
                    Err(_) => failure(id, -32603, "outcome_unknown"),
                }
            }
        };
        let encoded = response.to_string();
        if encoded.len() > WIRE_LIMIT {
            break;
        } // Do not turn a committed write into a rejection.
        if socket.send(Message::Text(encoded.into())).await.is_err() {
            break;
        }
    }
    let _ = socket.close(None).await;
    Ok(())
}

fn failure(id: Value, code: i32, kind: &str) -> Value {
    json!({"jsonrpc":"2.0", "id":id, "error":{"code":code,"message":"Todo request failed", "data":{"code":kind}}})
}

enum Operation {
    List,
    Show(i64),
    Create(String, String, Status),
    Edit(i64, i64, String, String),
    Move(i64, i64, Status),
}
fn text(value: &Value, name: &str) -> Result<String, ()> {
    value
        .get(name)
        .and_then(Value::as_str)
        .map(str::to_owned)
        .ok_or(())
}
fn number(value: &Value, name: &str) -> Result<i64, ()> {
    value
        .get(name)
        .and_then(Value::as_i64)
        .filter(|v| *v > 0)
        .ok_or(())
}
fn status(value: &Value) -> Result<Status, ()> {
    match value.get("status").and_then(Value::as_str) {
        Some("To Do") => Ok(Status::Todo),
        Some("In Progress") => Ok(Status::InProgress),
        Some("Done") => Ok(Status::Done),
        _ => Err(()),
    }
}
fn parse(text_value: &str) -> Result<(Value, Operation), Value> {
    let value: Value = serde_json::from_str(text_value)
        .map_err(|_| failure(Value::Null, -32700, "parse_error"))?;
    let id = value
        .get("id")
        .filter(|id| id.is_string() || id.as_i64().is_some())
        .cloned()
        .ok_or_else(|| failure(Value::Null, -32600, "invalid_request"))?;
    if value.get("jsonrpc").and_then(Value::as_str) != Some("2.0") {
        return Err(failure(id, -32600, "invalid_request"));
    }
    let params = value
        .get("params")
        .filter(|p| p.is_object())
        .ok_or_else(|| failure(id.clone(), -32602, "invalid_input"))?;
    let operation = (|| match value.get("method").and_then(Value::as_str) {
        Some("todo/list") => Ok(Operation::List),
        Some("todo/show") => Ok(Operation::Show(number(params, "id")?)),
        Some("todo/create") => Ok(Operation::Create(
            text(params, "title")?,
            text(params, "description")?,
            status(params)?,
        )),
        Some("todo/edit") => Ok(Operation::Edit(
            number(params, "id")?,
            number(params, "revision")?,
            text(params, "title")?,
            text(params, "description")?,
        )),
        Some("todo/move") => Ok(Operation::Move(
            number(params, "id")?,
            number(params, "revision")?,
            status(params)?,
        )),
        _ => Err(()),
    })();
    if !matches!(
        value.get("method").and_then(Value::as_str),
        Some("todo/list" | "todo/show" | "todo/create" | "todo/edit" | "todo/move")
    ) {
        return Err(failure(id, -32601, "method_not_found"));
    }
    operation
        .map(|op| (id.clone(), op))
        .map_err(|_| failure(id, -32602, "invalid_input"))
}
fn execute(path: &Path, operation: Operation) -> Result<Value, Error> {
    let mut store = Store::open(path)?;
    let task = match operation {
        Operation::List => {
            return Ok(json!({"tasks":store.list(None, ArchiveFilter::Active, None)?}));
        }
        Operation::Show(id) => store.show(id)?,
        Operation::Create(title, description, status) => {
            store.add_in_status(&title, &description, status)?
        }
        Operation::Edit(id, revision, title, description) => store.change(
            id,
            Some(revision),
            Change::Edit {
                title: Some(title),
                description: Some(description),
            },
        )?,
        Operation::Move(id, revision, status) => {
            store.change(id, Some(revision), Change::Move(status))?
        }
    };
    Ok(json!({"task":task}))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn websocket_owns_todo_without_a_stock_socket_and_limits_workers() {
        use tokio_tungstenite::tungstenite::client::IntoClientRequest;
        let directory = tempfile::tempdir().unwrap();
        let database = directory.path().join("todo.sqlite3");
        let token = "fixture-credential-0000000000000000000000000000000000";
        let config = Config::new("/absent-stock.sock", token)
            .unwrap()
            .with_todo_database(&database)
            .unwrap();
        let workers = config.todo_workers.clone();
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("ws://{}{}", listener.local_addr().unwrap(), PATH);
        let (stop, stopped) = tokio::sync::oneshot::channel();
        let server = tokio::spawn(crate::serve(listener, config, async {
            let _ = stopped.await;
        }));
        let mut unauthorized = url.clone().into_client_request().unwrap();
        unauthorized
            .headers_mut()
            .insert("Authorization", "Bearer wrong".parse().unwrap());
        assert!(
            tokio_tungstenite::connect_async(unauthorized)
                .await
                .is_err()
        );
        assert!(!database.exists());
        let mut browser = url.clone().into_client_request().unwrap();
        browser
            .headers_mut()
            .insert("Authorization", format!("Bearer {token}").parse().unwrap());
        browser
            .headers_mut()
            .insert("Origin", "https://example.test".parse().unwrap());
        assert!(tokio_tungstenite::connect_async(browser).await.is_err());
        let mut request = url.into_client_request().unwrap();
        request
            .headers_mut()
            .insert("Authorization", format!("Bearer {token}").parse().unwrap());
        let (mut client, _) = tokio_tungstenite::connect_async(request).await.unwrap();
        let held = workers.acquire_many(2).await.unwrap();
        client.send(Message::Text(json!({"jsonrpc":"2.0","id":"busy","method":"todo/create","params":{"title":"Idea","description":"","status":"Done"}}).to_string().into())).await.unwrap();
        let response: Value =
            serde_json::from_str(client.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
        assert_eq!(response["error"]["data"]["code"], "busy");
        assert!(!database.exists());
        drop(held);
        client.send(Message::Text(json!({"jsonrpc":"2.0","id":"create","method":"todo/create","params":{"title":"Idea","description":"","status":"Done"}}).to_string().into())).await.unwrap();
        let response: Value =
            serde_json::from_str(client.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
        assert_eq!(response["id"], "create");
        assert_eq!(response["result"]["task"]["status"], "Done");
        let mut local = Store::open(&database).unwrap();
        local
            .change(1, Some(1), Change::Note("CLI-visible note".into()))
            .unwrap();
        client.send(Message::Text(json!({"jsonrpc":"2.0","id":"conflict","method":"todo/edit","params":{"id":1,"revision":1,"title":"Stale","description":""}}).to_string().into())).await.unwrap();
        let response: Value =
            serde_json::from_str(client.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
        assert_eq!(response["error"]["data"]["code"], "revision_conflict");
        assert_eq!(local.show(1).unwrap().notes[0].text, "CLI-visible note");
        client.send(Message::Text("{bad".into())).await.unwrap();
        let response: Value =
            serde_json::from_str(client.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
        assert_eq!(response["error"]["code"], -32700);
        client.close(None).await.unwrap();
        stop.send(()).unwrap();
        server.await.unwrap().unwrap();
    }

    #[test]
    fn validates_before_writing_and_creates_in_selected_status() {
        let dir = tempfile::tempdir().unwrap();
        let db = dir.path().join("todo.sqlite3");
        assert!(
            parse(r#"{"jsonrpc":"2.0","id":"1","method":"todo/edit","params":{"id":1}}"#).is_err()
        );
        assert!(parse(r#"{"jsonrpc":"2.0","method":"todo/create","params":{}}"#).is_err());
        let (_, op) = parse(r#"{"jsonrpc":"2.0","id":"1","method":"todo/create","params":{"title":"Idea","description":"Notes","status":"Done"}}"#).unwrap();
        let result = execute(&db, op).unwrap();
        assert_eq!(result["task"]["status"], "Done");
        assert_eq!(result["task"]["revision"], 1);
        assert!(matches!(
            execute(&db, Operation::Edit(1, 8, "Changed".into(), "".into())),
            Err(Error::Conflict { .. })
        ));
        assert_eq!(
            execute(&db, Operation::Show(1)).unwrap()["task"]["title"],
            "Idea"
        );
    }
}

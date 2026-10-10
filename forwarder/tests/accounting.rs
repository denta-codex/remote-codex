use serde_json::{Value, json};
use std::{io::Write, process::Command};
use tempfile::NamedTempFile;

#[test]
fn helper_runs_without_forwarder_credentials_and_never_exposes_errors_or_text() {
    let mut rollout = NamedTempFile::new().unwrap();
    rollout.write_all(concat!(
        "{\"type\":\"session_meta\",\"payload\":{\"id\":\"chat-a\",\"model_provider\":\"openai\"}}\n",
        "{\"type\":\"response_item\",\"payload\":{\"text\":\"PRIVATE FIXTURE TRANSCRIPT\"}}\n"
    ).as_bytes()).unwrap();
    let run = |input: Value| {
        let output = Command::new(env!("CARGO_BIN_EXE_remote-codex-forwarder"))
            .args(["accounting", &input.to_string()])
            .env_remove("CREDENTIALS_DIRECTORY")
            .env("CODEX_SOCKET", "/absent.sock")
            .env("REMOTE_CODEX_LISTEN", "invalid")
            .output()
            .unwrap();
        assert!(output.status.success());
        assert!(output.stderr.is_empty());
        let text = String::from_utf8(output.stdout).unwrap();
        assert!(!text.contains("PRIVATE FIXTURE TRANSCRIPT"));
        serde_json::from_str::<Value>(&text).unwrap()
    };
    let response = run(json!({"version":1,"thread":"chat-a","path":rollout.path()}));
    assert_eq!("caughtUp", response["status"]);
    assert!(response["buckets"].as_array().unwrap().is_empty());
    let absent = "/absent-PRIVATE-FIXTURE-TRANSCRIPT.jsonl";
    let response = run(json!({"version":1,"thread":"chat-a","path":absent}));
    assert_eq!(json!({"version":1,"status":"unavailable"}), response);
}

#[tokio::test]
async fn stock_command_exec_returns_accounting_over_the_unchanged_forwarder() {
    use futures_util::{SinkExt, StreamExt};
    use remote_codex_forwarder::{Config, check_socket, serve};
    use std::{
        fs,
        process::{Child, Stdio},
        time::Duration,
    };
    use tokio::{
        net::TcpListener,
        time::{sleep, timeout},
    };
    use tokio_tungstenite::{
        connect_async,
        tungstenite::{Message, client::IntoClientRequest},
    };

    let Some(stock) = std::env::var_os("REMOTE_CODEX_TEST_CODEX") else {
        return;
    };
    struct ChildGuard(Child);
    impl Drop for ChildGuard {
        fn drop(&mut self) {
            let _ = self.0.kill();
            let _ = self.0.wait();
        }
    }
    let directory = tempfile::tempdir().unwrap();
    let home = directory.path().join("codex");
    let workspace = directory.path().join("workspace");
    fs::create_dir_all(&home).unwrap();
    fs::create_dir_all(&workspace).unwrap();
    fs::write(home.join("config.toml"), concat!(
        "model = \"fixture\"\nmodel_provider = \"fixture\"\napproval_policy = \"on-request\"\nsandbox_mode = \"read-only\"\n",
        "[model_providers.fixture]\nname = \"Fixture\"\nbase_url = \"http://127.0.0.1:9/v1\"\n",
        "env_key = \"REMOTE_CODEX_FIXTURE_KEY\"\nwire_api = \"responses\"\nrequires_openai_auth = false\n",
        "supports_websockets = false\nrequest_max_retries = 0\nstream_max_retries = 0\n"
    )).unwrap();
    let _child = ChildGuard(
        Command::new(stock)
            .args(["app-server", "--listen", "unix://"])
            .current_dir(&workspace)
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
            .unwrap(),
    );
    let socket = home.join("app-server-control/app-server-control.sock");
    timeout(Duration::from_secs(15), async {
        while check_socket(&socket).is_err() {
            sleep(Duration::from_millis(25)).await;
        }
    })
    .await
    .unwrap();
    let token = "fixture-accounting-token-000000000000000000000000000000";
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let server = tokio::spawn(serve(
        listener,
        Config::new(&socket, token).unwrap(),
        std::future::pending(),
    ));
    let mut request = format!("ws://{address}/codex/rpc")
        .into_client_request()
        .unwrap();
    request
        .headers_mut()
        .insert("Authorization", format!("Bearer {token}").parse().unwrap());
    let (mut websocket, _) = connect_async(request).await.unwrap();
    websocket.send(Message::Text(json!({"id":1,"method":"initialize","params":{
        "clientInfo":{"name":"accounting-test","version":"1"},"capabilities":{"experimentalApi":true}}}).to_string().into())).await.unwrap();
    let initialized = timeout(Duration::from_secs(5), websocket.next())
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    let initialized: Value = serde_json::from_str(initialized.to_text().unwrap()).unwrap();
    assert!(initialized.get("result").is_some());
    websocket
        .send(Message::Text(
            json!({"method":"initialized","params":{}})
                .to_string()
                .into(),
        ))
        .await
        .unwrap();
    let rollout = home.join("accounting-fixture.jsonl");
    fs::write(&rollout, concat!(
        "{\"type\":\"session_meta\",\"payload\":{\"id\":\"chat-a\",\"model_provider\":\"openai\"}}\n",
            "{\"type\":\"turn_context\",\"payload\":{\"model\":\"model-a\",\"service_tier\":\"default\"}}\n",
            "{\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":{\"total_token_usage\":{\"input_tokens\":1000,\"cached_input_tokens\":200,\"output_tokens\":100,\"total_tokens\":1100},\"last_token_usage\":{\"input_tokens\":1000,\"cached_input_tokens\":200,\"output_tokens\":100,\"total_tokens\":1100}}}}\n",
        "{\"type\":\"response_item\",\"payload\":{\"text\":\"PRIVATE FIXTURE TRANSCRIPT\"}}\n"
    )).unwrap();
    let input = json!({"version":1,"thread":"chat-a","path":rollout}).to_string();
    websocket.send(Message::Text(json!({"id":2,"method":"command/exec","params":{
        "command":[env!("CARGO_BIN_EXE_remote-codex-forwarder"),"accounting",input],"cwd":workspace,
        "sandboxPolicy":{"type":"readOnly","networkAccess":false},"timeoutMs":2000,"outputBytesCap":65536
    }}).to_string().into())).await.unwrap();
    let response = timeout(Duration::from_secs(5), async {
        loop {
            let message = websocket.next().await.unwrap().unwrap();
            if !message.is_text() {
                continue;
            }
            let response: Value = serde_json::from_str(message.to_text().unwrap()).unwrap();
            if response["id"] == 2 {
                break response;
            }
        }
    })
    .await
    .unwrap();
    assert!(response.get("error").is_none());
    assert_eq!(0, response["result"]["exitCode"]);
    let stdout = response["result"]["stdout"].as_str().unwrap();
    assert!(!stdout.contains("PRIVATE FIXTURE TRANSCRIPT"));
    let accounting: Value = serde_json::from_str(stdout).unwrap();
    assert_eq!("caughtUp", accounting["status"]);
    assert_eq!(1, accounting["buckets"][0]["requests"]);
    assert_eq!(1000, accounting["buckets"][0]["tokens"]["input_tokens"]);
    websocket.close(None).await.unwrap();
    server.abort();
    let _ = server.await;
}

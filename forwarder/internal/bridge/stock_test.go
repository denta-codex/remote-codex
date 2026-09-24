package bridge

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"github.com/coder/websocket"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestStockLifecycle(t *testing.T) {
	binary := os.Getenv("REMOTE_CODEX_TEST_CODEX")
	if binary == "" {
		t.Skip("set REMOTE_CODEX_TEST_CODEX for the isolated stock integration gate")
	}
	root := t.TempDir()
	home := filepath.Join(root, "codex")
	workspace := filepath.Join(root, "chat")
	os.MkdirAll(home, 0700)
	os.MkdirAll(workspace, 0700)
	var responses atomic.Int32
	model := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasSuffix(r.URL.Path, "/responses") {
			http.NotFound(w, r)
			return
		}
		requestNumber := responses.Add(1)
		time.Sleep(250 * time.Millisecond)
		w.Header().Set("Content-Type", "text/event-stream")
		item := map[string]any{"id": "msg-test", "type": "message", "role": "assistant", "content": []any{map[string]any{"type": "output_text", "text": "Remote Codex fixture complete"}}}
		if requestNumber == 2 {
			item = map[string]any{"id": "tool-approval", "type": "function_call", "call_id": "call-approval", "name": "exec_command", "arguments": `{"cmd":"printf fixture-approved","sandbox_permissions":"require_escalated","justification":"Isolated Remote Codex approval test"}`}
		}
		for _, event := range []any{map[string]any{"type": "response.created", "response": map[string]any{"id": "resp-test"}}, map[string]any{"type": "response.output_item.done", "item": item}, map[string]any{"type": "response.completed", "response": map[string]any{"id": "resp-test", "usage": map[string]any{"input_tokens": 1, "output_tokens": 1, "total_tokens": 2}}}} {
			b, _ := json.Marshal(event)
			fmt.Fprintf(w, "data: %s\n\n", b)
		}
	}))
	defer model.Close()
	config := fmt.Sprintf("model = \"openai/gpt-5.6-sol\"\nmodel_provider = \"fixture\"\napproval_policy = \"on-request\"\nsandbox_mode = \"read-only\"\ncli_auth_credentials_store = \"file\"\n[model_providers.fixture]\nname = \"Fixture\"\nbase_url = %q\nenv_key = \"REMOTE_CODEX_FIXTURE_KEY\"\nwire_api = \"responses\"\nrequires_openai_auth = false\nsupports_websockets = false\nrequest_max_retries = 0\nstream_max_retries = 0\n", model.URL+"/v1")
	os.WriteFile(filepath.Join(home, "config.toml"), []byte(config), 0600)
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, binary, "app-server", "--listen", "unix://")
	cmd.Dir = root
	cmd.Env = []string{"HOME=" + root, "CODEX_HOME=" + home, "PATH=/usr/bin:/bin", "LANG=C.UTF-8", "REMOTE_CODEX_FIXTURE_KEY=fake", "HTTP_PROXY=http://127.0.0.1:9", "HTTPS_PROXY=http://127.0.0.1:9", "NO_PROXY=127.0.0.1,localhost"}
	logFile, _ := os.Create(filepath.Join(root, "server.log"))
	cmd.Stdout = logFile
	cmd.Stderr = logFile
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() {
		cmd.Process.Kill()
		cmd.Wait()
		logFile.Close()
		if t.Failed() {
			b, _ := os.ReadFile(filepath.Join(root, "server.log"))
			t.Log(string(b))
		}
	}()
	socket := filepath.Join(home, "app-server-control/app-server-control.sock")
	for CheckSocket(socket) != nil {
		select {
		case <-ctx.Done():
			t.Fatal("stock socket not ready")
		case <-time.After(25 * time.Millisecond):
		}
	}
	handler, _ := New(socket, testToken)
	server := httptest.NewTLSServer(handler)
	defer server.Close()
	dial := func() *websocket.Conn {
		// Match OkHttp's default compression offer. Stock control sockets close
		// this handshake unless the forwarder declines extension negotiation.
		c, _, err := websocket.Dial(ctx, strings.Replace(server.URL, "https://", "wss://", 1)+"/codex/rpc", &websocket.DialOptions{HTTPClient: server.Client(), HTTPHeader: http.Header{"Authorization": []string{"Bearer " + testToken}, "Sec-WebSocket-Extensions": []string{"permessage-deflate"}}})
		if err != nil {
			t.Fatal(err)
		}
		c.SetReadLimit(16 << 20)
		return c
	}
	var sequence int
	var serverRequests []map[string]json.RawMessage
	call := func(c *websocket.Conn, method string, params any) map[string]json.RawMessage {
		sequence++
		id := sequence
		b, _ := json.Marshal(map[string]any{"id": id, "method": method, "params": params})
		if err := c.Write(ctx, websocket.MessageText, b); err != nil {
			t.Fatal(err)
		}
		for {
			_, b, err := c.Read(ctx)
			if err != nil {
				t.Fatal(err)
			}
			var m map[string]json.RawMessage
			json.Unmarshal(b, &m)
			if m["id"] != nil && m["method"] != nil {
				serverRequests = append(serverRequests, m)
			}
			if string(m["id"]) != fmt.Sprint(id) || m["method"] != nil {
				continue
			}
			if m["error"] != nil {
				t.Fatalf("%s: %s", method, m["error"])
			}
			var result map[string]json.RawMessage
			json.Unmarshal(m["result"], &result)
			return result
		}
	}
	init := func(c *websocket.Conn) {
		call(c, "initialize", map[string]any{"clientInfo": map[string]any{"name": "remote-codex-test", "version": "0.1.0"}, "capabilities": map[string]any{"experimentalApi": true}})
		c.Write(ctx, websocket.MessageText, []byte(`{"method":"initialized","params":{}}`))
	}
	first := dial()
	init(first)
	// ChatGPT Android preserves originals up to 20 MiB. Prove that the largest
	// supported image-sized fs/writeFile request traverses the real WSS proxy and
	// stock control socket without adding a second upload protocol.
	attachmentDir := filepath.Join(home, "attachments", "remote-android", "boundary")
	call(first, "fs/createDirectory", map[string]any{"path": attachmentDir, "recursive": true})
	attachmentPath := filepath.Join(attachmentDir, "20-mib.png")
	attachmentBytes := make([]byte, 20<<20)
	copy(attachmentBytes, []byte("remote-codex-image-boundary"))
	call(first, "fs/writeFile", map[string]any{"path": attachmentPath, "dataBase64": base64.StdEncoding.EncodeToString(attachmentBytes)})
	if info, err := os.Stat(attachmentPath); err != nil || info.Size() != int64(len(attachmentBytes)) {
		t.Fatalf("20 MiB attachment did not traverse stock WSS intact: info=%v err=%v", info, err)
	}
	// Mirrors the Android workspace preparation, with a narrow explicit write root.
	prep := call(first, "command/exec", map[string]any{"command": []string{"mkdir", "-p", "--", filepath.Join(workspace, "new")}, "sandboxPolicy": map[string]any{"type": "workspaceWrite", "writableRoots": []string{workspace}, "networkAccess": false}, "timeoutMs": 10000})
	if string(prep["exitCode"]) != "0" {
		t.Fatalf("mkdir failed: %s", prep)
	}
	created := call(first, "thread/start", map[string]any{"cwd": workspace, "historyMode": "paginated", "ephemeral": false, "threadSource": "agent_created_thread", "projectId": nil})
	var thread struct {
		ID string `json:"id"`
	}
	json.Unmarshal(created["thread"], &thread)
	second := dial()
	defer second.CloseNow()
	init(second)
	call(first, "turn/start", map[string]any{"threadId": thread.ID, "input": []any{map[string]any{"type": "text", "text": "fixture hello"}}, "clientUserMessageId": "fixture-message"})
	first.CloseNow()        // An accepted turn must survive the submitting client leaving.
	time.Sleep(time.Second) // Allow mock model completion before cold history inspection.
	call(second, "thread/resume", map[string]any{"threadId": thread.ID, "excludeTurns": true})
	var history map[string]json.RawMessage
	for {
		history = call(second, "thread/turns/list", map[string]any{"threadId": thread.ID, "limit": 20, "itemsView": "full", "sortDirection": "desc"})
		if strings.Contains(string(history["data"]), "Remote Codex fixture complete") {
			break
		}
		select {
		case <-ctx.Done():
			t.Fatal("accepted turn did not survive disconnect")
		case <-time.After(100 * time.Millisecond):
		}
	}
	if responses.Load() != 1 {
		t.Fatalf("expected exactly one model invocation, got %d", responses.Load())
	}
	search := call(second, "thread/search", map[string]any{"searchTerm": "fixture hello", "limit": 30, "sortKey": "updated_at"})
	if search["data"] == nil {
		t.Fatal("search has no data field")
	}
	reconnected := dial()
	defer reconnected.CloseNow()
	init(reconnected)
	resumed := call(reconnected, "thread/resume", map[string]any{"threadId": thread.ID, "excludeTurns": true})
	if !strings.Contains(string(resumed["thread"]), thread.ID) {
		t.Fatal("task identity changed")
	}
	call(reconnected, "turn/start", map[string]any{"threadId": thread.ID, "input": []any{map[string]any{"type": "text", "text": "fixture approval"}}, "clientUserMessageId": "fixture-approval-message"})
	var approval map[string]json.RawMessage
	for approval == nil {
		for _, m := range serverRequests {
			if string(m["method"]) == `"item/commandExecution/requestApproval"` {
				approval = m
				break
			}
		}
		if approval != nil {
			break
		}
		_, b, err := reconnected.Read(ctx)
		if err != nil {
			t.Fatal(err)
		}
		var m map[string]json.RawMessage
		json.Unmarshal(b, &m)
		if m["id"] != nil && m["method"] != nil {
			serverRequests = append(serverRequests, m)
		}
	}
	response, _ := json.Marshal(map[string]any{"id": approval["id"], "result": map[string]any{"decision": "decline"}})
	if err := reconnected.Write(ctx, websocket.MessageText, response); err != nil {
		t.Fatal(err)
	}
	for {
		_, b, err := reconnected.Read(ctx)
		if err != nil {
			t.Fatal(err)
		}
		if strings.Contains(string(b), `"turn/completed"`) {
			break
		}
	}
	t.Log("Command approval traversed WSS in both directions and decline allowed the turn to finish")
	t.Log("WSS → authenticated forwarder → isolated stock server: create, two clients, detach, full history, search and resume passed")
}

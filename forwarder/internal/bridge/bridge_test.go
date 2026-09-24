package bridge

import (
	"bufio"
	"context"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const testToken = "test-credential-0000000000000000000000000000000000000"

func TestAuthentication(t *testing.T) {
	h, err := New("/does-not-exist", testToken)
	if err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		path, token, origin, upgrade string
		status                       int
	}{
		{"/codex/rpc", "", "", "websocket", 401},
		{"/codex/rpc", "wrong", "", "websocket", 401},
		{"/codex/rpc", testToken, "https://evil.invalid", "websocket", 403},
		{"/codex/rpc", testToken, "", "", 426},
		{"/codex/rpc?token=secret", testToken, "", "websocket", 404},
		{"/other", testToken, "", "websocket", 404},
		{"/codex/rpc", testToken, "", "websocket", 502},
	}
	for _, c := range cases {
		r := httptest.NewRequest("GET", c.path, nil)
		r.Header.Set("Authorization", "Bearer "+c.token)
		r.Header.Set("Origin", c.origin)
		r.Header.Set("Upgrade", c.upgrade)
		r.Header.Set("Connection", "Upgrade")
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != c.status {
			t.Errorf("%s: got %d want %d", c.path, w.Code, c.status)
		}
	}
}
func TestSocketRejectsRegularFileAndSymlink(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "file")
	os.WriteFile(file, []byte("x"), 0600)
	if CheckSocket(file) == nil {
		t.Fatal("regular file accepted")
	}
	socket := filepath.Join(dir, "socket")
	l, err := net.Listen("unix", socket)
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	if err := CheckSocket(socket); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(dir, "link")
	os.Symlink(socket, link)
	if CheckSocket(link) == nil {
		t.Fatal("symlink accepted")
	}
}
func TestUpgradeForwardsBothDirectionsAndStripsCredential(t *testing.T) {
	socket := filepath.Join(t.TempDir(), "socket")
	listener, err := net.Listen("unix", socket)
	if err != nil {
		t.Fatal(err)
	}
	received := make(chan string, 1)
	upstream := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		received <- r.Header.Get("Authorization") + r.Header.Get("Sec-WebSocket-Extensions") + r.URL.String()
		conn, rw, err := w.(http.Hijacker).Hijack()
		if err != nil {
			return
		}
		defer conn.Close()
		rw.WriteString("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n")
		rw.Flush()
		// An upgraded reverse proxy must preserve arbitrary bytes, not interpret RPC.
		io.Copy(conn, rw)
	})}
	go upstream.Serve(listener)
	defer upstream.Close()
	handler, _ := New(socket, testToken)
	server := httptest.NewServer(handler)
	defer server.Close()
	conn, err := net.Dial("tcp", strings.TrimPrefix(server.URL, "http://"))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	req, _ := http.NewRequestWithContext(context.Background(), "GET", server.URL+"/codex/rpc", nil)
	req.Header.Set("Authorization", "Bearer "+testToken)
	req.Header.Set("Upgrade", "websocket")
	req.Header.Set("Connection", "Upgrade")
	req.Header.Set("Sec-WebSocket-Extensions", "permessage-deflate")
	req.Write(conn)
	reader := bufio.NewReader(conn)
	response, err := http.ReadResponse(reader, req)
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != 101 {
		t.Fatal(response.Status)
	}
	if got := <-received; got != "/" {
		t.Fatalf("upstream credential, extension, or path leaked: %q", got)
	}
	frame := []byte{0x81, 0x03, 'r', 'p', 'c'}
	conn.Write(frame)
	reply := make([]byte, len(frame))
	if _, err = io.ReadFull(reader, reply); err != nil {
		t.Fatal(err)
	}
	if string(frame) != string(reply) {
		t.Fatal("bytes changed")
	}
}

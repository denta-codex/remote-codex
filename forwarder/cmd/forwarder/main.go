package main

import (
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"remote-codex/forwarder/internal/bridge"
	"strings"
	"time"
)

func main() {
	socket := os.Getenv("CODEX_SOCKET")
	addr := os.Getenv("REMOTE_CODEX_LISTEN")
	if addr == "" {
		addr = "127.0.0.1:8787"
	}
	host, _, err := net.SplitHostPort(addr)
	if err != nil || net.ParseIP(host) == nil || !net.ParseIP(host).IsLoopback() {
		log.Fatal("listener must be a loopback IP")
	}
	token, err := loadToken(os.Getenv("CREDENTIALS_DIRECTORY"))
	if err != nil {
		log.Fatal(err)
	}
	handler, err := bridge.New(socket, token)
	if err != nil {
		log.Fatal(err)
	}
	server := &http.Server{Addr: addr, Handler: handler, ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16 << 10}
	log.Print("Remote Codex forwarder listening on loopback")
	// On SIGTERM the process closes all upgraded connections. Stock Codex is independent.
	log.Fatal(server.ListenAndServe())
}

func loadToken(directory string) (string, error) {
	if !filepath.IsAbs(directory) {
		return "", fmt.Errorf("systemd credential directory unavailable")
	}
	path := filepath.Join(directory, "connection-token")
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0077 != 0 {
		return "", fmt.Errorf("systemd connection credential missing or insecure")
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return "", fmt.Errorf("cannot read systemd connection credential")
	}
	return strings.TrimSpace(string(raw)), nil
}

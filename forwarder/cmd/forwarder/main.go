package main

import (
	"log"
	"net"
	"net/http"
	"os"
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
	tokenPath := os.Getenv("REMOTE_CODEX_TOKEN_FILE")
	info, err := os.Stat(tokenPath)
	if err != nil || info.Mode().Perm()&0077 != 0 {
		log.Fatal("credential file missing or accessible to other users")
	}
	raw, err := os.ReadFile(tokenPath)
	if err != nil {
		log.Fatal("cannot read credential file")
	}
	handler, err := bridge.New(socket, strings.TrimSpace(string(raw)))
	if err != nil {
		log.Fatal(err)
	}
	server := &http.Server{Addr: addr, Handler: handler, ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16 << 10}
	log.Print("Remote Codex forwarder listening on loopback")
	// On SIGTERM the process closes all upgraded connections. Stock Codex is independent.
	log.Fatal(server.ListenAndServe())
}

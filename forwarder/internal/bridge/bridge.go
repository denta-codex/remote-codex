// Package bridge authenticates a WebSocket upgrade and proxies bytes to stock Codex.
package bridge

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"errors"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"strings"
	"syscall"
	"time"
)

func CheckSocket(path string) error {
	info, err := os.Lstat(path)
	if err != nil {
		return errors.New("control socket unavailable")
	}
	st, ok := info.Sys().(*syscall.Stat_t)
	if !ok || info.Mode()&os.ModeSocket == 0 || int(st.Uid) != os.Getuid() {
		return errors.New("control socket must be an owned Unix socket, not a symlink")
	}
	return nil
}

func New(socket, token string) (http.Handler, error) {
	if len(token) < 43 || strings.ContainsAny(token, "\r\n\t ") {
		return nil, errors.New("credential must contain at least 43 non-whitespace characters")
	}
	expected := sha256.Sum256([]byte("Bearer " + token))
	transport := &http.Transport{DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
		if err := CheckSocket(socket); err != nil {
			return nil, err
		}
		return (&net.Dialer{Timeout: 5 * time.Second}).DialContext(ctx, "unix", socket)
	}, ResponseHeaderTimeout: 10 * time.Second}
	upstream := &url.URL{Scheme: "http", Host: "localhost"}
	proxy := &httputil.ReverseProxy{Transport: transport, Rewrite: func(p *httputil.ProxyRequest) {
		p.SetURL(upstream)
		p.Out.URL.Path = "/"
		p.Out.URL.RawPath = ""
		p.Out.URL.RawQuery = ""
		p.Out.Host = "localhost"
		p.Out.Header.Del("Authorization")
		p.Out.Header.Del("Cookie")
	}, ErrorHandler: func(w http.ResponseWriter, r *http.Request, err error) { http.Error(w, "Codex unavailable", 502) }, ErrorLog: log.New(discard{}, "", 0)}
	slots := make(chan struct{}, 8)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		if r.Method != "GET" || r.URL.Path != "/codex/rpc" || r.URL.RawQuery != "" {
			http.NotFound(w, r)
			return
		}
		got := sha256.Sum256([]byte(r.Header.Get("Authorization")))
		if subtle.ConstantTimeCompare(got[:], expected[:]) != 1 {
			http.Error(w, "Unauthorized", 401)
			return
		}
		if r.Header.Get("Origin") != "" {
			http.Error(w, "Browser connections disabled", 403)
			return
		}
		if !strings.EqualFold(r.Header.Get("Upgrade"), "websocket") {
			http.Error(w, "WebSocket required", 426)
			return
		}
		select {
		case slots <- struct{}{}:
			defer func() { <-slots }()
		default:
			http.Error(w, "Too many connections", 503)
			return
		}
		proxy.ServeHTTP(w, r)
	}), nil
}

type discard struct{}

func (discard) Write(p []byte) (int, error) { return len(p), nil }

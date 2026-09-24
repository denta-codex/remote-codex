package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLoadTokenRequiresPrivateSystemdCredential(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "connection-token")
	if _, err := loadToken(""); err == nil {
		t.Fatal("missing credential directory accepted")
	}
	if _, err := loadToken(dir); err == nil {
		t.Fatal("missing credential accepted")
	}
	token := strings.Repeat("a", 64)
	if err := os.WriteFile(path, []byte(token+"\n"), 0600); err != nil {
		t.Fatal(err)
	}
	if got, err := loadToken(dir); err != nil || got != token {
		t.Fatalf("got %q, %v", got, err)
	}
	if err := os.Chmod(path, 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := loadToken(dir); err == nil {
		t.Fatal("public credential accepted")
	}
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink("elsewhere", path); err != nil {
		t.Fatal(err)
	}
	if _, err := loadToken(dir); err == nil {
		t.Fatal("symlink credential accepted")
	}
}

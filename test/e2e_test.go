//go:build e2e

// Package e2e wraps scripts/e2e.sh so the end-to-end reverse-tunnel test can be
// run via `go test -tags e2e ./test/...` in addition to `bash scripts/e2e.sh`.
// The heavy lifting (private sshd, relay, agent, SSH-over-tunnel, tmux) lives in
// the script and test/e2eclient so there is a single source of truth.
package e2e

import (
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

func TestEndToEndReverseTunnel(t *testing.T) {
	repoRoot, err := filepath.Abs("..")
	if err != nil {
		t.Fatal(err)
	}
	script := filepath.Join(repoRoot, "scripts", "e2e.sh")
	if _, err := os.Stat(script); err != nil {
		t.Fatalf("e2e.sh not found: %v", err)
	}
	cmd := exec.Command("bash", script)
	cmd.Dir = repoRoot
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Run(); err != nil {
		t.Fatalf("scripts/e2e.sh failed: %v", err)
	}
}

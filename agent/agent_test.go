package main

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"math/rand"
	"net"
	"sync/atomic"
	"testing"
	"time"
)

func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

// TestRunReconnectsWithBackoff verifies the agent retries the control
// connection after drops, applying backoff between attempts.
func TestRunReconnectsWithBackoff(t *testing.T) {
	var calls int32
	a := &Agent{
		cfg:  Config{RelayURL: "ws://unused", DeviceID: "d", Token: "t", LocalSSH: "127.0.0.1:22"},
		log:  discardLogger(),
		base: time.Millisecond,
		max:  5 * time.Millisecond,
		rng:  rand.New(rand.NewSource(1)),
	}
	a.dialControl = func(ctx context.Context, url string) (ctrlClient, error) {
		atomic.AddInt32(&calls, 1)
		return nil, errors.New("boom: relay unreachable")
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go a.Run(ctx)

	deadline := time.Now().Add(2 * time.Second)
	for atomic.LoadInt32(&calls) < 3 {
		if time.Now().After(deadline) {
			t.Fatalf("agent only attempted %d connects, want >= 3 (reconnect not happening)", atomic.LoadInt32(&calls))
		}
		time.Sleep(time.Millisecond)
	}
	cancel()
	if got := atomic.LoadInt32(&calls); got < 3 {
		t.Fatalf("reconnect attempts = %d, want >= 3", got)
	}
}

// TestRunStopsOnContextCancel verifies Run returns promptly when ctx is cancelled.
func TestRunStopsOnContextCancel(t *testing.T) {
	a := NewAgent(Config{RelayURL: "ws://unused", DeviceID: "d", Token: "t", LocalSSH: "127.0.0.1:22"}, discardLogger())
	a.base = time.Millisecond
	a.max = 2 * time.Millisecond
	a.dialControl = func(ctx context.Context, url string) (ctrlClient, error) {
		return nil, errors.New("boom")
	}
	ctx, cancel := context.WithCancel(context.Background())
	doneCh := make(chan error, 1)
	go func() { doneCh <- a.Run(ctx) }()
	time.Sleep(10 * time.Millisecond)
	cancel()
	select {
	case <-doneCh:
	case <-time.After(time.Second):
		t.Fatal("Run did not stop within 1s of cancel")
	}
}

// TestHandleSessionBridges verifies handleSession dials the data conn and the
// local sshd, then bridges bytes both directions.
func TestHandleSessionBridges(t *testing.T) {
	a := NewAgent(Config{RelayURL: "ws://relay", DeviceID: "d", Token: "t", LocalSSH: "ignored"}, discardLogger())

	wsIn := make(chan []byte, 8)
	wsOut := make(chan []byte, 8)
	ws := newMemPacket(wsIn, wsOut)
	agentEnd, sshdEnd := net.Pipe()

	var dataURL string
	a.dialData = func(ctx context.Context, url string) (packetConn, error) {
		dataURL = url
		return ws, nil
	}
	a.dialLocal = func(ctx context.Context, addr string) (net.Conn, error) {
		return agentEnd, nil
	}

	go a.handleSession(context.Background(), "sess-xyz")

	// relay -> local
	wsIn <- []byte("client-to-host")
	buf := make([]byte, 64)
	sshdEnd.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, err := sshdEnd.Read(buf)
	if err != nil {
		t.Fatalf("sshd read: %v", err)
	}
	if string(buf[:n]) != "client-to-host" {
		t.Fatalf("relay->local = %q", buf[:n])
	}

	// local -> relay
	_, _ = sshdEnd.Write([]byte("host-to-client"))
	select {
	case got := <-wsOut:
		if string(got) != "host-to-client" {
			t.Fatalf("local->relay = %q", got)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for local->relay")
	}

	// The data URL must carry the session id and token.
	if dataURL == "" || !contains(dataURL, "session=sess-xyz") || !contains(dataURL, "token=t") {
		t.Fatalf("data URL = %q, want session+token query", dataURL)
	}

	_ = sshdEnd.Close()
	_ = ws.Close()
}

func contains(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}

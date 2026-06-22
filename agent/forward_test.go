package main

import (
	"errors"
	"io"
	"net"
	"sync"
	"testing"
	"time"
)

// memPacket is an in-memory packetConn for tests.
type memPacket struct {
	in        chan []byte
	out       chan []byte
	closeOnce sync.Once
	done      chan struct{}
}

func newMemPacket(in, out chan []byte) *memPacket {
	return &memPacket{in: in, out: out, done: make(chan struct{})}
}

func (m *memPacket) ReadMessage() ([]byte, error) {
	select {
	case b, ok := <-m.in:
		if !ok {
			return nil, io.EOF
		}
		return b, nil
	case <-m.done:
		return nil, io.EOF
	}
}

func (m *memPacket) WriteMessage(b []byte) error {
	cp := append([]byte(nil), b...)
	select {
	case m.out <- cp:
		return nil
	case <-m.done:
		return errors.New("closed")
	}
}

func (m *memPacket) Close() error {
	m.closeOnce.Do(func() { close(m.done) })
	return nil
}

// TestForwardBidirectional verifies forward copies bytes both ways between a
// relay data conn (message) and the local sshd conn (stream), verbatim.
func TestForwardBidirectional(t *testing.T) {
	wsIn := make(chan []byte, 8)
	wsOut := make(chan []byte, 8)
	ws := newMemPacket(wsIn, wsOut)
	agentEnd, sshdEnd := net.Pipe()

	done := make(chan struct{})
	go func() { forward(ws, agentEnd); close(done) }()

	// relay -> local sshd
	wsIn <- []byte("hello sshd")
	buf := make([]byte, 64)
	sshdEnd.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, err := sshdEnd.Read(buf)
	if err != nil {
		t.Fatalf("sshd read: %v", err)
	}
	if got := string(buf[:n]); got != "hello sshd" {
		t.Fatalf("ws->local = %q, want %q", got, "hello sshd")
	}

	// local sshd -> relay
	if _, err := sshdEnd.Write([]byte("SSH-2.0-remux\r\n")); err != nil {
		t.Fatalf("sshd write: %v", err)
	}
	select {
	case got := <-wsOut:
		if string(got) != "SSH-2.0-remux\r\n" {
			t.Fatalf("local->ws = %q, want SSH banner", got)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for local->ws message")
	}

	// Tear down and ensure forward returns.
	_ = sshdEnd.Close()
	_ = ws.Close()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("forward did not return after close")
	}
}

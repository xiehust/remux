package main

import (
	"bytes"
	"errors"
	"io"
	"math/rand"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// memConn is an in-memory dataConn for testing the byte-pipe bridge without a
// real WebSocket. ReadMessage drains `in`; WriteMessage appends to `out`.
type memConn struct {
	in        chan []byte
	out       chan []byte
	closeOnce sync.Once
	done      chan struct{}
}

func newMemConn(in, out chan []byte) *memConn {
	return &memConn{in: in, out: out, done: make(chan struct{})}
}

func (c *memConn) ReadMessage() ([]byte, error) {
	select {
	case m, ok := <-c.in:
		if !ok {
			return nil, io.EOF
		}
		return m, nil
	case <-c.done:
		return nil, io.EOF
	}
}

func (c *memConn) WriteMessage(b []byte) error {
	cp := append([]byte(nil), b...)
	select {
	case c.out <- cp:
		return nil
	case <-c.done:
		return errors.New("closed")
	}
}

func (c *memConn) Close() error {
	c.closeOnce.Do(func() { close(c.done) })
	return nil
}

// TestBridgeByteTransparency fuzzes random binary messages through the bridge
// and asserts they arrive byte-for-byte unchanged — proving the relay is a
// content-agnostic, lossless byte pipe.
func TestBridgeByteTransparency(t *testing.T) {
	const n = 200
	rng := rand.New(rand.NewSource(42))

	ain := make(chan []byte, n)
	want := make([][]byte, n)
	var totalBytes int
	for i := 0; i < n; i++ {
		sz := rng.Intn(4096) + 1
		buf := make([]byte, sz)
		rng.Read(buf)
		want[i] = buf
		totalBytes += sz
		ain <- buf
	}
	close(ain) // signals EOF for the a->b direction

	bout := make(chan []byte, n)
	a := newMemConn(ain, make(chan []byte, n)) // a->b reads from ain
	b := newMemConn(make(chan []byte), bout)   // b->a never produces input

	aToB, _ := bridge(a, b)

	close(bout)
	var got [][]byte
	for m := range bout {
		got = append(got, m)
	}
	if len(got) != n {
		t.Fatalf("got %d messages, want %d", len(got), n)
	}
	for i := range want {
		if !bytes.Equal(got[i], want[i]) {
			t.Fatalf("message %d corrupted: got %d bytes, want %d bytes", i, len(got[i]), len(want[i]))
		}
	}
	if aToB != int64(totalBytes) {
		t.Fatalf("aToB byte count = %d, want %d", aToB, totalBytes)
	}
}

// TestRendezvousPairing verifies the first /data arrival parks until the second
// arrives, the second receives the first's conn, and closing done releases the
// first.
func TestRendezvousPairing(t *testing.T) {
	rdv := newRendezvous(2 * time.Second)
	a := newMemConn(make(chan []byte), make(chan []byte))
	b := newMemConn(make(chan []byte), make(chan []byte))

	var firstReleased int32
	go func() {
		partner, done := rdv.join("s1", a)
		if partner != nil {
			t.Errorf("first arrival should get nil partner, got %v", partner)
		}
		if done == nil {
			t.Error("first arrival should get a done channel")
			return
		}
		<-done
		atomic.StoreInt32(&firstReleased, 1)
	}()

	// Wait until the first arrival is parked in pending.
	deadline := time.Now().Add(time.Second)
	for rdv.pendingCount() == 0 {
		if time.Now().After(deadline) {
			t.Fatal("first arrival never registered in pending")
		}
		time.Sleep(time.Millisecond)
	}

	partner, done := rdv.join("s1", b)
	if partner == nil {
		t.Fatal("second arrival should receive the first's conn")
	}
	if partner != a {
		t.Fatalf("second arrival got wrong partner: %v", partner)
	}
	if rdv.pendingCount() != 0 {
		t.Fatalf("pending should be empty after pairing, got %d", rdv.pendingCount())
	}

	close(done) // simulate the bridge finishing
	deadline = time.Now().Add(time.Second)
	for atomic.LoadInt32(&firstReleased) == 0 {
		if time.Now().After(deadline) {
			t.Fatal("first arrival was not released after done closed")
		}
		time.Sleep(time.Millisecond)
	}
}

// TestRendezvousTimeout verifies a lone /data arrival gives up after the
// pairing timeout.
func TestRendezvousTimeout(t *testing.T) {
	rdv := newRendezvous(40 * time.Millisecond)
	c := newMemConn(make(chan []byte), make(chan []byte))
	partner, done := rdv.join("lonely", c)
	if partner != nil || done != nil {
		t.Fatalf("lone arrival should time out with (nil,nil), got partner=%v done=%v", partner, done)
	}
	if rdv.pendingCount() != 0 {
		t.Fatalf("pending should be cleaned up after timeout, got %d", rdv.pendingCount())
	}
}

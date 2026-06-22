package main

import (
	"sync"
	"time"
)

// dataConn is one end of a data-plane WebSocket. The relay copies whole binary
// messages between two dataConns verbatim. Abstracted as an interface so the
// byte-pipe bridge is unit-testable with an in-memory implementation and so the
// relay code path is identical for tests and production.
type dataConn interface {
	// ReadMessage returns the next binary message (the SSH byte chunk).
	ReadMessage() ([]byte, error)
	// WriteMessage writes one binary message verbatim.
	WriteMessage([]byte) error
	// Close closes the connection.
	Close() error
}

// bridge copies messages a->b and b->a until either side errors or closes.
// It is intentionally content-agnostic: it never inspects, reframes, or alters
// payload bytes — this is what keeps the relay unable to read SSH traffic.
// It returns the total bytes copied in each direction (for logging counts only,
// never contents).
func bridge(a, b dataConn) (aToB, bToA int64) {
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		aToB = copyMessages(a, b)
		_ = b.Close()
		_ = a.Close()
	}()
	go func() {
		defer wg.Done()
		bToA = copyMessages(b, a)
		_ = a.Close()
		_ = b.Close()
	}()
	wg.Wait()
	return aToB, bToA
}

// copyMessages reads messages from src and writes them verbatim to dst until
// src errors/EOF. Returns total bytes forwarded.
func copyMessages(src, dst dataConn) int64 {
	var total int64
	for {
		msg, err := src.ReadMessage()
		if len(msg) > 0 {
			if werr := dst.WriteMessage(msg); werr != nil {
				return total
			}
			total += int64(len(msg))
		}
		if err != nil {
			return total
		}
	}
}

// waiter holds the first connection to arrive for a session while it waits for
// its partner.
type waiter struct {
	conn      dataConn
	partnerCh chan dataConn
	done      chan struct{}
}

// rendezvous pairs two data connections by session id. The first /data
// connection to arrive for a session parks until the second arrives; the second
// connection's handler runs the bridge and then releases the first.
type rendezvous struct {
	mu      sync.Mutex
	pending map[string]*waiter
	timeout time.Duration
}

func newRendezvous(timeout time.Duration) *rendezvous {
	return &rendezvous{pending: make(map[string]*waiter), timeout: timeout}
}

// join returns one of three outcomes:
//
//   - (partner != nil, done != nil): caller is the SECOND arrival. It must call
//     bridge(self, partner), then close(done) to release the first arrival.
//   - (partner == nil, done != nil): caller is the FIRST arrival and its partner
//     has arrived. It must block on <-done, then close its own conn.
//   - (partner == nil, done == nil): timed out waiting for a partner. Caller
//     should close its conn and give up.
func (r *rendezvous) join(sessionID string, c dataConn) (partner dataConn, done chan struct{}) {
	r.mu.Lock()
	if w, ok := r.pending[sessionID]; ok {
		// We are the second arrival. Hand our conn to the first and take its
		// conn + done channel to run the bridge.
		delete(r.pending, sessionID)
		r.mu.Unlock()
		w.partnerCh <- c
		return w.conn, w.done
	}
	w := &waiter{conn: c, partnerCh: make(chan dataConn, 1), done: make(chan struct{})}
	r.pending[sessionID] = w
	r.mu.Unlock()

	select {
	case <-w.partnerCh:
		// Partner arrived; it will run the bridge. Park on done.
		return nil, w.done
	case <-time.After(r.timeout):
		r.mu.Lock()
		// Only delete if still ours (partner may have raced in).
		if cur, ok := r.pending[sessionID]; ok && cur == w {
			delete(r.pending, sessionID)
			r.mu.Unlock()
			return nil, nil
		}
		r.mu.Unlock()
		// Lost the race: partner arrived just now; park on done.
		return nil, w.done
	}
}

// pendingCount is exposed for tests.
func (r *rendezvous) pendingCount() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.pending)
}

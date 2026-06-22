package main

import (
	"sync"
	"time"

	"github.com/remux/remux/proto"
)

// ctrlConn is the relay's handle to a connected agent's control connection.
// It is used to push Open requests to the agent. Abstracted as an interface so
// the registry is unit-testable without a real WebSocket.
type ctrlConn interface {
	// Send writes one already-encoded control envelope to the peer.
	Send(msg []byte) error
}

// device is a registered agent: its advertised info, the control connection to
// reach it, and the last time we heard from it (for heartbeat expiry).
type device struct {
	info     proto.DeviceInfo
	conn     ctrlConn
	lastSeen time.Time
}

// registry is the relay's in-memory table of online devices. All methods are
// safe for concurrent use. The clock is injectable so heartbeat expiry is
// deterministically testable.
type registry struct {
	mu      sync.Mutex
	now     func() time.Time
	timeout time.Duration
	devices map[string]*device
}

func newRegistry(timeout time.Duration, now func() time.Time) *registry {
	if now == nil {
		now = time.Now
	}
	return &registry{
		now:     now,
		timeout: timeout,
		devices: make(map[string]*device),
	}
}

// register adds or replaces a device and stamps it as just-seen.
func (r *registry) register(info proto.DeviceInfo, conn ctrlConn) {
	r.mu.Lock()
	defer r.mu.Unlock()
	info.Online = true
	r.devices[info.DeviceID] = &device{info: info, conn: conn, lastSeen: r.now()}
}

// touch refreshes a device's last-seen timestamp (called on pong/heartbeat).
// Returns false if the device is not registered.
func (r *registry) touch(deviceID string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	d, ok := r.devices[deviceID]
	if !ok {
		return false
	}
	d.lastSeen = r.now()
	return true
}

// remove deletes a device (called on disconnect).
func (r *registry) remove(deviceID string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	delete(r.devices, deviceID)
}

// list returns a snapshot of all registered devices.
func (r *registry) list() []proto.DeviceInfo {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]proto.DeviceInfo, 0, len(r.devices))
	for _, d := range r.devices {
		out = append(out, d.info)
	}
	return out
}

// conn returns the control connection for a device, or (nil,false) if absent.
func (r *registry) connFor(deviceID string) (ctrlConn, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	d, ok := r.devices[deviceID]
	if !ok {
		return nil, false
	}
	return d.conn, true
}

// expireStale removes any device not seen within the timeout, using the
// injected clock. Returns the IDs that were expired.
func (r *registry) expireStale() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	var expired []string
	cutoff := r.now().Add(-r.timeout)
	for id, d := range r.devices {
		if d.lastSeen.Before(cutoff) {
			delete(r.devices, id)
			expired = append(expired, id)
		}
	}
	return expired
}

// count returns the number of registered devices.
func (r *registry) count() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.devices)
}

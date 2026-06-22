package main

import (
	"testing"
	"time"

	"github.com/remux/remux/proto"
)

type fakeCtrl struct{ sent [][]byte }

func (f *fakeCtrl) Send(b []byte) error { f.sent = append(f.sent, b); return nil }

func TestRegistryRegisterListRemove(t *testing.T) {
	r := newRegistry(time.Minute, time.Now)
	r.register(proto.DeviceInfo{DeviceID: "d1", Name: "ec2"}, &fakeCtrl{})
	r.register(proto.DeviceInfo{DeviceID: "d2", Name: "mac"}, &fakeCtrl{})

	if r.count() != 2 {
		t.Fatalf("count = %d, want 2", r.count())
	}
	list := r.list()
	if len(list) != 2 {
		t.Fatalf("list len = %d, want 2", len(list))
	}
	for _, d := range list {
		if !d.Online {
			t.Errorf("device %s not marked online", d.DeviceID)
		}
	}
	if _, ok := r.connFor("d1"); !ok {
		t.Error("connFor(d1) not found")
	}
	r.remove("d1")
	if _, ok := r.connFor("d1"); ok {
		t.Error("d1 still present after remove")
	}
	if r.count() != 1 {
		t.Fatalf("count after remove = %d, want 1", r.count())
	}
}

func TestRegistryExpireStaleWithInjectedClock(t *testing.T) {
	now := time.Unix(1_000_000, 0)
	clock := func() time.Time { return now }
	r := newRegistry(30*time.Second, clock)
	r.register(proto.DeviceInfo{DeviceID: "d1"}, &fakeCtrl{})

	// Not yet stale.
	now = now.Add(10 * time.Second)
	if exp := r.expireStale(); len(exp) != 0 {
		t.Fatalf("expired too early: %v", exp)
	}
	if r.count() != 1 {
		t.Fatalf("count = %d, want 1", r.count())
	}

	// A pong at t+10 refreshes last-seen.
	r.touch("d1")

	// Advance past the timeout relative to the original register, but the touch
	// reset the clock, so still alive.
	now = now.Add(25 * time.Second) // 35s since register, but only 25s since touch
	if exp := r.expireStale(); len(exp) != 0 {
		t.Fatalf("expired despite recent touch: %v", exp)
	}

	// Now exceed the timeout since the last touch.
	now = now.Add(10 * time.Second) // 35s since touch
	exp := r.expireStale()
	if len(exp) != 1 || exp[0] != "d1" {
		t.Fatalf("expected d1 expired, got %v", exp)
	}
	if r.count() != 0 {
		t.Fatalf("count after expiry = %d, want 0", r.count())
	}
}

func TestRegistryTouchUnknown(t *testing.T) {
	r := newRegistry(time.Minute, time.Now)
	if r.touch("nope") {
		t.Error("touch of unknown device returned true")
	}
}

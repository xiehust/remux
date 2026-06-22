package main

import (
	"math/rand"
	"testing"
	"time"
)

func TestBackoffNeverExceedsMax(t *testing.T) {
	rng := rand.New(rand.NewSource(7))
	base := 100 * time.Millisecond
	max := 2 * time.Second
	for attempt := 0; attempt < 64; attempt++ {
		d := backoffDelay(attempt, base, max, rng)
		if d < 0 {
			t.Fatalf("attempt %d: negative delay %v", attempt, d)
		}
		if d > max {
			t.Fatalf("attempt %d: delay %v exceeds max %v", attempt, d, max)
		}
	}
}

// TestBackoffJitterWithinHalfToFull verifies, in the uncapped region, the delay
// is jittered into [ideal/2, ideal] where ideal = base<<attempt.
func TestBackoffJitterWithinHalfToFull(t *testing.T) {
	rng := rand.New(rand.NewSource(11))
	base := time.Second
	max := time.Hour
	for attempt := 0; attempt <= 5; attempt++ {
		ideal := base << uint(attempt)
		for i := 0; i < 50; i++ {
			d := backoffDelay(attempt, base, max, rng)
			if d < ideal/2 || d > ideal {
				t.Fatalf("attempt %d: delay %v outside [%v, %v]", attempt, d, ideal/2, ideal)
			}
		}
	}
}

// TestBackoffIncreasingTrend verifies the jitter floor (ideal/2) increases with
// the attempt number until the cap.
func TestBackoffIncreasingTrend(t *testing.T) {
	rng := rand.New(rand.NewSource(3))
	base := 50 * time.Millisecond
	max := 10 * time.Second
	var prevFloor time.Duration
	for attempt := 0; attempt <= 7; attempt++ {
		ideal := base << uint(attempt)
		if ideal > max {
			ideal = max
		}
		floor := ideal / 2
		if attempt > 0 && floor < prevFloor {
			t.Fatalf("attempt %d floor %v decreased below previous %v", attempt, floor, prevFloor)
		}
		// Sanity: a sampled delay respects this floor.
		if d := backoffDelay(attempt, base, max, rng); d < floor {
			t.Fatalf("attempt %d: delay %v below floor %v", attempt, d, floor)
		}
		prevFloor = floor
	}
}

package main

import (
	"math/rand"
	"time"
)

// backoffDelay computes the reconnect delay for a given attempt number using
// capped exponential backoff with "full-ish" jitter: the base doubles each
// attempt up to max, then a random jitter in [d/2, d] is returned. This avoids
// thundering-herd reconnect storms while staying bounded.
//
// It is a pure function (given the rng) so it is deterministically testable.
func backoffDelay(attempt int, base, max time.Duration, rng *rand.Rand) time.Duration {
	if attempt < 0 {
		attempt = 0
	}
	// Cap the shift to avoid overflow; 62 is well past any sane attempt count.
	shift := attempt
	if shift > 62 {
		shift = 62
	}
	d := base << uint(shift)
	if d <= 0 || d > max {
		d = max
	}
	half := d / 2
	if half <= 0 {
		return d
	}
	jitter := time.Duration(rng.Int63n(int64(half) + 1))
	return half + jitter
}

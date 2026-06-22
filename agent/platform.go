package main

import "runtime"

// platform returns the host OS label sent in the registration message
// ("linux", "darwin", ...). The agent is cross-platform (Linux EC2 + macOS).
func platform() string {
	return runtime.GOOS
}

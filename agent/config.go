package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
)

// Config is the device agent's configuration, loaded from a JSON file and/or
// overridden by flags.
type Config struct {
	// RelayURL is the base WebSocket URL of the relay, e.g. "wss://relay.example.com".
	RelayURL string `json:"relayUrl"`
	// DeviceID uniquely identifies this device in the relay registry.
	DeviceID string `json:"deviceId"`
	// Name is a human-friendly label shown in the app's device picker.
	Name string `json:"name"`
	// Token is the shared bearer token authenticating to the relay.
	Token string `json:"token"`
	// LocalSSH is the address the agent forwards sessions to. Default 127.0.0.1:22.
	LocalSSH string `json:"localSsh"`
}

// LoadConfig reads and parses a JSON config file.
func LoadConfig(path string) (*Config, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read config %q: %w", path, err)
	}
	var c Config
	if err := json.Unmarshal(b, &c); err != nil {
		return nil, fmt.Errorf("parse config %q: %w", path, err)
	}
	c.applyDefaults()
	return &c, nil
}

func (c *Config) applyDefaults() {
	if c.LocalSSH == "" {
		c.LocalSSH = "127.0.0.1:22"
	}
	if c.Name == "" {
		c.Name = c.DeviceID
	}
}

// Validate checks that all required fields are present.
func (c *Config) Validate() error {
	var missing []string
	if c.RelayURL == "" {
		missing = append(missing, "relayUrl")
	}
	if c.DeviceID == "" {
		missing = append(missing, "deviceId")
	}
	if c.Token == "" {
		missing = append(missing, "token")
	}
	if len(missing) > 0 {
		return fmt.Errorf("invalid config: missing required field(s): %v", missing)
	}
	if c.LocalSSH == "" {
		return errors.New("invalid config: localSsh is empty")
	}
	return nil
}

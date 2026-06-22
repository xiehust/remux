package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLoadConfigValidWithDefaults(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "agent.json")
	content := `{"relayUrl":"wss://relay.example.com","deviceId":"ec2-1","token":"sekret"}`
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := LoadConfig(path)
	if err != nil {
		t.Fatalf("LoadConfig: %v", err)
	}
	if err := cfg.Validate(); err != nil {
		t.Fatalf("Validate: %v", err)
	}
	if cfg.LocalSSH != "127.0.0.1:22" {
		t.Errorf("LocalSSH default = %q, want 127.0.0.1:22", cfg.LocalSSH)
	}
	if cfg.Name != "ec2-1" {
		t.Errorf("Name default = %q, want deviceId fallback", cfg.Name)
	}
}

func TestValidateMissingFields(t *testing.T) {
	cases := []struct {
		name string
		cfg  Config
		want string
	}{
		{"no relay", Config{DeviceID: "d", Token: "t", LocalSSH: "127.0.0.1:22"}, "relayUrl"},
		{"no device", Config{RelayURL: "ws://r", Token: "t", LocalSSH: "127.0.0.1:22"}, "deviceId"},
		{"no token", Config{RelayURL: "ws://r", DeviceID: "d", LocalSSH: "127.0.0.1:22"}, "token"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			err := c.cfg.Validate()
			if err == nil {
				t.Fatal("expected validation error, got nil")
			}
			if !strings.Contains(err.Error(), c.want) {
				t.Fatalf("error %q does not mention %q", err.Error(), c.want)
			}
		})
	}
}

func TestRelayModeURLs(t *testing.T) {
	c := &Config{RelayURL: "ws://relay:8080", DeviceID: "d", Token: "t", LocalSSH: "127.0.0.1:22"}
	c.applyDefaults()
	if c.Mode != "relay" {
		t.Fatalf("default mode = %q, want relay", c.Mode)
	}
	if got := c.controlURL(); got != "ws://relay:8080/agent/control" {
		t.Fatalf("controlURL = %q", got)
	}
	if got := c.dataBaseURL(); got != "ws://relay:8080" {
		t.Fatalf("dataBaseURL = %q", got)
	}
}

func TestApiGwModeURLs(t *testing.T) {
	c := &Config{
		Mode:     "apigw",
		RelayURL: "wss://abc.execute-api.us-east-2.amazonaws.com/prod",
		DataURL:  "ws://nlb-123.elb.us-east-2.amazonaws.com:8080",
		DeviceID: "d", Token: "sec ret", LocalSSH: "127.0.0.1:22",
	}
	c.applyDefaults()
	if err := c.Validate(); err != nil {
		t.Fatalf("Validate: %v", err)
	}
	got := c.controlURL()
	if got != "wss://abc.execute-api.us-east-2.amazonaws.com/prod?token=sec+ret" {
		t.Fatalf("apigw controlURL = %q (token must be query-escaped)", got)
	}
	if got := c.dataBaseURL(); got != "ws://nlb-123.elb.us-east-2.amazonaws.com:8080" {
		t.Fatalf("apigw dataBaseURL = %q, want the NLB data URL", got)
	}
}

func TestInvalidModeRejected(t *testing.T) {
	c := &Config{Mode: "bogus", RelayURL: "ws://r", DeviceID: "d", Token: "t", LocalSSH: "127.0.0.1:22"}
	if err := c.Validate(); err == nil {
		t.Fatal("expected error for invalid mode")
	}
}

func TestLoadConfigErrors(t *testing.T) {
	if _, err := LoadConfig("/no/such/file.json"); err == nil {
		t.Error("LoadConfig of missing file should error")
	}
	dir := t.TempDir()
	bad := filepath.Join(dir, "bad.json")
	_ = os.WriteFile(bad, []byte("{not json"), 0o600)
	if _, err := LoadConfig(bad); err == nil {
		t.Error("LoadConfig of malformed JSON should error")
	}
}

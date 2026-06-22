// Command agent is the Remux device agent. It runs on the developer's host
// (EC2 / MacBook), dials outbound to the relay, registers, and forwards mobile
// app sessions into the local sshd. It reconnects automatically with capped
// exponential backoff and is cross-platform (Linux + macOS).
package main

import (
	"context"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
)

// version is overridden at build time via -ldflags in release builds.
var version = "dev"

func main() {
	configPath := flag.String("config", os.Getenv("REMUX_AGENT_CONFIG"), "path to JSON config file")
	relayURL := flag.String("relay", "", "relay base URL (overrides config)")
	deviceID := flag.String("device-id", "", "device id (overrides config)")
	token := flag.String("token", "", "relay token (overrides config)")
	localSSH := flag.String("local-ssh", "", "local sshd address (overrides config; default 127.0.0.1:22)")
	mode := flag.String("mode", "", "control-plane mode: relay (default) | apigw (API Gateway WSS control + NLB data)")
	dataURL := flag.String("data-url", "", "data-plane relay base URL (apigw mode; defaults to -relay)")
	showVersion := flag.Bool("version", false, "print version and exit")
	flag.Parse()

	if *showVersion {
		fmt.Printf("remux-agent %s\n", version)
		return
	}

	log := slog.New(slog.NewJSONHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelInfo}))

	cfg := &Config{}
	if *configPath != "" {
		loaded, err := LoadConfig(*configPath)
		if err != nil {
			log.Error("failed to load config", "err", err)
			os.Exit(2)
		}
		cfg = loaded
	}
	// Flag overrides.
	if *relayURL != "" {
		cfg.RelayURL = *relayURL
	}
	if *deviceID != "" {
		cfg.DeviceID = *deviceID
	}
	if *token != "" {
		cfg.Token = *token
	}
	if *localSSH != "" {
		cfg.LocalSSH = *localSSH
	}
	if *mode != "" {
		cfg.Mode = *mode
	}
	if *dataURL != "" {
		cfg.DataURL = *dataURL
	}
	cfg.applyDefaults()

	if err := cfg.Validate(); err != nil {
		log.Error("configuration error", "err", err)
		fmt.Fprintln(os.Stderr, "hint: provide -config <file> or -relay/-device-id/-token flags")
		os.Exit(2)
	}

	log.Info("starting remux-agent", "version", version, "deviceId", cfg.DeviceID, "platform", platform())

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	agent := NewAgent(*cfg, log)
	if err := agent.Run(ctx); err != nil {
		log.Error("agent exited with error", "err", err)
		os.Exit(1)
	}
	log.Info("agent shut down cleanly")
}

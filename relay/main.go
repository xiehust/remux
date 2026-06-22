// Command relay is the Remux public relay server. It bridges a firewalled
// device agent and the mobile app over WebSocket without either side needing
// to accept inbound connections to the device. The relay is content-agnostic:
// it moves opaque bytes on the data plane and never reads SSH traffic.
package main

import (
	"context"
	"flag"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
	"time"
)

// version is overridden at build time via -ldflags in release builds.
var version = "dev"

func main() {
	cfg := DefaultConfig()
	flag.StringVar(&cfg.Addr, "addr", envOr("REMUX_RELAY_ADDR", ":8080"), "listen address")
	flag.StringVar(&cfg.Token, "token", os.Getenv("REMUX_RELAY_TOKEN"), "shared bearer token (empty disables auth — dev only)")
	hb := flag.Duration("heartbeat-timeout", 60*time.Second, "drop a peer after this long without a pong")
	st := flag.Duration("session-timeout", 30*time.Second, "give up pairing a data connection after this long")
	flag.Parse()
	cfg.HeartbeatTimeout = *hb
	cfg.SessionTimeout = *st

	log := slog.New(slog.NewJSONHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelInfo}))
	log.Info("starting remux-relay", "version", version)
	if cfg.Token == "" {
		log.Warn("no token set — authentication is DISABLED (dev only)")
	}

	srv := NewServer(cfg, log)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	if err := srv.ListenAndServe(ctx); err != nil {
		log.Error("relay exited with error", "err", err)
		os.Exit(1)
	}
	log.Info("relay shut down cleanly")
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

# Remux architecture

Remux is a mobile SSH client for driving AI coding agents inside remote tmux
sessions on a firewalled dev host. Three components plus deploy infrastructure.

```
┌────────────┐   WSS    ┌──────────────┐   WSS/TCP   ┌──────────────┐  TCP  ┌──────┐
│ Android app│◀────────▶│ Relay server │◀───────────▶│ Device agent │◀─────▶│ sshd │
│  (SSHJ)    │  control │  (Go, opaque │   outbound  │ (Go, EC2/Mac)│ :22   └──┬───┘
│            │  + data  │   byte pipe) │   from host │              │          │ tmux
└────────────┘          └──────────────┘             └──────────────┘      Claude Code / Codex
        └──────────────── end-to-end SSH encryption ────────────────┘
```

## Components

### `relay/` — relay server (Go)
Public, content-agnostic byte pipe. WebSocket endpoints:
`/agent/control` (agent registers, receives open requests), `/app/control` (app
auths, lists devices, opens sessions), `/data` (per-session byte pipe), `/healthz`.
An in-memory registry tracks online devices with heartbeat expiry. The session
manager mints a session id, notifies the target agent, and pairs the two `/data`
connections, bridging bytes verbatim. See `docs/PROTOCOL.md`.

### `agent/` — device agent (Go)
Runs on the dev host, dials *outbound* to the relay (pierces NAT/firewall),
registers, heartbeats, and reconnects with capped exponential backoff. On an
`open` request it dials `/data` and the local sshd (`127.0.0.1:22`), bridging the
two. Cross-platform (Linux + macOS).

### `android/` — mobile app (Kotlin / Jetpack Compose)
- `:core` (pure Kotlin/JVM, unit-tested off-device): xterm-256color terminal
  emulator (`terminal/`), ssh_config parser (`ssh/`), tmux output parser
  (`tmux/`), tunnel control protocol (`relay/`), AI-command catalog (`ai/`),
  shortcut-key byte mapping (`input/`).
- `:app` (Android): Compose UI (host list/edit, relay device picker, terminal
  screen, shortcut toolbar, AI panel), SSHJ session wrapper with TOFU host-key
  verification (`ssh/`), OkHttp relay client + loopback `TunnelBridge` (`relay/`),
  Room persistence + `EncryptedSharedPreferences` key store.

The app speaks **real SSH over the tunnel**: `TunnelBridge` opens a localhost
loopback socket bridged to the relay `/data` WebSocket, and SSHJ connects to
`127.0.0.1:<localPort>` — so the relay sees only encrypted bytes.

### `infra/` — AWS deployment (Terraform + Go Lambda)
Deploy-ready (not auto-applied) production topology mirroring the PRD: API
Gateway WebSocket API + Lambda (connection lifecycle/routing) + DynamoDB
registry + ECS Fargate (containerized relay) + NLB (agent ingress). See
`infra/README.md`.

## Why this shape
- **Firewall piercing without inbound ports:** both ends dial out to the relay.
- **Security:** end-to-end SSH means a compromised relay can't read sessions.
- **Testability:** all non-UI logic lives in pure modules (`:core`, Go packages)
  with real tests; the reverse tunnel is proven end-to-end with `scripts/e2e.sh`.

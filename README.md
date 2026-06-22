# Remux — *Remote + tmux.* Your tmux, in your pocket.

> Tap into your AI agents, anywhere.

Remux is a mobile SSH client (Android-first) optimized for driving AI coding
agents (Claude Code, Codex, …) inside remote `tmux` sessions on a developer's
EC2 instance or MacBook — even when that host sits behind a firewall/NAT.

This repository is a monorepo with three components plus deploy infrastructure:

| Path        | Component       | Language / stack            | What it is |
|-------------|-----------------|-----------------------------|------------|
| `proto/`    | Wire protocol   | Go (mirrored in Kotlin)     | Shared relay protocol contract (`docs/PROTOCOL.md`) |
| `relay/`    | Relay server    | Go (`gorilla/websocket`)    | Public, content-agnostic byte-pipe that bridges device ↔ app |
| `agent/`    | Device agent    | Go                          | Runs on EC2/MacBook; dials out to the relay; forwards to local `sshd` |
| `test/`     | E2E tests       | Go + `tmux`/`sshd`          | Real SSH-over-tunnel + tmux integration test |
| `infra/`    | AWS infra       | Terraform + Go Lambda       | Deploy-ready API GW WSS + Lambda + DynamoDB + Fargate + NLB |
| `android/`  | Mobile app      | Kotlin / Jetpack Compose / SSHJ | The Remux Android client |
| `scripts/`  | Dev scripts     | bash                        | `e2e.sh` and helpers |
| `docs/`     | Docs            | Markdown                    | Protocol, architecture, security |

## Architecture in one diagram

```
Android app  <--WSS-->  Relay server  <--WSS-->  Device agent  <--TCP-->  sshd
   (SSHJ)               (opaque pipe)            (EC2/MacBook)         (127.0.0.1:22)
                                                                          └─ tmux ─ Claude Code / Codex
```

The relay only moves opaque bytes; a standard SSH connection runs end-to-end
over the tunnel, so the relay can never read session content. See
`docs/PROTOCOL.md` for the full protocol.

## Build & test

### Backend (Go relay + agent + protocol)

```bash
go build ./...          # build relay, agent, proto, test
go vet ./...
go test ./...           # unit tests
```

### End-to-end reverse tunnel (real SSH + tmux)

```bash
bash scripts/e2e.sh     # added in phase 4
```

### AWS infrastructure (validate only; no deploy)

```bash
terraform -chdir=infra/terraform init -backend=false
terraform -chdir=infra/terraform validate
```

### Android app

```bash
cd android && ./gradlew :core:test assembleDebug lintDebug
```

(Requires the Android SDK; see `android/` and phase 6 for the bootstrap.)

## Docs

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — components and how they fit together
- [`docs/PROTOCOL.md`](docs/PROTOCOL.md) — relay wire protocol
- [`docs/SECURITY.md`](docs/SECURITY.md) — security model & checklist
- [`docs/ANDROID_BUILD.md`](docs/ANDROID_BUILD.md) — Android build + ARM64 host setup
- [`infra/README.md`](infra/README.md) — AWS deploy guide & cost estimate

## Status

MVP (v0.1) under active construction. See the roadmap and live progress under
`.supergoal/`. Roadmap scope: relay + agent reverse tunnel, SSH key auth,
xterm-256color terminal, tmux detect/attach, mobile shortcut toolbar, AI-command
quick panel, and dark theme.

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**Remux** is a mobile SSH client (Android-first) for driving AI coding agents (Claude Code / Codex) inside remote `tmux` sessions on a **firewalled** dev host (EC2 / MacBook). It's a monorepo of three cooperating components plus AWS deploy infra. The defining idea: the dev host and the phone both only make *outbound* connections to a public relay, which bridges them — so no inbound port on the dev host is ever needed.

```
Android app  ──WSS──▶  Relay server  ──WSS/TCP──▶  Device agent  ──TCP──▶  sshd
  (SSHJ)      control   (Go, opaque        outbound  (Go, on host)  :22    └ tmux ─ AI agent
              + data     byte pipe)         from host
        └──────────────── end-to-end SSH encryption (relay can't read it) ──────────────┘
```

## Architecture (the big picture)

Read `docs/ARCHITECTURE.md` and `docs/PROTOCOL.md` first — they're the source of truth. Key load-bearing decisions:

- **The relay is content-agnostic.** It moves opaque binary bytes between two `/data` WebSockets and never parses them. A *standard SSH connection runs end-to-end inside the tunnel*, so the relay (even if compromised) cannot read sessions. `relay/session.go`'s `bridge`/`copyMessages` must never inspect or reframe payloads — a byte-fuzz test enforces this, and `scripts/e2e.sh` asserts the relay logs only byte counts.
- **Per-session WebSocket pair, no data-plane framing.** Control plane = JSON `Envelope` messages (`proto/proto.go`, mirrored in Kotlin `android/core/.../relay/TunnelProtocol.kt`); data plane = raw binary. After the `open`/`opened` handshake, app and agent each dial `/data?session=…&token=…` and the relay pairs them by session id. Endpoints: `/agent/control`, `/app/control`, `/data`, `/healthz`.
- **The app speaks real SSH over the tunnel via a loopback bridge.** `android/.../relay/TunnelBridge.kt` opens a localhost `ServerSocket` wired to the relay `/data` WebSocket; SSHJ then connects to `127.0.0.1:<localPort>` as if it were a normal socket. This avoids needing SSHJ to accept a custom transport.
- **Android `:core` vs `:app` split is deliberate** — all non-UI logic lives in the pure-Kotlin/JVM `:core` module (terminal VT emulator, `ssh_config`/`tmux` parsers, tunnel protocol, AI-command catalog, shortcut key→byte mapping) so it is unit-tested **without an emulator**. `:core` must have **zero `android`/`androidx` imports**. `:app` holds Compose UI, SSHJ, OkHttp, Room, and the Keystore-backed key store.
- **Go workspace = two modules.** Root module `github.com/remux/remux` (`proto`, `relay`, `agent`, `test`) and a separate `github.com/remux/remux/infra/lambda` module (keeps the AWS SDK out of the relay/agent build). `go build ./...` from root builds the root module only; build the Lambda explicitly. The relay `Dockerfile` sets `GOWORK=off` to build just the root module.
- **AWS infra mirrors the self-hosted relay.** `infra/terraform` is the production topology (API GW WebSocket + Lambda + DynamoDB + ECS Fargate + NLB); `infra/lambda` is its connection-lifecycle/routing handler. It is **deploy-ready but never auto-applied** — `terraform validate` (no credentials) is the gate.

Security model & checklist: `docs/SECURITY.md`. Private keys live only in `EncryptedSharedPreferences` (`SecureKeyStore`); Room's `HostEntity` stores a `keyAlias`, never key material. Host keys use trust-on-first-use (`TofuHostKeyVerifier`).

## Commands

### Go backend (run from repo root)
```bash
go build ./...                 # relay, agent, proto, test
go vet ./...
go test ./...                  # unit tests
go test -race ./...            # must stay race-clean
go test ./relay/...            # one package
go test -run TestBridgeByteTransparency ./relay/...   # one test
GOOS=darwin go build ./agent/...   # agent must cross-compile (linux + darwin)
gofmt -l .                     # must print nothing
```

### End-to-end reverse tunnel (real SSH + tmux)
```bash
bash scripts/e2e.sh            # spins a private sshd + relay + agent, drives SSH+tmux through the tunnel; exit 0 = pass
go test -tags e2e ./test/...   # same, via the build-tagged wrapper
```
Self-contained (generates its own sshd host/client keys on an ephemeral port); needs `sshd`, `tmux`, `ssh-keygen`, `curl` on PATH. The single most important regression check — if you touch the relay or agent, re-run it.

### AWS infra (validate only — never `apply` here)
```bash
terraform -chdir=infra/terraform init -backend=false
terraform -chdir=infra/terraform validate
terraform -chdir=infra/terraform fmt -check
docker build -f relay/Dockerfile -t remux-relay .
GOOS=linux go build ./infra/lambda/...
```

### Android (run from `android/`)
```bash
cd android
./gradlew :core:test           # pure-JVM unit tests (NO device/emulator needed) — fastest feedback loop
./gradlew assembleDebug        # builds app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug            # fails on lint errors; warnings allowed
./gradlew :core:test --tests "dev.remux.core.terminal.TerminalSgrTest"   # one test class
```
Pinned toolchain (do not bump casually): Gradle 8.10.2, AGP 8.7.2, Kotlin 2.0.21, compileSdk 35, minSdk 26, JVM-17 bytecode. There is no emulator here — app correctness is verified via `:core` JVM tests + compile + lint, not on-device runs.

## Build environment gotchas (this host)

This host is **ARM64 (aarch64) Linux**, which the Android build does not natively support out of the box. `docs/ANDROID_BUILD.md` has the full setup; the essentials, already applied here:

- **Gradle must run with `JAVA_HOME` and `ANDROID_HOME` set**, e.g. `source scripts/android-env.sh` (sets `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64`, `ANDROID_HOME=$HOME/android-sdk`). A bare JRE is not enough — AGP needs a full JDK (`javac`).
- **Google ships only x86_64 `aapt2`/`d8` for Linux.** They run under `qemu-user-static` + amd64 multiarch libs (`libc6:amd64`, `libstdc++6:amd64`, …). Without this, `assembleDebug` fails in `:app:processDebugResources` with `aapt2 … Exec format error`.
- The Gradle wrapper is committed; the SDK path is in `android/local.properties` (gitignored).

## Workflow notes

- The whole MVP was built via a Supergoal run; the plan, phase specs, and live progress log are under `.supergoal/mvp-mobile-ssh-client-prd-md-kzMtj7/` (`ROADMAP.md`, `STATE.md`). Treat these as historical context, not active config.
- Keep terminal-emulator and protocol logic in `:core` (testable); only put Android/SSHJ/OkHttp/Room/Keystore code in `:app`.
- When editing the protocol, change `proto/proto.go`, the Kotlin mirror in `:core`, **and** `docs/PROTOCOL.md` together.

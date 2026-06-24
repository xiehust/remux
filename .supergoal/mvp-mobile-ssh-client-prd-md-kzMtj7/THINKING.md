# THINKING — Remux MVP (v0.1)

## Goals (what "done" means)
Ship the v0.1 MVP of **Remux**, an Android-first mobile SSH client tuned for driving AI coding
agents (Claude Code / Codex) inside remote tmux sessions, across three real components:

1. **Relay server (Go)** — public, content-agnostic byte-pipe that bridges a firewalled device
   to the mobile app over WebSocket. Self-contained single binary; AWS-serverless topology also
   expressed as deploy-ready IaC.
2. **Device agent (Go)** — runs on EC2/MacBook, dials *outbound* to the relay, registers, keeps a
   heartbeat, auto-reconnects, and on a session request forwards bytes to local `sshd` (127.0.0.1:22).
3. **Android app (Kotlin/Compose)** — SSH over the tunnel (SSHJ), self-contained xterm-256color
   terminal, tmux detect/attach, shortcut toolbar, AI-command quick panel, dark theme, encrypted
   key storage.

The differentiated, fully-verifiable core is the **reverse tunnel**: real SSH bytes flow
app → relay → agent → sshd, with tmux attaching a session running a (scripted) AI-agent loop, all
proven by an automated end-to-end test capturing real output.

## Locked decisions (from user intake)
- Scope: build all 3 for real. Backend proven end-to-end with real SSH-over-tunnel + tmux. Android
  SDK installed headless; `./gradlew assembleDebug` + JVM unit tests are the gate (no on-device run).
- Backend stack: **Go** for agent and relay; AWS IaC deploy-ready but **not deployed** (no billing).
- Transport: relay is an **opaque byte pipe**; agent → local `sshd:22`; app speaks **real SSH** over
  the tunnel → end-to-end SSH encryption, relay cannot read content (matches PRD security goals).
- App libs: **SSHJ** (pure-Java SSH, JVM-testable) + **self-contained Kotlin VT parser** (JVM-testable)
  + Jetpack Compose / Material3 + Room + EncryptedSharedPreferences / Android Keystore.

## Architecture (baked into phase specs)

### Wire protocol (shared Go package `proto`, documented in `docs/PROTOCOL.md`)
Control plane = JSON text WS messages; data plane = raw binary WS messages (opaque SSH bytes).
- Agent: connects `GET /agent/control` (WS), sends `register{deviceId,name,token,platform}`; receives
  `open{sessionId}` requests; WS ping/pong = heartbeat; reconnect with exponential backoff.
- App/client: connects `GET /app/control` (WS), sends `auth{token}`, `list{}` → `devices[...]`,
  `open{deviceId}` → `opened{sessionId}`.
- Data plane: **per-session WS pair**. After `open`, both app and agent dial `GET /data?session=S&token=…`.
  The relay pairs the two `/data` conns by sessionId and pipes bytes both ways (content-agnostic).
  The agent's `/data` handler also dials `127.0.0.1:22` and bridges WS ↔ sshd TCP. No multiplex framing
  needed — one WS pair per session keeps the relay a dumb pipe and preserves end-to-end SSH crypto.

### End-to-end test (the killer evidence, phase 4)
Go integration test / shell script: start `sshd` on a high port with a generated host key + authorized
test key → start relay → start agent (pointed at that sshd) → a Go "app" client auths to relay, opens a
session, runs an SSH client (golang.org/x/crypto/ssh) over the `/data` WS, executes `tmux new -d` running a
scripted fake-AI loop + `tmux attach`/`capture-pane`, asserts the real captured markers. Exit 0 = PRD
feature #0 (bridge) + #1 (SSH) + #3 (tmux) proven with real bytes.

### Android module split (maximize off-device test coverage)
- `:core` — pure Kotlin/JVM: VT parser + screen buffer + scrollback, tmux output parsing, ssh_config
  parser, tunnel-protocol client framing, AI command catalog. JUnit-tested via `./gradlew :core:test`
  (no emulator).
- `:app` — Android: Compose UI, Room, Keystore, SSHJ wiring, relay-tunnel loopback bridge. Verified by
  `./gradlew assembleDebug` (real APK) + lint.

### SSHJ-over-tunnel mechanism
App opens a localhost `ServerSocket` (loopback), a bridge thread copies bytes between that socket and the
relay `/data` WebSocket; SSHJ `SSHClient.connect("127.0.0.1", localPort)` runs normal SSH over it.
Bulletproof regardless of SSHJ custom-socket support. (Verified SSHJ API: `loadKeys` supports
ed25519/rsa, `authPublickey`/`authPassword`, `allocatePTY("xterm",cols,rows,..)`+`startShell()`,
`session.exec()` for `tmux ls`.)

### AWS IaC (phase 5, deploy-ready not deployed)
Terraform: API Gateway WebSocket API ($connect/$disconnect/$default → Lambda), DynamoDB device/connection
registry, ECS Fargate service running the containerized Go relay data-pipe, NLB for agent ingress, IAM.
Gate = `terraform validate` + `terraform fmt -check` (no AWS creds needed) + `docker build` of relay image
+ Lambda Go compiles. Maps 1:1 to PRD topology.

## Constraints
- No Android SDK/Gradle/emulator preinstalled → phase 6 installs SDK + bootstraps Gradle wrapper headless.
- No on-device/emulator runtime → app verified by compile + JVM unit tests only (chosen scope).
- No AWS credentials assumed → IaC validated, not applied.
- Pin known-compatible versions to avoid autonomous version-thrash: JDK 21 host, Gradle 8.10.x,
  AGP 8.7.x, Kotlin 2.0.21 (built-in Compose compiler plugin), compileSdk 35, minSdk 26,
  jvmTarget 17, Compose BOM 2024.10.x.
- Network is available (Context7 + web search worked) → Go module + SDK + Gradle downloads will work.

## Risks (top 3) + mitigations
1. **Android SDK install + first Compose build green, fully autonomous** (highest risk). Mitigation:
   isolate in phase 6 *after* the high-confidence backend phases already shipped value; pin exact
   versions; download Gradle once to generate the wrapper; `yes | sdkmanager --licenses`; minimal app
   shell first (assembleDebug must go green before any UI complexity is added in phases 7–9).
2. **SSH-over-WebSocket framing / partial reads corrupting the SSH stream.** Mitigation: relay and
   bridges treat the channel as an ordered byte stream, use binary WS messages + `io.Copy`-style
   streaming (`NextReader`/`NextWriter`), never reframe payloads; phase-4 e2e proves byte fidelity with a
   real SSH handshake (handshake fails instantly if a single byte is dropped/reordered).
3. **tmux/sshd test harness flakiness in CI-less headless env** (port races, missing host key, PAM).
   Mitigation: e2e spins its own `sshd` on an ephemeral high port with `-o` overrides + generated keys,
   no system sshd dependency; retries handled by the 3-strike protocol.

## Non-obvious dependencies / ordering
- `proto` package (phase 1) is the contract for relay (2), agent (3), and the app tunnel client (8).
- e2e (4) needs both relay (2) and agent (3).
- Android SDK bootstrap (6) blocks every later Android phase (7, 8, 9).
- VT parser (7) is consumed by the terminal UI (9); ssh/tunnel logic (8) is consumed by connect UI (9).
- Polish (10) re-runs every prior component's gate → depends on 1..9.

## Memory hits applied
None (empty memory). Will write a `project_remux` memory at the final phase.

## Tools/skills relied on
Context7 (SSHJ, Gorilla, Compose/Room as needed), kiro-web-search (fallback). go/cargo not needed for
Rust (Go chosen). Local tmux/sshd/ssh-keygen for real e2e. Docker for relay image build. Terraform for IaC.

## Best practices applied
- Relay never decrypts SSH (end-to-end crypto); tokens for device + app auth; mTLS noted as v0.2.
- Keys stored via Android Keystore / EncryptedSharedPreferences; never logged.
- Go: context-based shutdown, race detector in tests, exponential backoff with jitter for reconnect.
- Terminal: correct CJK/wide-char width (East Asian Width), 256-color + truecolor SGR, bounded scrollback.
- Pinned, mutually-compatible Android toolchain versions to keep the autonomous build deterministic.

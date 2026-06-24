# Roadmap: Remux — Mobile SSH Client MVP (v0.1)

**Task:** Build the v0.1 MVP of Remux — an Android-first SSH client for driving AI coding agents in remote tmux sessions, including the relay-server + device-agent reverse tunnel, SSH key auth, xterm-256color terminal, tmux detect/attach, shortcut toolbar, AI-command panel, and dark theme.
**Type:** greenfield, ui
**Created:** 2026-06-22
**Total phases:** 10

## Context summary

- **Stack:** Go (relay + agent), Kotlin/Jetpack Compose + SSHJ (Android app), Terraform/Docker (AWS IaC). Monorepo.
- **Package manager:** Go modules (go.work) · Gradle (wrapper, Android) · Terraform · npm n/a.
- **Build / test / lint commands:** `go build ./...`, `go vet ./...`, `gofmt -l`, `go test ./...`, `bash scripts/e2e.sh`, `terraform -chdir=infra/terraform validate`, `docker build`, `./gradlew :core:test`, `./gradlew assembleDebug`, `./gradlew lintDebug`.
- **Risky areas:** Android SDK install + first Compose build (autonomous); SSH-over-WebSocket byte fidelity; headless sshd/tmux e2e harness.

## Assumptions

Non-blocking decisions recorded here so we can proceed without round-trips. If any are wrong, stop the run and tell us:

- The self-hosted Go relay (single binary, WebSocket both legs) is the runnable MVP relay; the PRD's API Gateway+Lambda+Fargate+NLB+DynamoDB topology is delivered as **deploy-ready Terraform + Lambda source + relay Dockerfile**, validated but not deployed (no AWS credentials/billing).
- Both relay legs use WebSocket (agent outbound WSS is more firewall-friendly than raw NLB TCP); the NLB path is represented in IaC for the production topology.
- Android toolchain pinned: JDK 21 host, Gradle 8.10.x, AGP 8.7.x, Kotlin 2.0.21 (built-in Compose compiler), compileSdk 35, minSdk 26, jvmTarget 17, Compose BOM 2024.10.x.
- App verification = compile + JVM unit tests (`:core:test`) + `assembleDebug` APK; no emulator/on-device run (per chosen scope). UI correctness for visual-only items is asserted by structure/compile, not screenshots.
- Host-key verification policy for MVP: trust-on-first-use with fingerprint persistence (documented), not blind PromiscuousVerifier in shipping paths.
- Device + app auth via shared bearer tokens for MVP; mTLS / one-time device tokens noted as v0.2.
- Project name/package: `dev.remux` (app id `dev.remux.app`); Go module root `github.com/remux/remux`.

## Risk top 3

1. **Autonomous Android SDK install + first Compose `assembleDebug` green** — likelihood: med-high, mitigation: isolate in phase 6 after backend value is shipped; pin exact versions; bootstrap Gradle wrapper via one-time Gradle download; `yes | sdkmanager --licenses`; keep the phase-6 app a minimal shell that must assemble before UI grows.
2. **SSH stream corruption over the WebSocket pipe** — likelihood: med, mitigation: binary WS messages, streaming copy, never reframe payloads; phase-4 real SSH handshake fails loudly on any byte loss, so e2e is a strict fidelity gate.
3. **Headless sshd/tmux e2e flakiness** — likelihood: med, mitigation: e2e starts its own sshd on an ephemeral port with generated keys and explicit `-o` overrides (no system service), tmux uses a private socket; 3-strike retry covers transient port races.

## Phase map

| # | Phase | Depends on | Deliverable |
|---|-------|------------|-------------|
| 1 | Scaffold & wire protocol | — | Monorepo + `go.work` + `proto` package + protocol spec, all building |
| 2 | Relay server | 1 | Go relay: register/auth/list/session-pipe/heartbeat, unit-tested |
| 3 | Device agent | 1 | Go agent: outbound register, reconnect, forward to sshd, cross-compiled |
| 4 | End-to-end reverse tunnel | 2, 3 | Automated real SSH-over-tunnel + tmux e2e test, green |
| 5 | AWS infra-as-code | 1 | Terraform (API GW WSS+Lambda+DynamoDB+Fargate+NLB) + Lambda src + relay Dockerfile, validated |
| 6 | Android scaffold + SDK + dark shell | — | SDK installed; Gradle `:app`+`:core`; Compose Material3 dark app shell; `assembleDebug` green |
| 7 | Terminal emulator core | 6 | Pure-Kotlin xterm-256color VT parser + buffer + scrollback, unit-tested |
| 8 | SSH + tunnel + tmux logic | 1, 6, 7 | SSHJ client + relay-tunnel bridge + ssh_config/tmux parsing + data model, tested |
| 9 | Android UI | 6, 7, 8 | Host mgmt, connect (direct+relay), terminal screen, toolbar, AI panel, dark theme |
| 10 | Polish & Harden | 1..9 | Every aspect verified: states, security, a11y, perf, docs, full aggregate gate |

---

## Phase 1 — Scaffold & wire protocol

**Why:** Establish the monorepo, Go workspace, and the shared protocol contract every other backend/app piece depends on.

**Deliverables:**
- Repo layout: `relay/`, `agent/`, `proto/`, `test/`, `infra/`, `android/`, `scripts/`, `docs/`
- `go.work` + per-module `go.mod`; `proto/` Go package with message types + framing helpers
- `docs/PROTOCOL.md` (wire protocol spec) and top-level `README.md`

**Acceptance criteria:**
- [ ] `go build ./...` exits 0 across relay/agent/proto/test modules
- [ ] `go vet ./...` exits 0
- [ ] `gofmt -l .` prints nothing (all Go formatted)
- [ ] `proto` defines control messages (register/auth/list/devices/open/opened/error) with JSON tags + a round-trip encode/decode unit test that passes
- [ ] `docs/PROTOCOL.md` documents control plane, data plane, and the per-session WS-pair design
- [ ] Directory layout above exists

**Mandatory commands:** `go build ./...`, `go vet ./...`, `gofmt -l .`, `go test ./proto/...`

**Evidence required:** command outputs + exit codes; `tree -L 2` (or `find`) of repo; `proto` test pass line.

**Dependencies:** none

---

## Phase 2 — Relay server

**Why:** The public bridge that lets a firewalled device and the app meet without inbound connections.

**Deliverables:**
- `relay/` Go binary: `/agent/control`, `/app/control`, `/data`, `/healthz` WS/HTTP handlers
- In-memory device registry (register/list/heartbeat/expire), token auth, session pairing + bidirectional byte pipe
- Config via flags/env; structured logging; graceful shutdown
- Unit tests for registry, auth, session pairing, and the byte-pipe bridge

**Acceptance criteria:**
- [ ] `go test ./relay/...` passes (registry add/list/remove, auth accept/reject, session pairing, bridge copies bytes both directions)
- [ ] `go test -race ./relay/...` passes (no data races)
- [ ] `go build ./relay/...` produces a binary
- [ ] Relay never inspects/parses data-plane payloads (bridge is byte-transparent — asserted by a binary-fuzz round-trip test)
- [ ] `/healthz` returns 200; unknown token rejected with a control error message
- [ ] Heartbeat: a device missing pongs past the deadline is dropped from the registry (unit-tested with injected clock)

**Mandatory commands:** `go build ./relay/...`, `go vet ./relay/...`, `go test ./relay/...`, `go test -race ./relay/...`

**Evidence required:** test output incl. pairing + byte-pipe tests; `/healthz` + reject-bad-token demonstrated in a test; race run clean.

**Dependencies:** 1

---

## Phase 3 — Device agent

**Why:** The outbound daemon that pierces the firewall and forwards app sessions into local sshd.

**Deliverables:**
- `agent/` Go binary: outbound WS to relay, `register`, heartbeat, exponential-backoff reconnect with jitter
- On `open{sessionId}`: dial relay `/data`, dial `127.0.0.1:22` (configurable), bridge both
- Config file (relay URL, deviceId, name, token, local ssh addr) + flags; cross-platform (linux + darwin)
- Unit tests: reconnect backoff schedule, config load/validate, bridge forwarding via in-memory pipes

**Acceptance criteria:**
- [ ] `go test ./agent/...` passes (backoff schedule bounded + increasing+jittered, config parse/validate, forwarding copies bytes)
- [ ] `go test -race ./agent/...` passes
- [ ] `GOOS=linux go build ./agent/...` AND `GOOS=darwin go build ./agent/...` both succeed (cross-platform)
- [ ] Agent retries after a dropped relay connection (unit-tested: reconnect attempted with increasing delay, capped)
- [ ] Invalid/missing config produces a clear non-zero-exit error message (unit-tested)
- [ ] Agent forwards bytes between a fake relay data conn and a fake local TCP server in both directions (unit-tested)

**Mandatory commands:** `go build ./agent/...`, `go vet ./agent/...`, `go test ./agent/...`, `go test -race ./agent/...`, `GOOS=darwin go build ./agent/...`

**Evidence required:** test output; both cross-compile commands exit 0; backoff + forwarding test names visible.

**Dependencies:** 1

---

## Phase 4 — End-to-end reverse tunnel

**Why:** Prove the differentiated core for real — SSH bytes flowing app→relay→agent→sshd with tmux — not just unit mocks.

**Deliverables:**
- `scripts/e2e.sh` + `test/e2e_test.go` (build-tagged) that: generate keys, start a private `sshd` on an ephemeral port, start relay, start agent, run a Go SSH client through the relay `/data` tunnel
- The SSH session runs `tmux` (private socket): create a detached session running a scripted fake-AI loop, then `capture-pane`/attach and assert markers
- Teardown that kills all spawned processes even on failure

**Acceptance criteria:**
- [ ] `bash scripts/e2e.sh` exits 0
- [ ] Transcript shows a successful SSH handshake completing over the relay tunnel (not a direct dial)
- [ ] Transcript shows `tmux` session created and a captured marker string (e.g. `REMUX_AI_DONE`) printed from inside the tmux pane via the tunnel
- [ ] The relay process logs that it bridged a session **without** logging any SSH/plaintext payload (content-agnostic confirmed)
- [ ] All spawned processes (sshd, relay, agent) are cleaned up after the run (no leftover listeners on the test ports)
- [ ] A negative check: connecting with a wrong token is rejected end-to-end

**Mandatory commands:** `go build ./...`, `bash scripts/e2e.sh`

**Evidence required:** full e2e.sh output incl. handshake line, tmux marker line, bridge-without-payload log line, cleanup confirmation, and the rejected-bad-token line; exit code 0.

**Dependencies:** 2, 3

---

## Phase 5 — AWS infra-as-code

**Why:** Deliver the PRD's production relay topology as deploy-ready, validated infrastructure without incurring AWS cost.

**Deliverables:**
- `infra/terraform/`: API Gateway WebSocket API ($connect/$disconnect/$default → Lambda), DynamoDB device/connection table, ECS Fargate service for the containerized relay data-pipe, NLB for agent ingress, IAM roles, variables/outputs
- `infra/lambda/` Go Lambda source (connect/disconnect/route handlers) that compiles
- `relay/Dockerfile` (multi-stage) producing the relay image; `infra/README.md` deploy guide

**Acceptance criteria:**
- [ ] `terraform -chdir=infra/terraform init -backend=false` succeeds
- [ ] `terraform -chdir=infra/terraform validate` reports success
- [ ] `terraform -chdir=infra/terraform fmt -check` passes (formatted)
- [ ] Terraform defines each PRD component: API GW WSS, Lambda, DynamoDB, ECS Fargate, NLB (grep-verifiable resource types)
- [ ] `docker build -f relay/Dockerfile -t remux-relay .` succeeds and produces an image
- [ ] Lambda Go source compiles: `GOOS=linux go build ./infra/lambda/...` exits 0
- [ ] `infra/README.md` documents the deploy steps and the ~cost estimate from the PRD

**Mandatory commands:** `terraform -chdir=infra/terraform init -backend=false`, `terraform -chdir=infra/terraform validate`, `terraform -chdir=infra/terraform fmt -check`, `docker build -f relay/Dockerfile -t remux-relay .`, `GOOS=linux go build ./infra/lambda/...`

**Evidence required:** terraform validate success line; grep showing the 5 resource types; docker build success; lambda cross-build exit 0.

**Dependencies:** 1

---

## Phase 6 — Android scaffold + SDK + dark shell

**Why:** Stand up a buildable Android project and prove the toolchain works before any UI complexity — the riskiest step, isolated.

**Deliverables:**
- Android SDK installed headless (cmdline-tools, platform-tools, `platforms;android-35`, `build-tools;35.0.0`), licenses accepted, `android/local.properties` with `sdk.dir`
- Gradle wrapper (bootstrapped) + multi-module build: `:app` (Android) and `:core` (pure Kotlin/JVM)
- Compose + Material3 **dark** theme, app shell (single Activity + nav scaffold + placeholder screen)
- Pinned versions (Gradle 8.10.x, AGP 8.7.x, Kotlin 2.0.21, compileSdk 35, minSdk 26)

**Acceptance criteria:**
- [ ] `./gradlew :core:test` runs and passes (even with a trivial seed test)
- [ ] `./gradlew assembleDebug` exits 0 and produces `android/app/build/outputs/apk/debug/app-debug.apk`
- [ ] `./gradlew lintDebug` exits 0 (no lint errors; warnings allowed)
- [ ] App declares a Material3 **dark** color scheme and a Compose entry point (grep-verifiable)
- [ ] `:core` module exists with no Android dependencies (pure Kotlin/JVM) — verified by it building under the `java-library`/`kotlin("jvm")` plugin
- [ ] `android/local.properties` points at the installed SDK; `.gitignore` excludes it and build dirs

**Mandatory commands:** `./gradlew :core:test`, `./gradlew assembleDebug`, `./gradlew lintDebug`

**Evidence required:** SDK install log tail; `assembleDebug` BUILD SUCCESSFUL + `ls` of the produced APK; `:core:test` pass; lint exit 0.

**Dependencies:** none (but in practice run after backend phases; SDK is the gate for 7–9)

**Notes:** Bootstrap path — set `ANDROID_HOME=$HOME/android-sdk`; download `commandlinetools-linux` zip to `$ANDROID_HOME/cmdline-tools/latest`; `yes | sdkmanager --licenses`; `sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"`. Bootstrap the Gradle wrapper by downloading a Gradle 8.10.x distribution once and running `gradle wrapper --gradle-version 8.10.2`, then use `./gradlew`. Keep the app a minimal shell — it MUST assemble before phases 7–9 add complexity. Consult Context7 for current AGP/Kotlin/Compose-BOM compatibility if the first build fails on version alignment.

---

## Phase 7 — Terminal emulator core

**Why:** A correct xterm-256color emulator is the heart of the terminal UX and must be unit-tested off-device.

**Deliverables:**
- `:core` VT parser: ground/escape/CSI/OSC state machine; cursor movement; SGR (bold/underline/reverse, 16/256/truecolor); erase line/screen; scroll regions; tab stops
- Screen buffer (grid of styled cells) + bounded scrollback; resize handling
- UTF-8 decoding + East-Asian-Width-aware wide-char (CJK) cell occupancy
- Comprehensive JUnit tests

**Acceptance criteria:**
- [ ] `./gradlew :core:test` passes with ≥ 25 terminal test cases
- [ ] SGR parsing tested: 16-color, 256-color (`38;5;n`), truecolor (`38;2;r;g;b`), reset, bold/underline/reverse
- [ ] Cursor + erase tested: CUP/CUU/CUD/CUF/CUB, ED, EL behave per xterm
- [ ] Wide-char tested: a CJK char occupies 2 cells; combining marks don't; cursor advances correctly
- [ ] Scrollback tested: lines pushed past the top are retained up to the cap and retrievable
- [ ] Resize tested: shrinking/growing columns reflows the active buffer without crashing
- [ ] No Android imports in `:core` (grep-verifiable)

**Mandatory commands:** `./gradlew :core:test`

**Evidence required:** test summary showing ≥25 passing terminal tests; named tests for SGR/cursor/wide-char/scrollback/resize visible.

**Dependencies:** 6

---

## Phase 8 — SSH + tunnel + tmux logic

**Why:** Wire the app to real SSH over the relay tunnel and the tmux integration, keeping testable logic in `:core`.

**Deliverables:**
- SSHJ-based SSH client wrapper (`:app`): key auth (Ed25519, RSA) + password; PTY shell; `exec` for `tmux ls`; window-resize
- Relay-tunnel transport (`:app`): WebSocket client + localhost loopback bridge so SSHJ runs over the tunnel
- `:core` pure logic: `ssh_config` parser (Host/HostName/Port/User/IdentityFile/ProxyJump), tmux `list-sessions`/`list-windows` output parser, tunnel-protocol client framing, AI-command catalog
- Data model: Room entities (Host, Key, RelayDevice) + DAO; Keystore/EncryptedSharedPreferences key storage wrapper

**Acceptance criteria:**
- [ ] `./gradlew :core:test` passes including: ssh_config parse (incl. ProxyJump), tmux ls/lw parse (sessions, windows, attached flag), tunnel framing round-trip, AI-command catalog contents
- [ ] `./gradlew assembleDebug` exits 0 (SSHJ + tunnel + Room wired, app still assembles)
- [ ] `./gradlew lintDebug` exits 0
- [ ] SSHJ wrapper supports Ed25519, RSA, and password auth paths (grep/compile-verifiable; key loading unit-tested in `:core` where pure)
- [ ] tmux parser handles 0 sessions, N sessions, and the attached marker correctly (unit-tested)
- [ ] Key storage wrapper never writes plaintext private keys to Room/prefs (uses Keystore-backed encryption) — verified by code + a `:core`/contract test where feasible
- [ ] ssh_config parser resolves a host alias to host/port/user/identity (unit-tested)

**Mandatory commands:** `./gradlew :core:test`, `./gradlew assembleDebug`, `./gradlew lintDebug`

**Evidence required:** `:core:test` summary with the named parser/framing tests; `assembleDebug` BUILD SUCCESSFUL; lint exit 0.

**Dependencies:** 1, 6, 7

---

## Phase 9 — Android UI

**Why:** Deliver the user-facing app: manage hosts, connect (direct + via relay), drive the terminal with mobile-optimized input.

**Deliverables:**
- Host list / add / edit screens (Room-backed); key import + Keystore storage; optional biometric unlock
- Connection flow: direct host, and **via-relay device picker** (list online devices from relay, select, connect)
- Terminal screen rendering the `:core` buffer in Compose; orientation + font-size handling
- Customizable shortcut toolbar (Tab, Ctrl, Esc, arrows, Fn, `|`, `/`, `~`, etc.); multi-line input
- AI-command quick panel (`/compact`, `/clear`, `yes`, `no`, …) + input history/templates
- Gestures (pinch-to-zoom font, long-press select/copy); dark theme applied throughout

**Acceptance criteria:**
- [ ] `./gradlew assembleDebug` exits 0 with all screens wired
- [ ] `./gradlew :core:test` and any `:app` unit tests pass
- [ ] `./gradlew lintDebug` exits 0
- [ ] Host CRUD persists via Room (DAO unit/instrumented-where-pure test or compile-verified wiring)
- [ ] Via-relay device picker screen exists and consumes the tunnel client (grep/compile-verifiable)
- [ ] Shortcut toolbar emits the correct control bytes for Tab/Esc/Ctrl-combos/arrows (logic unit-tested in `:core`)
- [ ] AI-command panel inserts the catalog commands; multi-line input supported (compile-verifiable + `:core` catalog test)
- [ ] Dark theme is the default and applied to all screens (grep-verifiable theme wiring)

**Mandatory commands:** `./gradlew :core:test`, `./gradlew assembleDebug`, `./gradlew lintDebug`

**Evidence required:** `assembleDebug` BUILD SUCCESSFUL; list of composable screens added; `:core` toolbar/AI-command test pass lines; lint exit 0.

**Dependencies:** 6, 7, 8

---

## Phase 10 — Polish & Harden

**Why:** Catch what earlier phases missed because they were focused on shipping behavior. This is how "every aspect is perfect" gets enforced.

**Sub-passes (each must produce evidence):**

- [ ] **UX & copy** — all visible strings read well, no debug placeholders; empty/loading/error states for host list, device list, connecting, auth-failed, disconnected
- [ ] **States** — terminal reconnect on drop, relay-offline device shown as offline, key-auth-failure surfaced
- [ ] **Edges** — empty inputs, very long AI prompts, special chars/UTF-8, large scrollback, weak/slow tunnel
- [ ] **Security** — keys only in Keystore/encrypted prefs (no plaintext, grep), relay tokens not logged, host-key TOFU + fingerprint persisted, relay stays content-agnostic, no secrets in repo
- [ ] **A11y** — content descriptions on icon buttons, min touch targets, contrast on dark theme
- [ ] **Perf** — bounded scrollback, no main-thread blocking on SSH I/O, terminal render does not re-alloc the whole grid per frame
- [ ] **Diff review** — no stray debug logs / TODO/FIXME from this run in shipping code
- [ ] **Regression sweep** — full backend test suite + e2e + Android build/test/lint all green together; docs complete

**Mandatory commands:** `go build ./...`, `go vet ./...`, `go test ./...`, `go test -race ./...`, `bash scripts/e2e.sh`, `terraform -chdir=infra/terraform validate`, `./gradlew :core:test`, `./gradlew assembleDebug`, `./gradlew lintDebug`

**Evidence required:**
- One paragraph per sub-pass with what was checked and found/fixed
- A `docs/SECURITY.md` checklist with each item marked
- Cleanliness greps (no debug prints / session TODO/FIXME in shipping code) showing zero hits
- Final aggregate: every mandatory command above with exit code; e2e marker line; `assembleDebug` BUILD SUCCESSFUL
- `docs/` complete: README (run all 3 components), architecture, deploy guide

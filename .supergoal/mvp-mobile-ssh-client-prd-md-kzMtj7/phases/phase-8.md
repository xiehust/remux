SUPERGOAL_PHASE_START
Phase: 8 of 10 — SSH + tunnel + tmux logic
Task: Wire SSHJ-based SSH over the relay tunnel, plus ssh_config/tmux parsing, AI-command catalog, and the Room/Keystore data model.
Type: greenfield
Mandatory commands: ./gradlew :core:test, ./gradlew assembleDebug, ./gradlew lintDebug
Acceptance criteria: 7
Evidence required: :core:test named parser/framing tests, assembleDebug BUILD SUCCESSFUL, lint exit 0
Depends on phases: 1, 6, 7

## Why
Wire the app to real SSH over the relay tunnel and the tmux integration, keeping the testable logic in :core.

## Work
- `:app` SSH client wrapper over **SSHJ**: connect, host-key verification (TOFU + persisted fingerprint), auth via Ed25519 key, RSA key, and password; `allocatePTY("xterm", cols, rows, …)` + `startShell()`; `exec` for `tmux ls`; a window-resize call on rotate/resize. (Verified SSHJ API in planning; consult Context7 for exact method signatures.)
- `:app` relay-tunnel transport: a WebSocket client (OkHttp or java-websocket) to the relay `/app/control` + `/data`, and a **localhost loopback bridge** — open a `ServerSocket` on 127.0.0.1, copy bytes between it and the `/data` WS, and point SSHJ at `127.0.0.1:<localPort>`. Support direct (host:port) and via-relay (deviceId) connection modes.
- `:core` pure logic (all JVM-unit-tested):
  - `ssh_config` parser: Host / HostName / Port / User / IdentityFile / ProxyJump; resolve an alias to an effective config.
  - tmux output parser: `list-sessions` and `list-windows` formats → models (name, windows, attached flag); handle 0 / N sessions.
  - tunnel-protocol client: encode/decode the `proto` control messages (mirror of the Go `proto`) + framing round-trip.
  - AI-command catalog: `/compact`, `/clear`, `yes`, `no`, and the common set, with display label + literal bytes/text.
- Data model (`:app`): Room entities + DAO for `Host`, `SshKey` (metadata only), `RelayDevice`; a key-storage wrapper using Android Keystore / EncryptedSharedPreferences (private key material encrypted, never stored plaintext).

## Acceptance criteria (all must pass — verify each in transcript)
- `./gradlew :core:test` passes including: ssh_config parse (incl. ProxyJump alias resolution), tmux ls/lw parse (0/N sessions + attached flag), tunnel framing round-trip, AI-command catalog contents.
- `./gradlew assembleDebug` exits 0 (SSHJ + tunnel + Room wired, app still assembles).
- `./gradlew lintDebug` exits 0.
- SSHJ wrapper supports Ed25519, RSA, and password auth paths (compile/grep-verifiable).
- tmux parser handles 0 sessions, N sessions, and the attached marker correctly (unit-tested).
- Key-storage wrapper never persists plaintext private keys (Keystore-backed encryption) — verified by code review + a contract test where feasible.
- ssh_config parser resolves a host alias to host/port/user/identity (unit-tested).

## Mandatory commands (run each, surface last ~10 lines + exit code)
- `./gradlew :core:test`
- `./gradlew assembleDebug`
- `./gradlew lintDebug`

## Evidence required in transcript
- `:core:test` summary with the named ssh_config / tmux / framing / AI-catalog tests.
- `assembleDebug` BUILD SUCCESSFUL; `lintDebug` exit 0.

## Notes
Keep as much logic as possible in `:core` (no Android types) so it's JVM-testable; only the SSHJ session, the loopback bridge, Room, and Keystore live in `:app`. The loopback-bridge approach avoids needing SSHJ to accept a custom socket and is robust. Do NOT log key material or tokens. The full connect-flow UI is phase 9 — here, deliver the wired, compiling, tested machinery.

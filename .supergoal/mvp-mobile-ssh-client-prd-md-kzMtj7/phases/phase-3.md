SUPERGOAL_PHASE_START
Phase: 3 of 10 — Device agent
Task: Build the cross-platform Go device agent that dials out to the relay, registers, heartbeats, auto-reconnects, and forwards app sessions into local sshd.
Type: greenfield
Mandatory commands: go build ./agent/..., go vet ./agent/..., go test ./agent/..., go test -race ./agent/..., GOOS=darwin go build ./agent/...
Acceptance criteria: 6
Evidence required: test output (backoff, config, forwarding), both cross-compiles exit 0
Depends on phases: 1

## Why
The outbound daemon that pierces the firewall (NAT) and forwards mobile-app sessions into the host's local sshd.

## Work
- Implement `agent/` Go binary: outbound WS connection to the relay `/agent/control`, send `Register{deviceId,name,token,platform}`, maintain heartbeat (respond to/initiate WS ping-pong).
- Reconnect: on any control-connection drop, reconnect with exponential backoff + jitter, capped (e.g. 1s→2s→4s…→max 30s). Make backoff computation a pure, unit-testable function.
- On receiving `Open{sessionId}` over the control WS: dial relay `GET /data?session=S&token=…` AND dial the local ssh address (`127.0.0.1:22`, configurable), then bridge the two with bidirectional streaming copy. Support multiple concurrent sessions.
- Config: a config file (relay URL, deviceId, name, token, local ssh addr) loaded from a path flag, plus flag overrides. Validate required fields; clear error + non-zero exit on invalid/missing config. Detect platform (`runtime.GOOS`) for the register message.
- Cross-platform: must build for linux and darwin (no OS-specific syscalls that break either).
- Unit tests: backoff schedule (bounded, increasing, jittered within bounds), config load/validate (valid + several invalid cases), bridge forwarding between a fake relay-data conn and a fake local TCP server (bytes copied both directions), reconnect attempted after a simulated drop.

## Acceptance criteria (all must pass — verify each in transcript)
- `go test ./agent/...` passes (backoff, config, forwarding, reconnect).
- `go test -race ./agent/...` passes.
- `GOOS=linux go build ./agent/...` AND `GOOS=darwin go build ./agent/...` both succeed.
- Agent retries after a dropped relay connection — reconnect attempted with increasing, capped delay (unit-tested).
- Invalid/missing config yields a clear error and non-zero exit (unit-tested).
- Agent forwards bytes between a fake relay data conn and a fake local TCP server in both directions (unit-tested).

## Mandatory commands (run each, surface last ~10 lines + exit code)
- `go build ./agent/...`
- `go vet ./agent/...`
- `go test ./agent/...`
- `go test -race ./agent/...`
- `GOOS=darwin go build ./agent/...`

## Evidence required in transcript
- Test output with backoff + forwarding + config-validation test names visible.
- Both `GOOS=linux` and `GOOS=darwin` builds exit 0.

## Notes
Share the `proto` package with the relay for message types. The agent is the only component that touches local sshd; keep that address configurable so the phase-4 e2e can point it at an ephemeral test sshd. Use context for clean shutdown of all sessions.

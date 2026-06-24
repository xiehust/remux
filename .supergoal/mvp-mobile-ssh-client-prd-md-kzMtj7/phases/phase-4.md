SUPERGOAL_PHASE_START
Phase: 4 of 10 — End-to-end reverse tunnel
Task: Prove real SSH bytes flow app→relay→agent→sshd with tmux, via an automated end-to-end test.
Type: greenfield
Mandatory commands: go build ./..., bash scripts/e2e.sh
Acceptance criteria: 6
Evidence required: e2e output (SSH handshake over tunnel, tmux marker, bridge-without-payload log, cleanup, bad-token reject), exit 0
Depends on phases: 2, 3

## Why
Prove the differentiated core for real — SSH bytes flowing app→relay→agent→sshd with tmux — not just unit mocks. This is the single most important evidence artifact of the run.

## Work
- Write `scripts/e2e.sh` (orchestrator) and/or `test/e2e_test.go` (build-tagged `e2e`) that:
  1. Generates an SSH host key and a client keypair into a temp dir; writes an `authorized_keys`.
  2. Starts a private `sshd` on an ephemeral high port with explicit `-o` overrides (own host key, own pid file, `StrictModes no`, `AuthorizedKeysFile` → the temp file, no PAM) — no dependency on the system ssh service.
  3. Starts the relay (random port, test token) and the agent (pointed at `127.0.0.1:<sshd-port>`, same token), waits for the agent to register.
  4. Acts as the "app": auth to relay `/app/control`, `list`, `open` the device, get sessionId, dial `/data`, and run an SSH client (Go `golang.org/x/crypto/ssh`) over that WS stream (via the same loopback-bridge or a direct net.Conn wrapper).
  5. Over SSH: start a detached `tmux` session (private socket `-L`) running a scripted fake-AI loop that eventually echoes a unique marker (e.g. `REMUX_AI_DONE`), then `tmux capture-pane`/attach and read the marker back through the tunnel.
  6. Assert the marker appeared; assert the relay logged a bridged session WITHOUT logging payload bytes.
  7. Negative check: a connection with a wrong token is rejected end-to-end.
  8. Robust teardown (trap): kill sshd/relay/agent and remove temp dirs even on failure; verify no leftover listeners on the test ports.

## Acceptance criteria (all must pass — verify each in transcript)
- `bash scripts/e2e.sh` exits 0.
- Output shows a successful SSH handshake over the relay tunnel (not a direct dial to sshd).
- Output shows a tmux session created and the captured marker (`REMUX_AI_DONE`) read back through the tunnel.
- The relay logs that it bridged a session WITHOUT logging any SSH/plaintext payload (content-agnostic confirmed).
- All spawned processes are cleaned up (no leftover listeners on the test ports after the run).
- A wrong-token connection is rejected end-to-end (negative check passes).

## Mandatory commands (run each, surface last ~10 lines + exit code)
- `go build ./...`
- `bash scripts/e2e.sh`

## Evidence required in transcript
- Full `e2e.sh` output including: the handshake line, the tmux marker line, the bridge-without-payload log line, cleanup confirmation, and the rejected-bad-token line.
- Exit code 0.

## Notes
If the test environment's sshd refuses to start headless, fall back to running sshd as the current non-root user with a fully self-contained config dir (this is the standard CI pattern). The goal is real SSH crypto over the tunnel — do not stub the SSH layer. Keep the script idempotent and re-runnable. This test is also a mandatory command in phase 10.

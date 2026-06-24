SUPERGOAL_PHASE_START
Phase: 2 of 10 — Relay server
Task: Build the Go relay server that registers devices, authenticates the app, lists devices, and bridges per-session byte pipes content-agnostically.
Type: greenfield
Mandatory commands: go build ./relay/..., go vet ./relay/..., go test ./relay/..., go test -race ./relay/...
Acceptance criteria: 6
Evidence required: test output incl pairing + byte-pipe + heartbeat tests, healthz + bad-token reject, race clean
Depends on phases: 1

## Why
The public bridge that lets a firewalled device and the mobile app meet without any inbound connection to the device.

## Work
- Implement `relay/` Go binary using `github.com/gorilla/websocket` (verify current API via Context7 if needed).
- Endpoints: `GET /agent/control` (WS) — agent registers and stays connected to receive `open` requests; `GET /app/control` (WS) — app auths, lists devices, requests `open`; `GET /data?session=S&token=…` (WS) — per-session data pipe (pairs the two ends by sessionId and copies bytes both ways); `GET /healthz` (HTTP 200).
- In-memory device registry: register/list/remove, online status, heartbeat timestamp, expiry of stale devices. Make the clock injectable for tests.
- Token auth: a configured bearer token (flag/env) gates `/agent/control`, `/app/control`, and `/data`. Reject unknown tokens with a control `Error` message (app) / close (data).
- Session manager: on app `Open{deviceId}`, generate a sessionId, notify the target agent's control WS with `Open{sessionId}`, reply `Opened{sessionId}` to the app, and pair the two subsequent `/data` connections; bridge with streaming binary copy (`NextReader`/`NextWriter` or message relay) — never parse payloads.
- Config via flags/env (listen addr, token, heartbeat timeout); structured logging (log session-bridged events but NOT payload bytes); graceful shutdown on SIGINT/SIGTERM with context.
- Unit tests: registry add/list/remove/expire (injected clock), auth accept/reject, session pairing, bidirectional byte-pipe bridge (including a binary-fuzz round-trip asserting byte-for-byte transparency).

## Acceptance criteria (all must pass — verify each in transcript)
- `go test ./relay/...` passes (registry, auth, pairing, bridge).
- `go test -race ./relay/...` passes — no data races.
- `go build ./relay/...` produces a binary.
- The data-plane bridge is byte-transparent: a binary-fuzz round-trip test passes byte-for-byte (relay does not inspect/alter payloads).
- `/healthz` returns 200 and an unknown token is rejected with a control error (tested).
- Heartbeat: a device that misses pongs past the deadline is dropped from the registry (tested with the injected clock).

## Mandatory commands (run each, surface last ~10 lines + exit code)
- `go build ./relay/...`
- `go vet ./relay/...`
- `go test ./relay/...`
- `go test -race ./relay/...`

## Evidence required in transcript
- Test output showing pairing, byte-pipe transparency, and heartbeat-expiry test PASS lines.
- A test demonstrating `/healthz` 200 and bad-token rejection.
- `go test -race` clean.

## Notes
The relay must NEVER decrypt or parse SSH — it only moves opaque bytes. Logging payload content is a security failure and will be flagged in phase 10. Use binary WebSocket messages for the data plane. Keep handlers concurrency-safe (mutex-guarded registry / sync.Map).

SUPERGOAL_PHASE_START
Phase: 1 of 10 — Scaffold & wire protocol
Task: Stand up the Remux monorepo, Go workspace, and the shared relay/agent/app wire-protocol contract.
Type: greenfield
Mandatory commands: go build ./..., go vet ./..., gofmt -l ., go test ./proto/...
Acceptance criteria: 6
Evidence required: build/vet/gofmt outputs+exit codes, repo tree, proto round-trip test pass
Depends on phases: none

## Why
Establish the monorepo, Go workspace, and the shared protocol contract every other backend and app piece depends on.

## Work
- Create repo layout: `relay/`, `agent/`, `proto/`, `test/`, `infra/`, `android/`, `scripts/`, `docs/`.
- Initialize Go workspace: top-level `go.work`; module path root `github.com/remux/remux` with per-component modules (`proto`, `relay`, `agent`, `test`) wired into the workspace. Use Go 1.25.
- Implement `proto/` package: control-plane message types with JSON tags — `Register{DeviceID,Name,Token,Platform}`, `Auth{Token}`, `List{}`, `Devices{[]DeviceInfo}`, `Open{DeviceID}`/`Open{SessionID}` (agent variant), `Opened{SessionID}`, `Error{Msg}`. Include an envelope with a `type` discriminator and `Encode`/`Decode` helpers. Document the per-session data-plane WS-pair design (no payload framing — raw binary).
- Write `docs/PROTOCOL.md` describing control plane (JSON over WS), data plane (raw binary WS, per-session pair), endpoints (`/agent/control`, `/app/control`, `/data`, `/healthz`), auth model, heartbeat (WS ping/pong), reconnect.
- Write top-level `README.md` (project overview, component map, how to build each, how to run e2e — stub forward-references ok).
- Add `.gitignore` (Go binaries, Android build dirs, `local.properties`, terraform `.terraform/`, `*.tfstate`).
- `gofmt` all Go sources.

## Acceptance criteria (all must pass — verify each in transcript)
- `go build ./...` exits 0 across all modules.
- `go vet ./...` exits 0.
- `gofmt -l .` prints nothing.
- `proto` defines the control messages above with JSON tags and an `Encode`/`Decode` round-trip unit test that passes (`go test ./proto/...`).
- `docs/PROTOCOL.md` exists and documents control plane, data plane, endpoints, and the per-session WS-pair design.
- The directory layout (`relay/ agent/ proto/ test/ infra/ android/ scripts/ docs/`) exists.

## Mandatory commands (run each, surface last ~10 lines + exit code)
- `go build ./...`
- `go vet ./...`
- `gofmt -l .`
- `go test ./proto/...`

## Evidence required in transcript
- Exit codes + tail output for each mandatory command.
- `find . -maxdepth 2 -type d -not -path './.git/*' -not -path './.supergoal/*'` showing the layout.
- The `proto` round-trip test name + PASS line.

## Notes
Keep modules minimal but real. If `go.work` + multi-module is fussy, a single module rooted at repo top with `proto`/`relay`/`agent`/`test` as packages is acceptable as long as `go build ./...` and the per-package tests work — but prefer the workspace so `relay` and `agent` can later have their own deps. Consult Context7 for `go.work` usage if needed.

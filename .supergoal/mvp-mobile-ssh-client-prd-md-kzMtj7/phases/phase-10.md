SUPERGOAL_PHASE_START
Phase: 10 of 10 — Polish & Harden
Task: Verify every aspect — states, edges, security, a11y, perf, docs — and run the full aggregate gate across all three components.
Type: greenfield, ui
Mandatory commands: go build ./..., go vet ./..., go test ./..., go test -race ./..., bash scripts/e2e.sh, terraform -chdir=infra/terraform validate, ./gradlew :core:test, ./gradlew assembleDebug, ./gradlew lintDebug
Acceptance criteria: 8
Evidence required: per-sub-pass paragraph, docs/SECURITY.md checklist, cleanliness greps zero hits, full aggregate exit codes, e2e marker, assembleDebug SUCCESS
Depends on phases: 1,2,3,4,5,6,7,8,9

## Why
Catch what earlier phases missed because they were focused on shipping behavior. This is how "every aspect is perfect" gets enforced.

## Work / Sub-passes (each must produce evidence)
- **UX & copy**: every visible string reads well, no debug placeholders. Empty/loading/error states for host list, relay device list, connecting, auth-failed, disconnected.
- **States**: terminal reconnect on tunnel drop; relay-offline device shown as offline; key-auth failure surfaced with a clear message.
- **Edges**: empty inputs, very long AI prompts, special chars/UTF-8/CJK in terminal, large scrollback, slow/weak tunnel.
- **Security** (write `docs/SECURITY.md` checklist): private keys only in Keystore/encrypted prefs (grep for plaintext key persistence → zero), relay/app tokens never logged, host-key TOFU + persisted fingerprint, relay stays content-agnostic (no payload logging — re-confirm from phase 2/4), no secrets committed to the repo.
- **A11y**: content descriptions on icon buttons, ≥48dp touch targets, dark-theme contrast.
- **Perf**: bounded scrollback, SSH I/O off the main thread, terminal render does not reallocate the whole grid per frame.
- **Diff review**: no stray debug logs / TODO / FIXME from this run in shipping code (Go `fmt.Println` debug, Kotlin `println`/`Log.d` left in, etc.).
- **Docs**: `README.md` (build + run all 3 components, run e2e), `docs/ARCHITECTURE.md`, `infra/README.md` deploy guide complete and accurate.
- **Regression sweep**: run the full aggregate of mandatory commands; everything green together.

## Acceptance criteria (all must pass — verify each in transcript)
- All Go gates pass: `go build ./...`, `go vet ./...`, `go test ./...`, `go test -race ./...` exit 0.
- `bash scripts/e2e.sh` exits 0 and still shows the tmux marker over the tunnel.
- `terraform -chdir=infra/terraform validate` succeeds.
- `./gradlew :core:test`, `./gradlew assembleDebug`, `./gradlew lintDebug` all exit 0.
- `docs/SECURITY.md` exists with every checklist item addressed; cleanliness greps for debug prints and TODO/FIXME in shipping code return zero hits (or each remaining hit is justified in the transcript).
- No plaintext private-key persistence and no token/payload logging anywhere (grep-verified).
- `README.md`, `docs/ARCHITECTURE.md`, `infra/README.md` are complete (build/run/deploy all documented).
- A final `git status` / file inventory confirms all roadmap deliverables are present.

## Mandatory commands (run each, surface last ~10 lines + exit code)
- `go build ./...`
- `go vet ./...`
- `go test ./...`
- `go test -race ./...`
- `bash scripts/e2e.sh`
- `terraform -chdir=infra/terraform validate`
- `./gradlew :core:test`
- `./gradlew assembleDebug`
- `./gradlew lintDebug`

## Evidence required in transcript
- One paragraph per sub-pass (UX/copy, states, edges, security, a11y, perf, diff-review, docs) with what was checked and found/fixed.
- `docs/SECURITY.md` checklist with each item marked.
- Cleanliness greps (debug prints, TODO/FIXME in shipping code) showing zero hits or justified exceptions.
- Final aggregate: every mandatory command with exit code; the e2e marker line; `assembleDebug` BUILD SUCCESSFUL.

## Notes
This phase ships nothing new of substance — it verifies and hardens. If a check fails, fix it here (within scope) rather than expanding features. Write the final `project_remux` memory (location, stack, status, ROADMAP link) per the protocol. Do not declare done until every mandatory command above is green in the same run.

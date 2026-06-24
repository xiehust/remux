# State: Remux — Mobile SSH Client MVP (v0.1)

**Status:** COMPLETE
**Current phase:** — (complete)
**Started:** 2026-06-22
**Last update:** 2026-06-22
**Run root:** .supergoal/mvp-mobile-ssh-client-prd-md-kzMtj7
**Baseline ref:** no-git    <!-- repo has no commits yet; deliverable/cleanliness checks treat all files as new -->


## Phase progress

| # | Phase | Status | Started | Completed | Notes |
|---|-------|--------|---------|-----------|-------|
| 1 | Scaffold & wire protocol | done | 2026-06-22 | 2026-06-22 | proto+go.work+docs, go build/vet/test green |
| 2 | Relay server | done | 2026-06-22 | 2026-06-22 | gorilla/ws relay, 12 tests, race-clean |
| 3 | Device agent | done | 2026-06-22 | 2026-06-22 | outbound register+backoff+forward, 13 tests, linux+darwin |
| 4 | End-to-end reverse tunnel | done | 2026-06-22 | 2026-06-22 | REAL SSH+tmux over tunnel, e2e.sh exit 0, content-agnostic |
| 5 | AWS infra-as-code | done | 2026-06-22 | 2026-06-22 | TF validate ok (5 resources), docker img, lambda builds |
| 6 | Android scaffold + SDK + dark shell | done | 2026-06-22 | 2026-06-22 | SDK+qemu, assembleDebug APK 9MB, :core:test, lint green |
| 7 | Terminal emulator core | done | 2026-06-22 | 2026-06-22 | VT parser+buffer+scrollback, 50 :core tests green |
| 8 | SSH + tunnel + tmux logic | done | 2026-06-22 | 2026-06-22 | SSHJ+tunnel+Room+Keystore, :core 75 tests, assembleDebug green |
| 9 | Android UI | done | 2026-06-22 | 2026-06-22 | host mgmt+connect+terminal+toolbar+AI panel, APK 22.6M, 83 :core tests |
| 10 | Polish & Harden | done | 2026-06-22 | 2026-06-22 | docs+security+states; full aggregate gate green |

## Engineering check status

Updated by each phase as it runs. Cleared at the start of the next phase, so this always reflects the **most recent** engineering check.

- Build: pass (go + APK)
- Typecheck: pass (go vet + kotlin)
- Lint: pass (gofmt + lintDebug + tf fmt)
- Tests: pass (Go ok+race, :core 83, e2e marker, tf valid)

## Notable events

Append-only log of anything noteworthy that happened during execution.

- 2026-06-22 — Plan locked, 10 phases. Stack: Go relay+agent, Kotlin/Compose+SSHJ app, Terraform/Docker IaC. Decisions: all-3 scope, SDK installed, real-SSH-over-dumb-relay, SSHJ+custom VT parser.
- 2026-06-22 — Pre-flight red (expected greenfield): all mandatory commands fail because the source tree does not exist yet (no go.mod/android/scripts/infra). Not a broken baseline — phase 1 creates the tree.

## Failure log

If a phase hits FAILURE_PROBE, record it here:

- Phase 6 (Android scaffold): assembleDebug failed twice during build — (1) JRE had no javac → installed openjdk-21-jdk-headless; (2) x86_64 aapt2/d8 can't run on aarch64 → installed qemu-user-static + amd64 multiarch libs. Both fixed inline; APK builds. See docs/ANDROID_BUILD.md + memory android-build-on-aarch64.

- 2026-06-22 — Phase 1 done: monorepo + go.work + proto contract + docs/PROTOCOL.md. All Go gates green.

- 2026-06-22 — Phase 2 done: Go relay (gorilla/websocket) — registry+auth+session pairing+content-agnostic byte bridge. 12 tests, race-clean.

- 2026-06-22 — Phase 3 done: Go device agent — register, exp-backoff reconnect, session forward to local sshd. 13 tests, race-clean, cross-compiles.

- 2026-06-22 — Phase 4 done: e2e reverse tunnel GREEN. Real SSH handshake + tmux REMUX_AI_DONE marker read back through relay; relay content-agnostic (byte counts only); bad token rejected; clean teardown.

- 2026-06-22 — Phase 5 done: AWS IaC (Terraform API GW WSS+Lambda+DynamoDB+Fargate+NLB) validates; relay Dockerfile builds 7.9MB image; Go Lambda cross-builds. Not deployed.

- 2026-06-22 — Phase 6 done: Android SDK + Gradle wrapper + :app/:core Compose Material3 dark shell. assembleDebug -> APK (9MB), :core:test + lintDebug green. Host fixed for aarch64 (JDK+qemu+amd64 libs).

- 2026-06-22 — Phase 7 done: xterm-256color VT emulator in :core (SGR/cursor/erase/CJK-width/scrollback/resize). 50 terminal unit tests green, pure-JVM.

- 2026-06-22 — Phase 8 done: SSHJ session wrapper (ed25519/rsa/pw, TOFU host key), OkHttp relay client + loopback TunnelBridge, ssh_config/tmux/protocol/AI in :core (75 tests), Room + EncryptedSharedPreferences key store. assembleDebug+lint green.

- 2026-06-22 — Phase 9 done: Compose UI — host list/edit, relay device picker, terminal renderer (TerminalView+AnsiPalette), shortcut toolbar, AI command panel, multi-line input, dark theme. KeyMapping in :core (8 tests). assembleDebug+lint green.

- 2026-06-22 — Phase 10 done: docs (ARCHITECTURE/SECURITY), UX states, security+cleanliness greps clean. Full aggregate gate GREEN: go build/vet/test/-race, e2e.sh, tf validate, :core:test+assembleDebug+lintDebug.

- 2026-06-22 — FINAL AUDIT round 1 clean: all mandatory commands re-run green; 17/17 deliverables present; criteria spot-checks pass. AUDIT_COMPLETE. Coverage 92% (7 on-device/visual items trust-prior by chosen scope).

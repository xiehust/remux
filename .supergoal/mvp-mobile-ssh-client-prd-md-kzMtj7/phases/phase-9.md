SUPERGOAL_PHASE_START
Phase: 9 of 10 — Android UI
Task: Build the user-facing app — host management, connect (direct + via relay), terminal screen, shortcut toolbar, AI-command panel, dark theme.
Type: greenfield, ui
Mandatory commands: ./gradlew :core:test, ./gradlew assembleDebug, ./gradlew lintDebug
Acceptance criteria: 8
Evidence required: assembleDebug BUILD SUCCESSFUL, screen list, :core toolbar/AI tests, lint exit 0
Depends on phases: 6, 7, 8

## Why
Deliver the user-facing app: manage hosts, connect (direct + via relay), and drive the terminal with mobile-optimized input — the product the user actually touches.

## Work
- Host management screens (Compose): list, add, edit, delete — Room-backed. Key import (paste/file) stored via the Keystore wrapper; optional biometric unlock gate (BiometricPrompt) on app open / key use.
- Connection flow: pick a saved host → connect directly; OR pick **via relay** → fetch online devices from the relay, show a device picker, connect through the tunnel. Connection status UI (connecting / connected / error / disconnected).
- Terminal screen: a Compose renderer drawing the `:core` screen buffer (monospace grid, colors, cursor); handles orientation changes and font-size; auto-detect tmux on connect and offer attach (list sessions → attach).
- Shortcut toolbar: customizable row(s) of keys — Tab, Ctrl (sticky modifier), Esc, arrows, Fn, and symbols (`|`, `/`, `~`, `-`); each emits the correct control byte(s) into the SSH session. The byte-mapping logic lives in `:core` and is unit-tested.
- AI-command quick panel: chips for `/compact`, `/clear`, `yes`, `no`, etc. that insert into the multi-line input; multi-line prompt input; input history / templates.
- Gestures: pinch-to-zoom font size, long-press to select/copy. Dark theme applied throughout (default).

## Acceptance criteria (all must pass — verify each in transcript)
- `./gradlew assembleDebug` exits 0 with all screens wired.
- `./gradlew :core:test` and any `:app` unit tests pass.
- `./gradlew lintDebug` exits 0.
- Host CRUD persists via Room (DAO test where pure, or compile-verified wiring + a `:core` model test).
- The via-relay device-picker screen exists and consumes the tunnel client (grep/compile-verifiable).
- Shortcut toolbar emits the correct control bytes for Tab/Esc/Ctrl-combos/arrows (logic unit-tested in `:core`).
- AI-command panel inserts the catalog commands and multi-line input is supported (compile-verifiable + `:core` catalog test).
- Dark theme is the default and applied to all screens (grep-verifiable theme wiring).

## Mandatory commands (run each, surface last ~10 lines + exit code)
- `./gradlew :core:test`
- `./gradlew assembleDebug`
- `./gradlew lintDebug`

## Evidence required in transcript
- `assembleDebug` BUILD SUCCESSFUL.
- A list of the Composable screens/files added.
- `:core` toolbar key-mapping + AI-command test PASS lines.
- `lintDebug` exit 0.

## Notes
Control-byte mapping (e.g. Ctrl-C → 0x03, arrows → CSI sequences, Tab → 0x09, Esc → 0x1b) is pure logic → put it in `:core` and unit-test it; the Compose toolbar just calls it. Keep the terminal renderer efficient (don't reallocate the grid each frame). Since there's no emulator here, correctness of visual-only items is asserted via compile + the underlying `:core` logic tests. Consult Context7 for current Compose/Material3/Room/BiometricPrompt APIs as needed.

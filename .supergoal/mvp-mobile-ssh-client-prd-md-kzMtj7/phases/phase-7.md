SUPERGOAL_PHASE_START
Phase: 7 of 10 — Terminal emulator core
Task: Implement a pure-Kotlin xterm-256color VT parser + screen buffer + scrollback in :core, unit-tested off-device.
Type: greenfield, ui
Mandatory commands: ./gradlew :core:test
Acceptance criteria: 7
Evidence required: :core:test summary ≥25 terminal tests, named SGR/cursor/wide-char/scrollback/resize tests
Depends on phases: 6

## Why
A correct xterm-256color emulator is the heart of the terminal UX and must be unit-tested off-device.

## Work
- In `:core`, implement a VT/ANSI parser as a state machine: ground / escape / CSI (parameters + intermediates) / OSC states. Handle C0 controls (BS, HT, LF, CR, BEL).
- Screen model: grid of styled cells (char, fg, bg, attrs: bold/underline/reverse/italic). Cursor with save/restore. Bounded scrollback ring buffer with a cap. Scroll regions (DECSTBM). Tab stops.
- SGR: reset, bold, underline, reverse; 16 ANSI colors; 256-color (`38;5;n` / `48;5;n`); truecolor (`38;2;r;g;b`).
- Cursor + erase ops: CUP/HVP, CUU/CUD/CUF/CUB, ED (0/1/2), EL (0/1/2), line insert/delete (optional), index/reverse-index.
- UTF-8 decoder feeding the parser; East-Asian-Width table so CJK/full-width chars occupy 2 cells and combining marks occupy 0; cursor advances by the correct width.
- Resize: changing columns/rows reflows or clamps the active buffer without crashing; scrollback preserved.
- JUnit tests (≥25 cases) covering all the above; keep everything pure Kotlin (no Android).

## Acceptance criteria (all must pass — verify each in transcript)
- `./gradlew :core:test` passes with ≥ 25 terminal test cases.
- SGR tested: 16-color, 256-color, truecolor, reset, bold/underline/reverse.
- Cursor + erase tested: CUP/CUU/CUD/CUF/CUB, ED, EL behave per xterm.
- Wide-char tested: a CJK char occupies 2 cells; a combining mark occupies 0; cursor advances correctly.
- Scrollback tested: lines pushed past the top are retained up to the cap and retrievable.
- Resize tested: shrinking/growing columns adjusts the active buffer without crashing.
- No Android/androidx imports in `:core` (grep-verifiable).

## Mandatory commands (run each, surface last ~10 lines + exit code)
- `./gradlew :core:test`

## Evidence required in transcript
- `:core:test` summary showing ≥25 passing terminal tests.
- Named tests for SGR / cursor / wide-char / scrollback / resize visible in the output (use `--info` or a test report tail if names aren't in default output).

## Notes
This is self-contained algorithmic work — high-confidence and fully JVM-testable. Use a compact East-Asian-Width range table (don't pull a heavy dependency). Model colors as an enum/int union so truecolor and indexed colors coexist. The Compose renderer that draws this buffer is built in phase 9; keep rendering OUT of `:core`.

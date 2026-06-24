SUPERGOAL_PHASE_START
Phase: 6 of 10 — Android scaffold + SDK + dark shell
Task: Install the Android SDK headless, stand up a Gradle :app + :core multi-module Compose project with a Material3 dark theme, and prove assembleDebug is green.
Type: greenfield, ui
Mandatory commands: ./gradlew :core:test, ./gradlew assembleDebug, ./gradlew lintDebug
Acceptance criteria: 6
Evidence required: SDK install log tail, assembleDebug BUILD SUCCESSFUL + APK ls, :core:test pass, lint exit 0
Depends on phases: none

## Why
Stand up a buildable Android project and prove the toolchain works before any UI complexity — the riskiest step, isolated so a failure here doesn't block the already-shipped backend.

## Work
- Install the Android SDK headless into `$HOME/android-sdk` (set `ANDROID_HOME`/`ANDROID_SDK_ROOT`):
  download `commandlinetools-linux` zip → `$ANDROID_HOME/cmdline-tools/latest/`; `yes | sdkmanager --licenses`;
  `sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"`.
- Create the Android project under `android/`: settings.gradle(.kts) with modules `:app` and `:core`.
  - `:core` = pure Kotlin/JVM (`kotlin("jvm")` or `java-library` + kotlin) — NO Android deps. Add JUnit + a trivial seed test.
  - `:app` = Android application (`com.android.application` + `org.jetbrains.kotlin.android` + Compose).
- Bootstrap the Gradle wrapper: download a Gradle 8.10.x distribution once, run `gradle wrapper --gradle-version 8.10.2`, commit `gradlew`, `gradlew.bat`, `gradle/wrapper/*`. Thereafter use `./gradlew`.
- Pin versions: Gradle 8.10.x, AGP 8.7.x, Kotlin 2.0.21 (with `org.jetbrains.kotlin.plugin.compose`), compileSdk 35, minSdk 26, targetSdk 35, jvmTarget 17, Compose BOM 2024.10.x, Material3.
- App shell: single `MainActivity` (ComponentActivity) hosting Compose, a `RemuxTheme` with a Material3 **dark** color scheme as default, a nav scaffold (e.g. Navigation-Compose or a simple state machine) with a placeholder Home screen showing the app name/tagline.
- `android/local.properties` with `sdk.dir`; ensure `.gitignore` excludes `local.properties`, `**/build/`, `.gradle/`.
- Application id `dev.remux.app`.

## Acceptance criteria (all must pass — verify each in transcript)
- `./gradlew :core:test` runs and passes (seed test green).
- `./gradlew assembleDebug` exits 0 and produces `android/app/build/outputs/apk/debug/app-debug.apk`.
- `./gradlew lintDebug` exits 0 (lint errors fail; warnings allowed).
- The app declares a Material3 **dark** color scheme and a Compose entry point (grep-verifiable).
- `:core` has NO Android dependencies (pure Kotlin/JVM) — verified by its build plugin and the absence of `android`/`androidx` imports.
- `android/local.properties` points at the installed SDK; `.gitignore` excludes it and build dirs.

## Mandatory commands (run each, surface last ~10 lines + exit code)
- `./gradlew :core:test`
- `./gradlew assembleDebug`
- `./gradlew lintDebug`

## Evidence required in transcript
- Tail of the SDK install (sdkmanager) confirming platform-35 + build-tools installed.
- `assembleDebug` BUILD SUCCESSFUL line + `ls -la` of the produced `app-debug.apk`.
- `:core:test` PASS summary; `lintDebug` exit 0.

## Notes
Run all gradle commands from `android/` (or `./gradlew -p android …`). If the FIRST assembleDebug fails on version alignment (AGP/Kotlin/Compose/Gradle/JDK), consult Context7 for the current compatible matrix and adjust the pinned versions — do not pile on workarounds. Keep this app a MINIMAL shell; phases 7–9 add the real UI. The 3-strike protocol covers transient download flakiness — re-run on network hiccups. Memory-write the working version matrix at phase end (future runs benefit).

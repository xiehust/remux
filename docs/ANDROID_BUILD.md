# Building the Remux Android app

The app is a standard Gradle + Jetpack Compose project under `android/`. On a
typical x86_64 Linux or macOS machine with a JDK 17–21 and the Android SDK, the
usual commands just work:

```bash
cd android
./gradlew :core:test       # pure-JVM unit tests (no device/emulator)
./gradlew assembleDebug    # builds app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug
```

Pinned toolchain: Gradle 8.10.2, AGP 8.7.2, Kotlin 2.0.21, compileSdk 35,
minSdk 26, JDK 21 (targeting JVM 17 bytecode). The Gradle wrapper is committed.

## One-time host setup

```bash
# 1. Android SDK (command-line tools), then:
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
# 2. A full JDK (javac required — a JRE is not enough):
sudo apt-get install -y openjdk-21-jdk-headless
# 3. Point the build at the SDK:
echo "sdk.dir=$HOME/android-sdk" > android/local.properties
```

## ARM64 (aarch64) Linux build hosts

Google ships the Android build tools (`aapt2`, `d8`, …) only as **x86_64**
binaries for Linux. On an ARM64 host they cannot run natively, so the build
fails in `:app:processDebugResources` with an `aapt2 … Exec format error` /
`Syntax error: "(" unexpected`. Fix it once by enabling x86_64 emulation:

```bash
# qemu + binfmt so the kernel transparently runs x86_64 binaries:
sudo apt-get install -y qemu-user-static binfmt-support

# amd64 multiarch runtime libs the x86_64 tools link against:
sudo dpkg --add-architecture amd64
# (ensure your arm64 apt sources are pinned to `Architectures: arm64`, and add an
#  amd64 source pointing at archive.ubuntu.com / security.ubuntu.com)
sudo apt-get update
sudo apt-get install -y libc6:amd64 libstdc++6:amd64 zlib1g:amd64 libgcc-s1:amd64
```

Verify: `"$ANDROID_HOME/build-tools/35.0.0/aapt2" version` should print a version
(running under qemu). Then `./gradlew assembleDebug` succeeds.

`scripts/android-env.sh` exports `ANDROID_HOME` / `JAVA_HOME` for convenience.

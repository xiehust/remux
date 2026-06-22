#!/usr/bin/env bash
# android-env.sh — environment for building the Remux Android app on this host.
#
# Source it before running Gradle:
#     source scripts/android-env.sh && (cd android && ./gradlew assembleDebug)
#
# Notes for this aarch64 (ARM64) Linux build host:
#   * A full JDK (with javac) is required — a JRE alone fails AGP's Java compile.
#   * Google ships only x86_64 aapt2/d8 for Linux, so the x86_64 build tools run
#     under qemu-user-static (binfmt) with amd64 multiarch runtime libs present.
#     See docs/ANDROID_BUILD.md for the one-time host setup.
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-arm64}"
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin"

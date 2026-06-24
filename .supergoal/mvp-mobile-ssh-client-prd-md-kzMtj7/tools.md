# Detected tools (this session)

## Build/runtime toolchains
- Java: OpenJDK 21.0.11
- Node: v22.19.0, npm 10.9.3 (no pnpm)
- Go: 1.25.3 (linux/arm64)
- Rust: cargo 1.92.0
- Python: 3.12.3
- Docker: 28.4.0
- AWS CLI: 2.35.1; Terraform present; CDK present; SAM present

## NOT available
- Android SDK / Gradle / kotlinc — ANDROID_HOME unset. Full APK build NOT possible without installing SDK.
- No Android device / emulator (headless Linux server, arm64).

## Useful for end-to-end testing
- tmux, ssh, sshd, ssh-keygen all present → can run REAL reverse-tunnel SSH + tmux locally.

## MCP / research tools
- Context7 (mcp__context7__*, mcp__plugin_context7_context7__*) — AVAILABLE for lib docs.
- kiro-web-search (mcp__plugin_kiro-web-search_default__search) — AVAILABLE (preferred for web search).
- codegraph — available but repo is empty (greenfield).

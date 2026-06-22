# Remux security model & checklist

Remux moves SSH sessions between a phone and a firewalled dev host through a
public relay. The threat model assumes the relay can be observed or compromised,
so the design keeps secrets off the relay and encrypted on the device.

## Checklist (verified for the v0.1 MVP)

- [x] **End-to-end SSH encryption.** A standard SSH connection runs *inside* the
  relay tunnel. The relay only ever moves opaque bytes — it never decrypts or
  parses session content. Proven by `scripts/e2e.sh`, which asserts the relay
  logs a bridged session with **byte counts only** and never the SSH payload.
- [x] **Relay is content-agnostic.** `relay/session.go` copies binary messages
  verbatim (`bridge`/`copyMessages`); a byte-fuzz round-trip test
  (`TestBridgeByteTransparency`) proves no inspection or mutation.
- [x] **Private keys encrypted at rest.** SSH private keys are stored only via
  `SecureKeyStore` (Jetpack Security `EncryptedSharedPreferences`, AES-256, master
  key in the Android Keystore). The Room `HostEntity` stores only a `keyAlias`,
  never key material. No plaintext key is written to Room, plain prefs, or logs.
- [x] **No secret logging.** The relay logs session ids + byte counts; the agent
  logs device id, never the token; key/token values are never passed to loggers.
- [x] **Host-key trust-on-first-use.** `TofuHostKeyVerifier` pins the host key
  fingerprint on first connect and rejects mismatches thereafter (MITM defense).
  The shipping client never blindly accepts unknown keys after the first pin.
- [x] **Token-gated relay.** All relay endpoints (`/agent/control`,
  `/app/control`, `/data`) require a shared bearer token (constant-time compared
  in `relay/server.go`). Unknown tokens are rejected — verified end-to-end.
- [x] **No secrets committed.** No real tokens/keys in the repo. The Terraform
  `relay_token` is a `sensitive` variable with an empty default (set via
  `TF_VAR_relay_token`). The only literal tokens in the tree are throwaway test
  fixtures in `*_test.go` / `scripts/e2e.sh`, generated or clearly non-production.
- [x] **Biometric unlock (optional).** `USE_BIOMETRIC` permission is declared for
  gating key use; the encrypted key store is the source of truth.

## Hardening roadmap (post-MVP)

- mTLS on the relay↔agent leg, and per-device one-time registration tokens
  (instead of a single shared bearer token).
- An optional E2E encryption layer *outside* SSH so a compromised relay cannot
  even observe connection metadata/timing.
- Certificate-pinned WSS to the relay.

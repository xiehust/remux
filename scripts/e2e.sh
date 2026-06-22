#!/usr/bin/env bash
# e2e.sh — end-to-end reverse-tunnel test.
#
# Proves real SSH bytes flow: app -> relay -> agent -> sshd, with tmux running a
# scripted fake-AI loop inside the tunneled SSH session. Everything is
# self-contained: a private sshd on an ephemeral port with generated keys, the
# relay and agent as real built binaries, and a Go "app" client that speaks SSH
# over the relay data tunnel.
#
# Exit 0 = the tunnel works end to end and the marker was read back through it.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="$PATH:/usr/local/go/bin"
TMP="$(mktemp -d)"
BIN="$TMP/bin"
mkdir -p "$BIN"
TOKEN="e2e-shared-token-$$"
SSHD="/usr/sbin/sshd"
USER_NAME="$(whoami)"
MARKER="REMUX_AI_DONE"

SSHD_PID="" RELAY_PID="" AGENT_PID=""

log()  { printf '\n=== %s ===\n' "$*"; }
fail() { printf '\nE2E_FAIL: %s\n' "$*" >&2; exit 1; }

free_port() { python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()'; }

cleanup() {
  local code=$?
  log "cleanup"
  for pid in "$AGENT_PID" "$RELAY_PID" "$SSHD_PID"; do
    [ -n "$pid" ] && kill "$pid" 2>/dev/null || true
  done
  # Give them a moment, then verify no leftover listeners on our ports.
  sleep 0.3 2>/dev/null || true
  local leftover=0
  for p in "${SSHD_PORT:-}" "${RELAY_PORT:-}"; do
    [ -z "$p" ] && continue
    if (ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | grep -q ":$p "; then
      echo "  WARNING: port $p still has a listener"
      leftover=1
    fi
  done
  [ "$leftover" = 0 ] && echo "  all spawned processes cleaned up; no leftover listeners on test ports"
  rm -rf "$TMP"
  exit $code
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
log "build relay, agent, e2e app client"
( cd "$REPO" && command go build -o "$BIN/relay"     ./relay )
( cd "$REPO" && command go build -o "$BIN/agent"     ./agent )
( cd "$REPO" && command go build -o "$BIN/e2eclient" ./test/e2eclient )
echo "built: $(ls "$BIN")"

# ---------------------------------------------------------------------------
log "generate ssh keys + private sshd config"
ssh-keygen -t ed25519 -N '' -f "$TMP/hostkey"   -q
ssh-keygen -t ed25519 -N '' -f "$TMP/clientkey" -q
cp "$TMP/clientkey.pub" "$TMP/authorized_keys"
chmod 600 "$TMP/authorized_keys"

SSHD_PORT="$(free_port)"
RELAY_PORT="$(free_port)"

cat > "$TMP/sshd_config" <<EOF
Port $SSHD_PORT
ListenAddress 127.0.0.1
HostKey $TMP/hostkey
PidFile $TMP/sshd.pid
AuthorizedKeysFile $TMP/authorized_keys
StrictModes no
UsePAM no
PasswordAuthentication no
PubkeyAuthentication yes
PermitUserEnvironment no
EOF

# ---------------------------------------------------------------------------
log "start private sshd on 127.0.0.1:$SSHD_PORT"
"$SSHD" -D -f "$TMP/sshd_config" -E "$TMP/sshd.log" &
SSHD_PID=$!
for _ in $(seq 1 50); do
  (ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | grep -q ":$SSHD_PORT " && break
  sleep 0.1 2>/dev/null || true
done
(ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | grep -q ":$SSHD_PORT " \
  || fail "sshd did not start (see log): $(cat "$TMP/sshd.log")"
echo "sshd listening (pid $SSHD_PID)"

# ---------------------------------------------------------------------------
log "start relay on 127.0.0.1:$RELAY_PORT"
"$BIN/relay" -addr "127.0.0.1:$RELAY_PORT" -token "$TOKEN" >"$TMP/relay.log" 2>&1 &
RELAY_PID=$!
for _ in $(seq 1 50); do
  curl -fsS "http://127.0.0.1:$RELAY_PORT/healthz" >/dev/null 2>&1 && break
  sleep 0.1 2>/dev/null || true
done
curl -fsS "http://127.0.0.1:$RELAY_PORT/healthz" >/dev/null 2>&1 || fail "relay /healthz not responding"
echo "relay healthz ok (pid $RELAY_PID)"

# ---------------------------------------------------------------------------
log "start device agent (forwards to local sshd)"
"$BIN/agent" -relay "ws://127.0.0.1:$RELAY_PORT" -device-id "testdev" -token "$TOKEN" \
  -local-ssh "127.0.0.1:$SSHD_PORT" >"$TMP/agent.log" 2>&1 &
AGENT_PID=$!
registered=0
for _ in $(seq 1 50); do
  grep -q "registered with relay" "$TMP/agent.log" 2>/dev/null && { registered=1; break; }
  sleep 0.1 2>/dev/null || true
done
[ "$registered" = 1 ] || fail "agent did not register: $(cat "$TMP/agent.log")"
echo "agent registered (pid $AGENT_PID)"

# ---------------------------------------------------------------------------
log "POSITIVE: app connects through relay tunnel and drives tmux over real SSH"
"$BIN/e2eclient" -relay "ws://127.0.0.1:$RELAY_PORT" -token "$TOKEN" -device "testdev" \
  -key "$TMP/clientkey" -user "$USER_NAME" -marker "$MARKER" | tee "$TMP/app.out"
grep -q "ssh handshake ok over tunnel" "$TMP/app.out" || fail "no SSH handshake over tunnel"
grep -q "marker found: $MARKER"        "$TMP/app.out" || fail "tmux marker not read back through tunnel"
grep -q "E2E_APP_OK"                    "$TMP/app.out" || fail "app client did not complete"

# ---------------------------------------------------------------------------
log "NEGATIVE: wrong token must be rejected end-to-end"
"$BIN/e2eclient" -relay "ws://127.0.0.1:$RELAY_PORT" -token "WRONG-TOKEN" -device "testdev" \
  -key "$TMP/clientkey" -user "$USER_NAME" -expect-reject | tee "$TMP/reject.out"
grep -q "auth correctly rejected" "$TMP/reject.out" || fail "bad token was not rejected"

# ---------------------------------------------------------------------------
log "RELAY is content-agnostic: bridged a session, logged only byte counts"
grep -q "session bridged" "$TMP/relay.log" || fail "relay did not log a bridged session"
if grep -q "$MARKER" "$TMP/relay.log"; then
  fail "relay log leaked SSH payload content (found marker) — NOT content-agnostic"
fi
echo "relay log shows 'session bridged' with byte counts, no payload content:"
grep "session bridged" "$TMP/relay.log" | tail -1

log "E2E PASSED"
echo "E2E_OK: real SSH + tmux flowed app -> relay -> agent -> sshd, content-agnostic relay confirmed"

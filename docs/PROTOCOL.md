# Remux relay wire protocol

This document specifies the protocol shared by the **relay server** (`relay/`),
the **device agent** (`agent/`), and (mirrored in Kotlin) the **mobile app**.
The canonical Go types live in `proto/proto.go`.

## Design goals

- **Firewall piercing.** The device (EC2/MacBook) makes only *outbound*
  connections. The app also connects outbound to the relay. They meet in the
  middle. No inbound port on the device is ever required.
- **Content-agnostic relay.** The relay moves opaque bytes on the data plane and
  never decrypts or parses them. A standard SSH connection runs end-to-end over
  the tunnel, so SSH's own encryption protects the session even if the relay is
  compromised.
- **Simple, robust multiplexing.** Each session is its own WebSocket pair — no
  custom multiplexing/framing on the data plane.

## Transport

WebSocket (WSS in production, WS for local development). Two planes:

| Plane   | Endpoint(s)                       | Message kind            | Purpose                              |
|---------|-----------------------------------|-------------------------|--------------------------------------|
| Control | `/agent/control`, `/app/control`  | JSON text (`Envelope`)  | registration, auth, listing, open    |
| Data    | `/data?session=<id>&token=<t>`    | binary, raw             | opaque byte pipe (carries SSH)       |
| Health  | `/healthz`                        | HTTP 200                | liveness                             |

## Control plane

Every control message is a JSON `Envelope`:

```json
{ "type": "<type>", "data": { ... } }
```

`type` values and payloads (`proto.Type` constants):

| Type         | Direction       | Payload                                            |
|--------------|-----------------|----------------------------------------------------|
| `register`   | agent → relay   | `{deviceId,name,token,platform}`                   |
| `registered` | relay → agent   | `{deviceId}`                                       |
| `auth`       | app → relay     | `{token}`                                          |
| `auth_ok`    | relay → app     | `{}`                                               |
| `list`       | app → relay     | `{}`                                               |
| `devices`    | relay → app     | `{devices:[{deviceId,name,platform,online}]}`      |
| `open`       | app → relay     | `{deviceId}` — request a session to that device    |
| `open`       | relay → agent   | `{sessionId}` — instruct agent to open a data conn |
| `opened`     | relay → app     | `{sessionId}`                                      |
| `error`      | relay → *       | `{msg}`                                            |
| `ping`/`pong`| either          | `{}` — optional app-level heartbeat                |

## Data plane (per-session WebSocket pair)

After the control-plane `open`/`opened` handshake, **both** the app and the
agent dial `/data?session=<sessionId>&token=<token>`. The relay holds the first
arriving connection until the second arrives, then bridges them: every binary
message read from one side is written verbatim to the other. The relay performs
**no** inspection, framing, or transformation of payload bytes.

On the agent side, its `/data` connection is additionally bridged to a fresh TCP
connection to the local sshd (`127.0.0.1:22` by default). So the full path is:

```
app SSH client  <--WS-->  relay  <--WS-->  agent  <--TCP-->  sshd (127.0.0.1:22)
                     (opaque bytes both legs)
```

## Authentication

MVP uses a shared **bearer token** that gates `/agent/control`, `/app/control`,
and `/data`. Unknown tokens are rejected (control: an `error` envelope then
close; data: immediate close). Production hardening (v0.2): per-device one-time
registration tokens and mTLS on the relay↔agent leg. The relay never sees SSH
credentials — those are exchanged end-to-end inside the tunneled SSH session.

## Heartbeat & reconnect

- **Heartbeat:** WebSocket ping/pong control frames. The relay pings connected
  agents/apps on an interval; a peer that misses pongs past the deadline is
  dropped (and, for agents, removed from the device registry).
- **Reconnect:** the agent reconnects on any control-connection drop using
  exponential backoff with jitter (e.g. 1s → 2s → 4s → … capped at 30s).

## Session lifecycle (summary)

1. Agent dials `/agent/control`, sends `register`. Relay replies `registered`.
2. App dials `/app/control`, sends `auth` → `auth_ok`, then `list` → `devices`.
3. App sends `open{deviceId}`. Relay mints `sessionId`, sends `open{sessionId}`
   to the target agent's control conn, replies `opened{sessionId}` to the app.
4. App and agent each dial `/data?session=<sessionId>&token=<token>`.
5. Relay pairs the two `/data` conns and bridges bytes. Agent also bridges its
   `/data` conn to local sshd. SSH (and tmux inside it) now runs end-to-end.

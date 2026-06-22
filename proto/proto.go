// Package proto defines the Remux relay wire protocol shared by the relay
// server, the device agent, and (mirrored in Kotlin) the mobile app.
//
// There are two planes:
//
//   - Control plane: JSON [Envelope] messages over the control WebSocket
//     endpoints (/agent/control, /app/control). Every message is an envelope
//     with a Type discriminator and a Data payload.
//
//   - Data plane: raw binary WebSocket messages over /data. The relay pairs
//     two /data connections by sessionId and copies bytes verbatim in both
//     directions. There is NO application framing on the data plane — it is an
//     opaque, ordered byte stream carrying a standard SSH connection. This is
//     what keeps the relay content-agnostic: it cannot read or alter the SSH
//     traffic (end-to-end SSH encryption is preserved).
//
// Session lifecycle (per-session WS pair):
//
//  1. Agent dials /agent/control, sends Register{deviceID,name,token,platform}.
//  2. App  dials /app/control, sends Auth{token}, then List{} -> Devices{...}.
//  3. App sends Open{deviceID}. Relay mints a sessionId, sends Open{sessionId}
//     to the target agent's control conn, and replies Opened{sessionId} to app.
//  4. Both app and agent dial /data?session=<sessionId>&token=<token>. The relay
//     pairs them by sessionId and bridges bytes. The agent's /data handler also
//     dials the local sshd (127.0.0.1:22) and bridges WS <-> TCP.
package proto

import (
	"encoding/json"
	"errors"
	"fmt"
)

// Type is the control-message discriminator carried in every [Envelope].
type Type string

const (
	TypeRegister   Type = "register"   // agent -> relay
	TypeRegistered Type = "registered" // relay -> agent
	TypeAuth       Type = "auth"       // app   -> relay
	TypeAuthOK     Type = "auth_ok"    // relay -> app
	TypeList       Type = "list"       // app   -> relay
	TypeDevices    Type = "devices"    // relay -> app
	TypeOpen       Type = "open"       // app -> relay (DeviceID) AND relay -> agent (SessionID)
	TypeOpened     Type = "opened"     // relay -> app
	TypeError      Type = "error"      // relay -> *
	TypePing       Type = "ping"       // optional application-level heartbeat
	TypePong       Type = "pong"       // optional application-level heartbeat
)

// Envelope is the outer JSON object for every control-plane message.
type Envelope struct {
	Type Type            `json:"type"`
	Data json.RawMessage `json:"data,omitempty"`
}

// Register is sent by an agent immediately after connecting to /agent/control.
type Register struct {
	DeviceID string `json:"deviceId"`
	Name     string `json:"name"`
	Token    string `json:"token"`
	Platform string `json:"platform"` // "linux" | "darwin" | ...
}

// Registered is the relay's acknowledgement of a successful Register.
type Registered struct {
	DeviceID string `json:"deviceId"`
}

// Auth is sent by the app after connecting to /app/control.
type Auth struct {
	Token string `json:"token"`
}

// AuthOK is the relay's acknowledgement of a successful Auth.
type AuthOK struct{}

// List requests the set of registered devices.
type List struct{}

// DeviceInfo describes one registered device in a Devices reply.
type DeviceInfo struct {
	DeviceID string `json:"deviceId"`
	Name     string `json:"name"`
	Platform string `json:"platform"`
	Online   bool   `json:"online"`
}

// Devices is the relay's reply to List.
type Devices struct {
	Devices []DeviceInfo `json:"devices"`
}

// Open is dual-purpose:
//   - app -> relay: DeviceID set, requesting a session to that device.
//   - relay -> agent: SessionID set, instructing the agent to open a data conn.
type Open struct {
	DeviceID  string `json:"deviceId,omitempty"`
	SessionID string `json:"sessionId,omitempty"`
}

// Opened is the relay's reply to the app's Open, carrying the minted session id.
type Opened struct {
	SessionID string `json:"sessionId"`
}

// Error is a generic relay error reply.
type Error struct {
	Msg string `json:"msg"`
}

// ErrEmpty is returned by Decode when given no bytes.
var ErrEmpty = errors.New("proto: empty message")

// Encode wraps a typed payload into an Envelope and marshals it to JSON.
// A nil payload produces an envelope with only the type set.
func Encode(t Type, payload any) ([]byte, error) {
	env := Envelope{Type: t}
	if payload != nil {
		raw, err := json.Marshal(payload)
		if err != nil {
			return nil, fmt.Errorf("proto: marshal payload for %q: %w", t, err)
		}
		env.Data = raw
	}
	return json.Marshal(env)
}

// Decode parses an Envelope and returns its type and raw data payload.
func Decode(b []byte) (Type, json.RawMessage, error) {
	if len(b) == 0 {
		return "", nil, ErrEmpty
	}
	var env Envelope
	if err := json.Unmarshal(b, &env); err != nil {
		return "", nil, fmt.Errorf("proto: unmarshal envelope: %w", err)
	}
	if env.Type == "" {
		return "", nil, errors.New("proto: missing message type")
	}
	return env.Type, env.Data, nil
}

// DecodeData unmarshals an envelope's raw data into the provided target.
func DecodeData(data json.RawMessage, target any) error {
	if len(data) == 0 {
		return nil
	}
	if err := json.Unmarshal(data, target); err != nil {
		return fmt.Errorf("proto: unmarshal data: %w", err)
	}
	return nil
}

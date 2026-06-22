package main

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/remux/remux/proto"
)

const testToken = "test-secret-token"

func newTestServer(t *testing.T) (*httptest.Server, string) {
	t.Helper()
	cfg := DefaultConfig()
	cfg.Token = testToken
	cfg.HeartbeatTimeout = 5 * time.Second
	cfg.SessionTimeout = 2 * time.Second
	// Discard logs during tests but keep the structured logger path exercised.
	srv := NewServer(cfg, slog.New(slog.NewTextHandler(io.Discard, nil)))
	ts := httptest.NewServer(srv.Handler())
	t.Cleanup(ts.Close)
	wsURL := "ws" + strings.TrimPrefix(ts.URL, "http")
	return ts, wsURL
}

func dial(t *testing.T, url string) *websocket.Conn {
	t.Helper()
	c, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		t.Fatalf("dial %s: %v", url, err)
	}
	t.Cleanup(func() { c.Close() })
	return c
}

func sendCtrl(t *testing.T, c *websocket.Conn, typ proto.Type, payload any) {
	t.Helper()
	b, err := proto.Encode(typ, payload)
	if err != nil {
		t.Fatalf("encode %s: %v", typ, err)
	}
	if err := c.WriteMessage(websocket.TextMessage, b); err != nil {
		t.Fatalf("write %s: %v", typ, err)
	}
}

func recvCtrl(t *testing.T, c *websocket.Conn) (proto.Type, json.RawMessage) {
	t.Helper()
	c.SetReadDeadline(time.Now().Add(3 * time.Second))
	_, raw, err := c.ReadMessage()
	if err != nil {
		t.Fatalf("read ctrl: %v", err)
	}
	typ, data, err := proto.Decode(raw)
	if err != nil {
		t.Fatalf("decode ctrl: %v", err)
	}
	return typ, data
}

func TestHealthz(t *testing.T) {
	ts, _ := newTestServer(t)
	resp, err := http.Get(ts.URL + "/healthz")
	if err != nil {
		t.Fatalf("GET /healthz: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("healthz status = %d, want 200", resp.StatusCode)
	}
	body, _ := io.ReadAll(resp.Body)
	if string(body) != "ok" {
		t.Fatalf("healthz body = %q, want ok", body)
	}
}

func TestAppBadTokenRejected(t *testing.T) {
	_, wsURL := newTestServer(t)
	c := dial(t, wsURL+"/app/control")
	sendCtrl(t, c, proto.TypeAuth, proto.Auth{Token: "wrong-token"})
	typ, data := recvCtrl(t, c)
	if typ != proto.TypeError {
		t.Fatalf("expected error reply, got %s", typ)
	}
	var e proto.Error
	_ = proto.DecodeData(data, &e)
	if !strings.Contains(e.Msg, "unauthorized") {
		t.Fatalf("error msg = %q, want unauthorized", e.Msg)
	}
}

func TestAgentBadTokenRejected(t *testing.T) {
	_, wsURL := newTestServer(t)
	c := dial(t, wsURL+"/agent/control")
	sendCtrl(t, c, proto.TypeRegister, proto.Register{DeviceID: "d1", Token: "wrong"})
	typ, data := recvCtrl(t, c)
	if typ != proto.TypeError {
		t.Fatalf("expected error reply, got %s", typ)
	}
	var e proto.Error
	_ = proto.DecodeData(data, &e)
	if !strings.Contains(e.Msg, "unauthorized") {
		t.Fatalf("error msg = %q, want unauthorized", e.Msg)
	}
}

func TestDataBadTokenRejected(t *testing.T) {
	_, wsURL := newTestServer(t)
	_, resp, err := websocket.DefaultDialer.Dial(wsURL+"/data?session=x&token=wrong", nil)
	if err == nil {
		t.Fatal("expected /data dial with bad token to fail")
	}
	if resp == nil || resp.StatusCode != http.StatusUnauthorized {
		got := 0
		if resp != nil {
			got = resp.StatusCode
		}
		t.Fatalf("expected 401, got %d", got)
	}
}

// TestEndToEndControlAndDataBridge exercises the full happy path: agent
// registers, app auths/lists/opens, the agent is notified, and a data session
// is bridged byte-for-byte in both directions through the relay.
func TestEndToEndControlAndDataBridge(t *testing.T) {
	_, wsURL := newTestServer(t)

	// 1. Agent registers.
	agent := dial(t, wsURL+"/agent/control")
	sendCtrl(t, agent, proto.TypeRegister, proto.Register{DeviceID: "dev1", Name: "ec2", Token: testToken, Platform: "linux"})
	if typ, _ := recvCtrl(t, agent); typ != proto.TypeRegistered {
		t.Fatalf("agent: expected registered, got %s", typ)
	}

	// 2. App auths and lists.
	app := dial(t, wsURL+"/app/control")
	sendCtrl(t, app, proto.TypeAuth, proto.Auth{Token: testToken})
	if typ, _ := recvCtrl(t, app); typ != proto.TypeAuthOK {
		t.Fatalf("app: expected auth_ok, got %s", typ)
	}
	sendCtrl(t, app, proto.TypeList, proto.List{})
	typ, data := recvCtrl(t, app)
	if typ != proto.TypeDevices {
		t.Fatalf("app: expected devices, got %s", typ)
	}
	var devs proto.Devices
	_ = proto.DecodeData(data, &devs)
	if len(devs.Devices) != 1 || devs.Devices[0].DeviceID != "dev1" || !devs.Devices[0].Online {
		t.Fatalf("device list = %+v, want one online dev1", devs.Devices)
	}

	// 3. App opens a session; agent should be told to open a data conn.
	agentOpen := make(chan string, 1)
	go func() {
		typ, data := recvCtrl(t, agent)
		if typ == proto.TypeOpen {
			var op proto.Open
			_ = proto.DecodeData(data, &op)
			agentOpen <- op.SessionID
		} else {
			agentOpen <- ""
		}
	}()
	sendCtrl(t, app, proto.TypeOpen, proto.Open{DeviceID: "dev1"})
	typ, data = recvCtrl(t, app)
	if typ != proto.TypeOpened {
		t.Fatalf("app: expected opened, got %s", typ)
	}
	var opened proto.Opened
	_ = proto.DecodeData(data, &opened)
	if opened.SessionID == "" {
		t.Fatal("opened session id empty")
	}
	var agentSession string
	select {
	case agentSession = <-agentOpen:
	case <-time.After(3 * time.Second):
		t.Fatal("agent never received open request")
	}
	if agentSession != opened.SessionID {
		t.Fatalf("session id mismatch: app=%s agent=%s", opened.SessionID, agentSession)
	}

	// 4. Both ends dial /data and the relay bridges bytes.
	dataURL := wsURL + "/data?token=" + testToken + "&session=" + opened.SessionID
	appData := dial(t, dataURL)
	agentData := dial(t, dataURL)

	// app -> agent
	want1 := []byte{0x00, 0x01, 0x02, 0xff, 0xfe, 'h', 'i'}
	if err := appData.WriteMessage(websocket.BinaryMessage, want1); err != nil {
		t.Fatalf("app data write: %v", err)
	}
	agentData.SetReadDeadline(time.Now().Add(3 * time.Second))
	_, got1, err := agentData.ReadMessage()
	if err != nil {
		t.Fatalf("agent data read: %v", err)
	}
	if !bytes.Equal(got1, want1) {
		t.Fatalf("app->agent corrupted: got %v want %v", got1, want1)
	}

	// agent -> app
	want2 := []byte("SSH-2.0-remux\r\n")
	if err := agentData.WriteMessage(websocket.BinaryMessage, want2); err != nil {
		t.Fatalf("agent data write: %v", err)
	}
	appData.SetReadDeadline(time.Now().Add(3 * time.Second))
	_, got2, err := appData.ReadMessage()
	if err != nil {
		t.Fatalf("app data read: %v", err)
	}
	if !bytes.Equal(got2, want2) {
		t.Fatalf("agent->app corrupted: got %q want %q", got2, want2)
	}
}

// TestOpenOfflineDevice verifies opening a session to an unknown device errors.
func TestOpenOfflineDevice(t *testing.T) {
	_, wsURL := newTestServer(t)
	app := dial(t, wsURL+"/app/control")
	sendCtrl(t, app, proto.TypeAuth, proto.Auth{Token: testToken})
	recvCtrl(t, app) // auth_ok
	sendCtrl(t, app, proto.TypeOpen, proto.Open{DeviceID: "ghost"})
	typ, data := recvCtrl(t, app)
	if typ != proto.TypeError {
		t.Fatalf("expected error for offline device, got %s", typ)
	}
	var e proto.Error
	_ = proto.DecodeData(data, &e)
	if !strings.Contains(e.Msg, "offline") {
		t.Fatalf("error msg = %q, want offline", e.Msg)
	}
}

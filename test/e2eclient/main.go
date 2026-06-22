// Command e2eclient plays the role of the Remux mobile app in the end-to-end
// test: it authenticates to the relay, lists devices, opens a session, dials
// the data tunnel, and runs a REAL SSH connection (golang.org/x/crypto/ssh)
// over the tunnel to drive tmux on the far side. It is exercised by
// scripts/e2e.sh.
//
// The SSH host-key check is intentionally relaxed here because this is a
// throwaway test harness talking to an ephemeral sshd with a generated host
// key; the shipping Android client uses trust-on-first-use with fingerprint
// pinning (see docs/SECURITY.md).
package main

import (
	"flag"
	"fmt"
	"net"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/remux/remux/proto"
	"golang.org/x/crypto/ssh"
)

func main() {
	relay := flag.String("relay", "ws://127.0.0.1:8080", "relay base ws url")
	token := flag.String("token", "", "relay token")
	device := flag.String("device", "testdev", "device id to open")
	keyPath := flag.String("key", "", "ssh private key path")
	user := flag.String("user", "", "ssh user")
	marker := flag.String("marker", "REMUX_AI_DONE", "expected tmux marker")
	expectReject := flag.Bool("expect-reject", false, "expect the relay to reject auth")
	mode := flag.String("mode", "relay", "control mode: relay (path-based) | apigw (API Gateway WSS)")
	dataURL := flag.String("data-url", "", "data-plane relay base URL (apigw mode; defaults to -relay)")
	flag.Parse()

	cfg := clientConfig{
		relay: *relay, token: *token, device: *device, keyPath: *keyPath,
		user: *user, marker: *marker, expectReject: *expectReject, mode: *mode, dataURL: *dataURL,
	}
	if err := run(cfg); err != nil {
		fmt.Fprintln(os.Stderr, "E2E_APP_FAIL:", err)
		os.Exit(1)
	}
}

type clientConfig struct {
	relay, token, device, keyPath, user, marker, mode, dataURL string
	expectReject                                               bool
}

// controlURL returns the WebSocket URL for the control plane.
func (c clientConfig) controlURL() string {
	if c.mode == "apigw" {
		return c.relay + "?token=" + url.QueryEscape(c.token) // API GW authorizes at $connect
	}
	return strings.TrimRight(c.relay, "/") + "/app/control"
}

// dataURLFor returns the /data WebSocket URL for a session.
func (c clientConfig) dataURLFor(sessionID string) string {
	base := c.relay
	if c.dataURL != "" {
		base = c.dataURL
	}
	return strings.TrimRight(base, "/") + "/data?session=" + url.QueryEscape(sessionID) + "&token=" + url.QueryEscape(c.token)
}

func run(cfg clientConfig) error {
	token, device, keyPath, user, marker, expectReject := cfg.token, cfg.device, cfg.keyPath, cfg.user, cfg.marker, cfg.expectReject

	// 1. Control connection + auth.
	ctrl, _, err := websocket.DefaultDialer.Dial(cfg.controlURL(), nil)
	if err != nil {
		return fmt.Errorf("dial control: %w", err)
	}
	defer ctrl.Close()

	authMsg, _ := proto.Encode(proto.TypeAuth, proto.Auth{Token: token})
	if err := ctrl.WriteMessage(websocket.TextMessage, authMsg); err != nil {
		return fmt.Errorf("send auth: %w", err)
	}
	typ, data, err := readCtrl(ctrl)
	if err != nil {
		return fmt.Errorf("read auth reply: %w", err)
	}

	if expectReject {
		if typ == proto.TypeError {
			var e proto.Error
			_ = proto.DecodeData(data, &e)
			fmt.Println("E2E_APP: auth correctly rejected:", e.Msg)
			return nil
		}
		return fmt.Errorf("expected auth rejection, got %s", typ)
	}
	if typ != proto.TypeAuthOK {
		return fmt.Errorf("expected auth_ok, got %s", typ)
	}
	fmt.Println("E2E_APP: auth ok")

	// 2. List devices.
	listMsg, _ := proto.Encode(proto.TypeList, proto.List{})
	_ = ctrl.WriteMessage(websocket.TextMessage, listMsg)
	typ, data, err = readCtrl(ctrl)
	if err != nil || typ != proto.TypeDevices {
		return fmt.Errorf("list devices failed: typ=%s err=%v", typ, err)
	}
	var devs proto.Devices
	_ = proto.DecodeData(data, &devs)
	found := false
	for _, d := range devs.Devices {
		if d.DeviceID == device && d.Online {
			found = true
		}
	}
	if !found {
		return fmt.Errorf("device %q not online; list=%+v", device, devs.Devices)
	}
	fmt.Printf("E2E_APP: device %s online\n", device)

	// 3. Open a session.
	openMsg, _ := proto.Encode(proto.TypeOpen, proto.Open{DeviceID: device})
	_ = ctrl.WriteMessage(websocket.TextMessage, openMsg)
	typ, data, err = readCtrl(ctrl)
	if err != nil || typ != proto.TypeOpened {
		return fmt.Errorf("open failed: typ=%s err=%v", typ, err)
	}
	var opened proto.Opened
	_ = proto.DecodeData(data, &opened)
	if opened.SessionID == "" {
		return fmt.Errorf("empty session id")
	}
	fmt.Printf("E2E_APP: session opened %s\n", opened.SessionID)

	// 4. Dial the data tunnel.
	dataWS, _, err := websocket.DefaultDialer.Dial(cfg.dataURLFor(opened.SessionID), nil)
	if err != nil {
		return fmt.Errorf("dial data: %w", err)
	}
	conn := newWSNetConn(dataWS)
	fmt.Println("E2E_APP: data tunnel connected")

	// 5. Real SSH handshake over the tunnel.
	signer, err := loadSigner(keyPath)
	if err != nil {
		return err
	}
	sshConf := &ssh.ClientConfig{
		User:            user,
		Auth:            []ssh.AuthMethod{ssh.PublicKeys(signer)},
		HostKeyCallback: ssh.InsecureIgnoreHostKey(), // test harness only
		Timeout:         10 * time.Second,
	}
	clientConn, chans, reqs, err := ssh.NewClientConn(conn, "remux-tunnel:22", sshConf)
	if err != nil {
		return fmt.Errorf("SSH handshake over tunnel failed: %w", err)
	}
	client := ssh.NewClient(clientConn, chans, reqs)
	defer client.Close()
	fmt.Println("E2E_APP: ssh handshake ok over tunnel")

	// 6. Drive tmux: create a detached session running a scripted fake-AI loop,
	//    then capture the pane and read the marker back through the tunnel.
	sess, err := client.NewSession()
	if err != nil {
		return fmt.Errorf("new ssh session: %w", err)
	}
	defer sess.Close()

	script := `
set -e
SOCK=remuxE2E
tmux -L $SOCK kill-server 2>/dev/null || true
tmux -L $SOCK new-session -d -s ai 'for i in 1 2 3; do echo "ai thinking $i"; sleep 0.2; done; echo ` + marker + `; sleep 30'
sleep 1.5
echo "---LIST---"
tmux -L $SOCK list-sessions
echo "---CAPTURE---"
tmux -L $SOCK capture-pane -t ai -p
tmux -L $SOCK kill-server 2>/dev/null || true
`
	out, err := sess.CombinedOutput(script)
	if err != nil {
		return fmt.Errorf("tmux script over tunnel failed: %w\noutput:\n%s", err, out)
	}
	output := string(out)
	fmt.Println("E2E_APP: remote tmux output over tunnel:")
	for _, line := range strings.Split(strings.TrimRight(output, "\n"), "\n") {
		fmt.Println("    | " + line)
	}
	if !strings.Contains(output, "ai:") && !strings.Contains(output, "ai ") {
		return fmt.Errorf("tmux session 'ai' not found in list-sessions output")
	}
	if !strings.Contains(output, marker) {
		return fmt.Errorf("marker %q not found in tmux output captured through the tunnel", marker)
	}
	fmt.Printf("E2E_APP: marker found: %s\n", marker)
	fmt.Println("E2E_APP_OK")
	return nil
}

func readCtrl(c *websocket.Conn) (proto.Type, []byte, error) {
	c.SetReadDeadline(time.Now().Add(10 * time.Second))
	_, raw, err := c.ReadMessage()
	if err != nil {
		return "", nil, err
	}
	t, d, err := proto.Decode(raw)
	return t, d, err
}

func loadSigner(path string) (ssh.Signer, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read key %q: %w", path, err)
	}
	signer, err := ssh.ParsePrivateKey(b)
	if err != nil {
		return nil, fmt.Errorf("parse key: %w", err)
	}
	return signer, nil
}

// wsNetConn adapts a binary WebSocket to net.Conn so x/crypto/ssh can run over
// the relay data tunnel as if it were a plain TCP socket.
type wsNetConn struct {
	c       *websocket.Conn
	readBuf []byte
	mu      sync.Mutex
}

func newWSNetConn(c *websocket.Conn) *wsNetConn { return &wsNetConn{c: c} }

func (w *wsNetConn) Read(p []byte) (int, error) {
	for len(w.readBuf) == 0 {
		_, msg, err := w.c.ReadMessage()
		if err != nil {
			return 0, err
		}
		w.readBuf = msg
	}
	n := copy(p, w.readBuf)
	w.readBuf = w.readBuf[n:]
	return n, nil
}

func (w *wsNetConn) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if err := w.c.WriteMessage(websocket.BinaryMessage, p); err != nil {
		return 0, err
	}
	return len(p), nil
}

func (w *wsNetConn) Close() error                       { return w.c.Close() }
func (w *wsNetConn) LocalAddr() net.Addr                { return w.c.LocalAddr() }
func (w *wsNetConn) RemoteAddr() net.Addr               { return w.c.RemoteAddr() }
func (w *wsNetConn) SetDeadline(t time.Time) error      { return w.c.SetReadDeadline(t) }
func (w *wsNetConn) SetReadDeadline(t time.Time) error  { return w.c.SetReadDeadline(t) }
func (w *wsNetConn) SetWriteDeadline(t time.Time) error { return w.c.SetWriteDeadline(t) }

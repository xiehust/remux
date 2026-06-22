package main

import (
	"context"
	"math/rand"
	"net"
	"net/url"
	"strings"
	"sync"
	"time"

	"log/slog"

	"github.com/gorilla/websocket"
	"github.com/remux/remux/proto"
)

// ctrlClient is the control connection to the relay. Abstracted so the
// reconnect loop is testable without a real WebSocket.
type ctrlClient interface {
	ReadMessage() ([]byte, error)
	WriteMessage([]byte) error
	// Ping sends a WebSocket ping control frame (keepalive). API Gateway closes
	// idle WebSocket connections after ~10 minutes, so the agent pings well
	// inside that window.
	Ping() error
	Close() error
}

// Agent dials outbound to the relay, registers, and forwards sessions to the
// local sshd. It reconnects with capped exponential backoff on any drop.
type Agent struct {
	cfg  Config
	log  *slog.Logger
	base time.Duration
	max  time.Duration
	rng  *rand.Rand

	// dialControl establishes a control connection to the given URL.
	// Overridable in tests; defaults to a real WebSocket dial.
	dialControl func(ctx context.Context, rawURL string) (ctrlClient, error)
	// dialData establishes a data connection; defaults to a real WebSocket dial.
	dialData func(ctx context.Context, rawURL string) (packetConn, error)
	// dialLocal connects to the local sshd; defaults to net.Dial.
	dialLocal func(ctx context.Context, addr string) (net.Conn, error)

	mu   sync.Mutex
	rngM sync.Mutex // guards rng (math/rand is not concurrency-safe)
}

// NewAgent builds an agent with production dialers.
func NewAgent(cfg Config, log *slog.Logger) *Agent {
	if log == nil {
		log = slog.Default()
	}
	a := &Agent{
		cfg:  cfg,
		log:  log,
		base: time.Second,
		max:  30 * time.Second,
		rng:  rand.New(rand.NewSource(time.Now().UnixNano())),
	}
	a.dialControl = a.realDialControl
	a.dialData = a.realDialData
	a.dialLocal = func(ctx context.Context, addr string) (net.Conn, error) {
		var d net.Dialer
		return d.DialContext(ctx, "tcp", addr)
	}
	return a
}

func (a *Agent) nextDelay(attempt int) time.Duration {
	a.rngM.Lock()
	defer a.rngM.Unlock()
	return backoffDelay(attempt, a.base, a.max, a.rng)
}

// Run connects to the relay and keeps the connection alive, reconnecting with
// backoff until ctx is cancelled.
func (a *Agent) Run(ctx context.Context) error {
	attempt := 0
	for {
		if ctx.Err() != nil {
			return nil
		}
		start := time.Now()
		err := a.connectOnce(ctx)
		if ctx.Err() != nil {
			return nil
		}
		// If the connection was healthy for a while, reset the backoff.
		if time.Since(start) > 10*time.Second {
			attempt = 0
		}
		delay := a.nextDelay(attempt)
		attempt++
		a.log.Warn("relay connection lost; reconnecting", "err", err, "delay", delay, "attempt", attempt)
		select {
		case <-ctx.Done():
			return nil
		case <-time.After(delay):
		}
	}
}

// connectOnce establishes one control connection, registers, and serves Open
// requests until the connection drops or ctx is cancelled.
func (a *Agent) connectOnce(ctx context.Context) error {
	conn, err := a.dialControl(ctx, a.cfg.controlURL())
	if err != nil {
		return err
	}
	defer conn.Close()

	reg, _ := proto.Encode(proto.TypeRegister, proto.Register{
		DeviceID: a.cfg.DeviceID,
		Name:     a.cfg.Name,
		Token:    a.cfg.Token,
		Platform: platform(),
	})
	if err := conn.WriteMessage(reg); err != nil {
		return err
	}
	// Expect a registered ack (or an error).
	raw, err := conn.ReadMessage()
	if err != nil {
		return err
	}
	typ, data, err := proto.Decode(raw)
	if err != nil {
		return err
	}
	if typ == proto.TypeError {
		var e proto.Error
		_ = proto.DecodeData(data, &e)
		a.log.Error("relay rejected registration", "msg", e.Msg)
		return &registerError{msg: e.Msg}
	}
	a.log.Info("registered with relay", "deviceId", a.cfg.DeviceID, "relay", a.cfg.RelayURL, "mode", a.cfg.Mode)

	// Keepalive: ping inside API Gateway's ~10-minute idle window. Stops when
	// connectOnce returns (the read loop below exits on drop/cancel).
	stopPing := make(chan struct{})
	defer close(stopPing)
	go func() {
		t := time.NewTicker(5 * time.Minute)
		defer t.Stop()
		for {
			select {
			case <-stopPing:
				return
			case <-ctx.Done():
				return
			case <-t.C:
				if err := conn.Ping(); err != nil {
					return
				}
			}
		}
	}()

	// Serve Open requests until the control connection drops.
	for {
		raw, err := conn.ReadMessage()
		if err != nil {
			return err
		}
		typ, data, err := proto.Decode(raw)
		if err != nil {
			continue
		}
		if typ == proto.TypeOpen {
			var op proto.Open
			_ = proto.DecodeData(data, &op)
			if op.SessionID != "" {
				go a.handleSession(ctx, op.SessionID)
			}
		}
	}
}

// handleSession opens a data connection to the relay and a TCP connection to
// the local sshd, then bridges them.
func (a *Agent) handleSession(ctx context.Context, sessionID string) {
	dataURL := joinWS(a.cfg.dataBaseURL(), "/data") + "?session=" + url.QueryEscape(sessionID) + "&token=" + url.QueryEscape(a.cfg.Token)
	ws, err := a.dialData(ctx, dataURL)
	if err != nil {
		a.log.Error("failed to open data connection", "sessionId", sessionID, "err", err)
		return
	}
	local, err := a.dialLocal(ctx, a.cfg.LocalSSH)
	if err != nil {
		a.log.Error("failed to dial local sshd", "addr", a.cfg.LocalSSH, "err", err)
		_ = ws.Close()
		return
	}
	a.log.Info("session opened", "sessionId", sessionID, "local", a.cfg.LocalSSH)
	up, down := forward(ws, local)
	a.log.Info("session closed", "sessionId", sessionID, "bytesUp", up, "bytesDown", down)
}

// --- real dialers ---

func (a *Agent) realDialControl(ctx context.Context, rawURL string) (ctrlClient, error) {
	c, _, err := websocket.DefaultDialer.DialContext(ctx, rawURL, nil)
	if err != nil {
		return nil, err
	}
	return &wsCtrlClient{conn: c}, nil
}

func (a *Agent) realDialData(ctx context.Context, rawURL string) (packetConn, error) {
	c, _, err := websocket.DefaultDialer.DialContext(ctx, rawURL, nil)
	if err != nil {
		return nil, err
	}
	return &wsPacketConn{conn: c}, nil
}

// wsCtrlClient adapts a websocket.Conn to ctrlClient (text control frames).
type wsCtrlClient struct {
	conn *websocket.Conn
	mu   sync.Mutex
}

func (c *wsCtrlClient) ReadMessage() ([]byte, error) {
	_, b, err := c.conn.ReadMessage()
	return b, err
}
func (c *wsCtrlClient) WriteMessage(b []byte) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.conn.WriteMessage(websocket.TextMessage, b)
}
func (c *wsCtrlClient) Ping() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(5*time.Second))
}
func (c *wsCtrlClient) Close() error { return c.conn.Close() }

// wsPacketConn adapts a websocket.Conn to packetConn (binary data frames).
type wsPacketConn struct {
	conn *websocket.Conn
	mu   sync.Mutex
}

func (c *wsPacketConn) ReadMessage() ([]byte, error) {
	_, b, err := c.conn.ReadMessage()
	return b, err
}
func (c *wsPacketConn) WriteMessage(b []byte) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.conn.WriteMessage(websocket.BinaryMessage, b)
}
func (c *wsPacketConn) Close() error { return c.conn.Close() }

type registerError struct{ msg string }

func (e *registerError) Error() string { return "registration rejected: " + e.msg }

// joinWS joins a base relay URL and a path, tolerating a trailing slash.
func joinWS(base, path string) string {
	return strings.TrimRight(base, "/") + path
}

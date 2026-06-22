package main

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/remux/remux/proto"
)

// Config holds relay server configuration.
type Config struct {
	Addr             string        // listen address, e.g. ":8080"
	Token            string        // shared bearer token gating all endpoints
	HeartbeatTimeout time.Duration // drop a peer after this long without a pong
	SessionTimeout   time.Duration // give up pairing a /data conn after this long
}

// DefaultConfig returns sane defaults.
func DefaultConfig() Config {
	return Config{
		Addr:             ":8080",
		HeartbeatTimeout: 60 * time.Second,
		SessionTimeout:   30 * time.Second,
	}
}

// Server is the Remux relay. It is content-agnostic on the data plane.
type Server struct {
	cfg      Config
	log      *slog.Logger
	upgrader websocket.Upgrader
	reg      *registry
	rdv      *rendezvous
}

// NewServer constructs a relay server.
func NewServer(cfg Config, log *slog.Logger) *Server {
	if log == nil {
		log = slog.Default()
	}
	return &Server{
		cfg: cfg,
		log: log,
		upgrader: websocket.Upgrader{
			ReadBufferSize:  32 * 1024,
			WriteBufferSize: 32 * 1024,
			CheckOrigin:     func(*http.Request) bool { return true },
		},
		reg: newRegistry(cfg.HeartbeatTimeout, time.Now),
		rdv: newRendezvous(cfg.SessionTimeout),
	}
}

// Handler returns the HTTP mux for the relay.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", s.handleHealthz)
	mux.HandleFunc("/agent/control", s.handleAgentControl)
	mux.HandleFunc("/app/control", s.handleAppControl)
	mux.HandleFunc("/data", s.handleData)
	return mux
}

// checkToken constant-time compares a provided token against the configured one.
func (s *Server) checkToken(got string) bool {
	if s.cfg.Token == "" {
		return true // auth disabled (dev only)
	}
	return subtle.ConstantTimeCompare([]byte(got), []byte(s.cfg.Token)) == 1
}

func (s *Server) handleHealthz(w http.ResponseWriter, _ *http.Request) {
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte("ok"))
}

// wsCtrl wraps a websocket.Conn for control-plane (JSON) use with a write mutex.
type wsCtrl struct {
	conn *websocket.Conn
	mu   sync.Mutex
}

func (c *wsCtrl) Send(msg []byte) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.conn.WriteMessage(websocket.TextMessage, msg)
}

// handleAgentControl: agent registers and stays connected to receive Open reqs.
func (s *Server) handleAgentControl(w http.ResponseWriter, r *http.Request) {
	conn, err := s.upgrader.Upgrade(w, r, nil)
	if err != nil {
		s.log.Warn("agent upgrade failed", "err", err)
		return
	}
	defer conn.Close()

	ctrl := &wsCtrl{conn: conn}

	// First message must be a Register.
	_, raw, err := conn.ReadMessage()
	if err != nil {
		return
	}
	typ, data, err := proto.Decode(raw)
	if err != nil || typ != proto.TypeRegister {
		s.sendErr(ctrl, "expected register")
		return
	}
	var reg proto.Register
	if err := proto.DecodeData(data, &reg); err != nil {
		s.sendErr(ctrl, "bad register payload")
		return
	}
	if !s.checkToken(reg.Token) {
		s.sendErr(ctrl, "unauthorized")
		s.log.Warn("agent register rejected: bad token", "deviceId", reg.DeviceID)
		return
	}
	if reg.DeviceID == "" {
		s.sendErr(ctrl, "missing deviceId")
		return
	}

	info := proto.DeviceInfo{DeviceID: reg.DeviceID, Name: reg.Name, Platform: reg.Platform, Online: true}
	s.reg.register(info, ctrl)
	defer s.reg.remove(reg.DeviceID)
	ack, _ := proto.Encode(proto.TypeRegistered, proto.Registered{DeviceID: reg.DeviceID})
	_ = ctrl.Send(ack)
	s.log.Info("agent registered", "deviceId", reg.DeviceID, "name", reg.Name, "platform", reg.Platform)

	// Heartbeat: pong handler refreshes last-seen; ping ticker keeps it alive.
	conn.SetReadDeadline(time.Now().Add(s.cfg.HeartbeatTimeout))
	conn.SetPongHandler(func(string) error {
		conn.SetReadDeadline(time.Now().Add(s.cfg.HeartbeatTimeout))
		s.reg.touch(reg.DeviceID)
		return nil
	})
	go s.pingLoop(conn)

	// Read loop: agents may send pong-equivalent app pings; mostly we just block
	// here keeping the connection (and its registry entry) alive.
	for {
		if _, _, err := conn.ReadMessage(); err != nil {
			s.log.Info("agent disconnected", "deviceId", reg.DeviceID, "err", err)
			return
		}
		s.reg.touch(reg.DeviceID)
	}
}

// handleAppControl: app authenticates, lists devices, and requests sessions.
func (s *Server) handleAppControl(w http.ResponseWriter, r *http.Request) {
	conn, err := s.upgrader.Upgrade(w, r, nil)
	if err != nil {
		s.log.Warn("app upgrade failed", "err", err)
		return
	}
	defer conn.Close()
	ctrl := &wsCtrl{conn: conn}

	// First message must be Auth.
	_, raw, err := conn.ReadMessage()
	if err != nil {
		return
	}
	typ, data, err := proto.Decode(raw)
	if err != nil || typ != proto.TypeAuth {
		s.sendErr(ctrl, "expected auth")
		return
	}
	var auth proto.Auth
	_ = proto.DecodeData(data, &auth)
	if !s.checkToken(auth.Token) {
		s.sendErr(ctrl, "unauthorized")
		s.log.Warn("app auth rejected: bad token")
		return
	}
	ok, _ := proto.Encode(proto.TypeAuthOK, proto.AuthOK{})
	_ = ctrl.Send(ok)

	for {
		_, raw, err := conn.ReadMessage()
		if err != nil {
			return
		}
		typ, data, err := proto.Decode(raw)
		if err != nil {
			s.sendErr(ctrl, "bad message")
			continue
		}
		switch typ {
		case proto.TypeList:
			devs, _ := proto.Encode(proto.TypeDevices, proto.Devices{Devices: s.reg.list()})
			_ = ctrl.Send(devs)
		case proto.TypeOpen:
			var op proto.Open
			_ = proto.DecodeData(data, &op)
			s.openSession(ctrl, op.DeviceID)
		default:
			s.sendErr(ctrl, "unsupported message")
		}
	}
}

// openSession mints a session id, notifies the target agent, and replies to app.
func (s *Server) openSession(app *wsCtrl, deviceID string) {
	agentConn, ok := s.reg.connFor(deviceID)
	if !ok {
		s.sendErr(app, "device offline: "+deviceID)
		return
	}
	sessionID := newSessionID()
	// Tell the agent to open a /data connection for this session.
	openMsg, _ := proto.Encode(proto.TypeOpen, proto.Open{SessionID: sessionID})
	if err := agentConn.Send(openMsg); err != nil {
		s.sendErr(app, "failed to reach device")
		return
	}
	opened, _ := proto.Encode(proto.TypeOpened, proto.Opened{SessionID: sessionID})
	_ = app.Send(opened)
	s.log.Info("session opening", "sessionId", sessionID, "deviceId", deviceID)
}

// wsData adapts a websocket.Conn to the dataConn interface (binary messages).
type wsData struct {
	conn *websocket.Conn
}

func (d wsData) ReadMessage() ([]byte, error) {
	_, msg, err := d.conn.ReadMessage()
	return msg, err
}
func (d wsData) WriteMessage(b []byte) error {
	return d.conn.WriteMessage(websocket.BinaryMessage, b)
}
func (d wsData) Close() error { return d.conn.Close() }

// handleData pairs two /data connections by session id and bridges bytes.
func (s *Server) handleData(w http.ResponseWriter, r *http.Request) {
	token := r.URL.Query().Get("token")
	sessionID := r.URL.Query().Get("session")
	if !s.checkToken(token) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	if sessionID == "" {
		http.Error(w, "missing session", http.StatusBadRequest)
		return
	}
	conn, err := s.upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	dc := wsData{conn: conn}

	partner, done := s.rdv.join(sessionID, dc)
	switch {
	case partner == nil && done == nil:
		// Timed out waiting for a partner.
		s.log.Warn("session pairing timed out", "sessionId", sessionID)
		_ = dc.Close()
	case partner == nil:
		// We are first; partner arrived and runs the bridge. Park until done.
		<-done
		_ = dc.Close()
	default:
		// We are second; run the content-agnostic bridge, then release first.
		aToB, bToA := bridge(dc, partner)
		close(done)
		// NOTE: we log only byte COUNTS, never any payload content.
		s.log.Info("session bridged", "sessionId", sessionID, "bytesUp", aToB, "bytesDown", bToA)
	}
}

func (s *Server) sendErr(c *wsCtrl, msg string) {
	b, _ := proto.Encode(proto.TypeError, proto.Error{Msg: msg})
	_ = c.Send(b)
}

func (s *Server) pingLoop(conn *websocket.Conn) {
	t := time.NewTicker(s.cfg.HeartbeatTimeout / 2)
	defer t.Stop()
	for range t.C {
		if err := conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(5*time.Second)); err != nil {
			return
		}
	}
}

// newSessionID returns a random hex session identifier.
func newSessionID() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	return hex.EncodeToString(b[:])
}

// ListenAndServe runs the relay until ctx is cancelled, then shuts down.
func (s *Server) ListenAndServe(ctx context.Context) error {
	srv := &http.Server{Addr: s.cfg.Addr, Handler: s.Handler()}
	errc := make(chan error, 1)
	go func() {
		s.log.Info("relay listening", "addr", s.cfg.Addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errc <- err
		}
	}()
	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		return srv.Shutdown(shutdownCtx)
	case err := <-errc:
		return err
	}
}

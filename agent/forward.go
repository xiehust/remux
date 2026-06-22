package main

import (
	"io"
	"sync"
)

// packetConn is the message-oriented side of a session (the relay /data
// WebSocket). Abstracted so forwarding is unit-testable without a real socket.
type packetConn interface {
	ReadMessage() ([]byte, error)
	WriteMessage([]byte) error
	Close() error
}

// forward bridges a relay data connection (message-oriented) and the local sshd
// connection (byte stream) in both directions until either side closes. It is
// content-agnostic: bytes are copied verbatim, carrying the end-to-end SSH
// stream. Returns bytes copied ws->local and local->ws (for logging counts).
func forward(ws packetConn, local io.ReadWriteCloser) (wsToLocal, localToWS int64) {
	var wg sync.WaitGroup
	wg.Add(2)

	// relay -> local sshd
	go func() {
		defer wg.Done()
		for {
			msg, err := ws.ReadMessage()
			if len(msg) > 0 {
				if _, werr := local.Write(msg); werr != nil {
					break
				}
				wsToLocal += int64(len(msg))
			}
			if err != nil {
				break
			}
		}
		_ = local.Close()
		_ = ws.Close()
	}()

	// local sshd -> relay
	go func() {
		defer wg.Done()
		buf := make([]byte, 32*1024)
		for {
			n, err := local.Read(buf)
			if n > 0 {
				if werr := ws.WriteMessage(buf[:n]); werr != nil {
					break
				}
				localToWS += int64(n)
			}
			if err != nil {
				break
			}
		}
		_ = ws.Close()
		_ = local.Close()
	}()

	wg.Wait()
	return wsToLocal, localToWS
}

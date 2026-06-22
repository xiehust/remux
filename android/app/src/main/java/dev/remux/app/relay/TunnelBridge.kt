package dev.remux.app.relay

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Bridges a relay `/data` WebSocket to a localhost loopback socket so the SSHJ
 * client can run a standard SSH connection over the relay tunnel by simply
 * dialing `127.0.0.1:<localPort>`.
 *
 * Flow: open a loopback [ServerSocket]; SSHJ connects to it; bytes from that
 * socket are forwarded to the `/data` WebSocket as binary messages, and binary
 * messages from the WebSocket are written back to the socket. The relay pairs
 * this with the device agent's `/data` connection — neither the relay nor this
 * bridge inspects the (SSH-encrypted) payload.
 */
class TunnelBridge(
    private val dataUrl: String,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private var server: ServerSocket? = null
    private var ws: WebSocket? = null
    @Volatile private var socket: Socket? = null

    /** Opens the loopback listener and the data WebSocket; returns the local
     *  port SSHJ should connect to. */
    fun start(): Int {
        val srv = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        server = srv
        val localPort = srv.localPort

        ws = client.newWebSocket(
            Request.Builder().url(dataUrl).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    runCatching { socket?.getOutputStream()?.apply { write(bytes.toByteArray()); flush() } }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = closeSocket()
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = closeSocket()
            },
        )

        thread(name = "remux-tunnel-accept", isDaemon = true) {
            runCatching {
                val s = srv.accept()
                socket = s
                val input = s.getInputStream()
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    ws?.send(buf.copyOf(n).toByteString())
                }
            }
            ws?.close(1000, "eof")
        }
        return localPort
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
    }

    fun close() {
        runCatching { ws?.close(1000, "bye") }
        runCatching { socket?.close() }
        runCatching { server?.close() }
    }
}

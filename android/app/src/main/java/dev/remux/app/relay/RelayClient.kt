package dev.remux.app.relay

import dev.remux.core.relay.Auth
import dev.remux.core.relay.DeviceInfo
import dev.remux.core.relay.Devices
import dev.remux.core.relay.ErrorMsg
import dev.remux.core.relay.MsgType
import dev.remux.core.relay.Open
import dev.remux.core.relay.Opened
import dev.remux.core.relay.TunnelProtocol
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Callbacks for relay control-plane events. */
interface RelayControlListener {
    fun onAuthenticated() {}
    fun onDevices(devices: List<DeviceInfo>) {}
    fun onSessionOpened(sessionId: String) {}
    fun onError(message: String) {}
    fun onClosed() {}
}

/**
 * Speaks the Remux control protocol over the relay `/app/control` WebSocket
 * (see [TunnelProtocol]): authenticate, list online devices, and open a session
 * to a chosen device. The returned sessionId is then used with [TunnelBridge] to
 * carry the SSH connection.
 */
class RelayClient(
    /** Exact control WebSocket URL. Fargate: `<base>/app/control`. API GW: the
     *  `wss://…/prod` URL (no path). */
    private val controlUrl: String,
    /** Base URL of the data-plane relay; `/data` is appended. Fargate: same host.
     *  API GW: the NLB-fronted Fargate relay. */
    private val dataBaseUrl: String,
    private val token: String,
    /** API Gateway authorizes at `$connect` via the query string. */
    private val tokenInQuery: Boolean = false,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private var control: WebSocket? = null

    fun connect(listener: RelayControlListener) {
        val url = if (tokenInQuery) "$controlUrl?token=$token" else controlUrl
        control = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(TunnelProtocol.encode(MsgType.AUTH, Auth(token)))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val env = TunnelProtocol.decodeEnvelope(text)
                    when (env.type) {
                        MsgType.AUTH_OK -> listener.onAuthenticated()
                        MsgType.DEVICES -> listener.onDevices(TunnelProtocol.decodeData<Devices>(env).devices)
                        MsgType.OPENED -> listener.onSessionOpened(TunnelProtocol.decodeData<Opened>(env).sessionId)
                        MsgType.ERROR -> listener.onError(TunnelProtocol.decodeData<ErrorMsg>(env).msg)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed()

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    listener.onError(t.message ?: "relay connection failed")
            },
        )
    }

    fun requestDeviceList() {
        control?.send(TunnelProtocol.encodeBare(MsgType.LIST))
    }

    fun openSession(deviceId: String) {
        control?.send(TunnelProtocol.encode(MsgType.OPEN, Open(deviceId = deviceId)))
    }

    fun close() {
        control?.close(1000, "bye")
        control = null
    }

    internal fun dataUrl(sessionId: String): String =
        dataBaseUrl.trimEnd('/') + "/data?session=" + sessionId + "&token=" + token
}

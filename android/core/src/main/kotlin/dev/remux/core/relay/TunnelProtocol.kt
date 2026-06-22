package dev.remux.core.relay

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Kotlin mirror of the Go `proto` package (see proto/proto.go and
 * docs/PROTOCOL.md): the control-plane messages the app exchanges with the relay
 * over the `/app/control` WebSocket. The data plane carries raw SSH bytes and has
 * no framing here.
 */
object MsgType {
    const val REGISTER = "register"
    const val REGISTERED = "registered"
    const val AUTH = "auth"
    const val AUTH_OK = "auth_ok"
    const val LIST = "list"
    const val DEVICES = "devices"
    const val OPEN = "open"
    const val OPENED = "opened"
    const val ERROR = "error"
}

@Serializable
data class Envelope(val type: String, val data: JsonElement? = null)

@Serializable
data class Auth(val token: String)

@Serializable
data class DeviceInfo(
    val deviceId: String,
    val name: String = "",
    val platform: String = "",
    val online: Boolean = false,
)

@Serializable
data class Devices(val devices: List<DeviceInfo> = emptyList())

@Serializable
data class Open(val deviceId: String? = null, val sessionId: String? = null)

@Serializable
data class Opened(val sessionId: String)

@Serializable
data class ErrorMsg(val msg: String)

/** Encodes/decodes control-plane envelopes for the app↔relay WebSocket. */
object TunnelProtocol {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    inline fun <reified T> encode(type: String, payload: T): String {
        val env = Envelope(type, json.encodeToJsonElement(payload))
        return json.encodeToString(Envelope.serializer(), env)
    }

    fun encodeBare(type: String): String =
        json.encodeToString(Envelope.serializer(), Envelope(type, null))

    fun decodeEnvelope(raw: String): Envelope =
        json.decodeFromString(Envelope.serializer(), raw)

    inline fun <reified T> decodeData(env: Envelope): T {
        val data = env.data ?: error("envelope ${env.type} has no data")
        return json.decodeFromJsonElement(data)
    }
}

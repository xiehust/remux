package dev.remux.app.ui

import android.content.Context
import dev.remux.app.data.RemuxDatabase
import dev.remux.app.security.SecureKeyStore
import dev.remux.app.ssh.FingerprintStore

/** Simple manual DI container holding app-wide singletons. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val database: RemuxDatabase = RemuxDatabase.get(appContext)
    val keyStore: SecureKeyStore = SecureKeyStore(appContext)
    val hostDao get() = database.hostDao()
    val relayDeviceDao get() = database.relayDeviceDao()

    /** Persisted SSH host-key fingerprints for trust-on-first-use verification. */
    val fingerprintStore: FingerprintStore = object : FingerprintStore {
        private val prefs = appContext.getSharedPreferences("remux_known_hosts", Context.MODE_PRIVATE)
        override fun get(hostPort: String): String? = prefs.getString(hostPort, null)
        override fun put(hostPort: String, fingerprint: String) {
            prefs.edit().putString(hostPort, fingerprint).apply()
        }
    }

    /** Relay endpoint settings (for via-relay connections). MVP: in-memory defaults. */
    var relayMode: RelayMode = RelayMode.FARGATE

    /**
     * FARGATE: the self-hosted relay base (e.g. `ws://relay:8080`).
     * APIGW: the API Gateway WebSocket URL (e.g. `wss://abc.execute-api.us-east-2.amazonaws.com/prod`).
     */
    var relayBaseUrl: String = ""

    /** APIGW only: base URL of the NLB-fronted Fargate data relay (e.g. `ws://nlb:8080`). */
    var relayDataUrl: String = ""
    var relayToken: String = ""

    /** Builds a [dev.remux.app.relay.RelayClient] wired for the current mode. */
    fun newRelayClient(): dev.remux.app.relay.RelayClient = when (relayMode) {
        RelayMode.FARGATE -> dev.remux.app.relay.RelayClient(
            controlUrl = relayBaseUrl.trimEnd('/') + "/app/control",
            dataBaseUrl = relayBaseUrl,
            token = relayToken,
        )
        RelayMode.APIGW -> dev.remux.app.relay.RelayClient(
            controlUrl = relayBaseUrl,
            dataBaseUrl = relayDataUrl.ifBlank { relayBaseUrl },
            token = relayToken,
            tokenInQuery = true,
        )
    }
}

/** How the app reaches the relay control plane. */
enum class RelayMode {
    /** Self-hosted Go relay, path-based (`/app/control`, `/data` on one host). */
    FARGATE,

    /** API Gateway WebSocket + Lambda control plane; data over the NLB-fronted relay. */
    APIGW,
}

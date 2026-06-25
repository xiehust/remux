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

    /**
     * Relay endpoint settings (for via-relay connections), persisted across app
     * restarts. The non-secret fields (mode + URLs) live in plain
     * SharedPreferences; the bearer token is kept in the encrypted [keyStore]
     * so it gets the same at-rest protection as SSH private keys.
     */
    private val relayPrefs = appContext.getSharedPreferences("remux_relay", Context.MODE_PRIVATE)

    var relayMode: RelayMode
        get() = runCatching {
            RelayMode.valueOf(relayPrefs.getString(KEY_MODE, null) ?: RelayMode.FARGATE.name)
        }.getOrDefault(RelayMode.FARGATE)
        set(value) {
            relayPrefs.edit().putString(KEY_MODE, value.name).apply()
        }

    /**
     * FARGATE: the self-hosted relay base (e.g. `ws://relay:8080`).
     * APIGW: the API Gateway WebSocket URL (e.g. `wss://abc.execute-api.us-east-2.amazonaws.com/prod`).
     */
    var relayBaseUrl: String
        get() = relayPrefs.getString(KEY_BASE_URL, "") ?: ""
        set(value) {
            relayPrefs.edit().putString(KEY_BASE_URL, value).apply()
        }

    /** APIGW only: base URL of the NLB-fronted Fargate data relay (e.g. `ws://nlb:8080`). */
    var relayDataUrl: String
        get() = relayPrefs.getString(KEY_DATA_URL, "") ?: ""
        set(value) {
            relayPrefs.edit().putString(KEY_DATA_URL, value).apply()
        }

    /** Relay bearer token; stored encrypted via [keyStore]. */
    var relayToken: String
        get() = keyStore.getSecret(SECRET_RELAY_TOKEN) ?: ""
        set(value) {
            keyStore.putSecret(SECRET_RELAY_TOKEN, value)
        }

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

    private companion object {
        const val KEY_MODE = "relay_mode"
        const val KEY_BASE_URL = "relay_base_url"
        const val KEY_DATA_URL = "relay_data_url"
        const val SECRET_RELAY_TOKEN = "relay_token"
    }
}

/** How the app reaches the relay control plane. */
enum class RelayMode {
    /** Self-hosted Go relay, path-based (`/app/control`, `/data` on one host). */
    FARGATE,

    /** API Gateway WebSocket + Lambda control plane; data over the NLB-fronted relay. */
    APIGW,
}

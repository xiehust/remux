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
    var relayBaseUrl: String = ""
    var relayToken: String = ""
}

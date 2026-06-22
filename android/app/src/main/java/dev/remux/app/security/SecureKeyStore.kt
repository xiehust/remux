package dev.remux.app.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted-at-rest storage for SSH private keys, backed by Jetpack Security's
 * [EncryptedSharedPreferences] (AES-256, master key in the Android Keystore).
 *
 * Private key PEM material is ONLY ever written through here — it is never
 * placed in Room, plain SharedPreferences, or logs. The Room [dev.remux.app.data.HostEntity]
 * stores just a [keyAlias] that maps to an entry here.
 */
class SecureKeyStore(context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Stores a private key PEM under [alias] (encrypted at rest). */
    fun storePrivateKey(alias: String, privateKeyPem: String) {
        prefs.edit().putString(keyFor(alias), privateKeyPem).apply()
    }

    /** Returns the decrypted private key PEM for [alias], or null. */
    fun getPrivateKey(alias: String): String? = prefs.getString(keyFor(alias), null)

    fun deleteKey(alias: String) {
        prefs.edit().remove(keyFor(alias)).apply()
    }

    fun listAliases(): Set<String> =
        prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }.map { it.removePrefix(KEY_PREFIX) }.toSet()

    private fun keyFor(alias: String) = KEY_PREFIX + alias

    companion object {
        private const val PREFS_NAME = "remux_secure_keys"
        private const val KEY_PREFIX = "key_"
    }
}

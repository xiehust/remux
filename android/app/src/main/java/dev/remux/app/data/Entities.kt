package dev.remux.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** How a saved host authenticates. */
enum class AuthType { KEY, PASSWORD }

/** How a saved host is reached. */
enum class ConnectMode { DIRECT, RELAY }

/**
 * A saved SSH host. Private key material is NOT stored here — only a [keyAlias]
 * referencing the encrypted key store ([dev.remux.app.security.SecureKeyStore]).
 */
@Entity(tableName = "hosts")
data class HostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val hostName: String,
    val port: Int = 22,
    val username: String,
    val authType: AuthType = AuthType.KEY,
    /** Alias into the encrypted key store (key auth); never the key itself. */
    val keyAlias: String? = null,
    val connectMode: ConnectMode = ConnectMode.DIRECT,
    /** For [ConnectMode.RELAY]: the registered device to tunnel through. */
    val relayDeviceId: String? = null,
)

/** A device registered with the relay, cached for the device picker. */
@Entity(tableName = "relay_devices")
data class RelayDeviceEntity(
    @PrimaryKey val deviceId: String,
    val name: String,
    val platform: String,
    val lastSeenEpochMs: Long,
)

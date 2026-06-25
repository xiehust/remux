package dev.remux.app.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.InputStream
import java.io.OutputStream
import java.security.PublicKey
import java.security.Security
import java.util.concurrent.TimeUnit

/** How the user authenticates to a host. */
sealed interface SshAuth {
    /** Private-key auth (Ed25519 or RSA PEM). [publicKeyPem] and [passphrase] optional. */
    data class Key(val privateKeyPem: String, val publicKeyPem: String? = null, val passphrase: String? = null) : SshAuth

    /** Password auth. */
    data class Password(val password: String) : SshAuth
}

/**
 * Thin wrapper over SSHJ providing the operations Remux needs: connect (directly
 * or over the relay tunnel via a loopback host/port), key/password auth, an
 * interactive xterm PTY shell, one-shot exec (for `tmux ls`), and window resize.
 *
 * Host keys are verified trust-on-first-use via [TofuHostKeyVerifier] — the
 * fingerprint is pinned on first connect and compared thereafter.
 */
class SshSession(
    private val client: SSHClient = SSHClient(),
) {
    private var session: Session? = null
    private var shell: Session.Shell? = null

    private companion object {
        init {
            // Android pre-registers a stripped-down BouncyCastle under the name
            // "BC" that lacks an X25519 KeyPairGenerator — so SSHJ's default
            // curve25519-sha256 key exchange fails with
            // "no such algorithm: X25519 for provider BC". SSHJ bundles the full
            // org.bouncycastle provider on the classpath; swap it in under "BC".
            // (insertProviderAt is rejected if a "BC" provider already exists, so
            // the existing one must be removed first.)
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    val isConnected: Boolean get() = client.isConnected

    /** Connects to [host]:[port]. For relay tunneling, pass the local loopback
     *  host/port produced by [dev.remux.app.relay.TunnelBridge]. */
    fun connect(host: String, port: Int, verifier: HostKeyVerifier) {
        client.addHostKeyVerifier(verifier)
        client.connectTimeout = 15_000
        client.connect(host, port)
    }

    fun authenticate(user: String, auth: SshAuth) {
        when (auth) {
            is SshAuth.Password -> client.authPassword(user, auth.password)
            is SshAuth.Key -> {
                val keys: KeyProvider = client.loadKeys(auth.privateKeyPem, auth.publicKeyPem, null)
                client.authPublickey(user, keys)
            }
        }
    }

    /** Allocates an xterm-256color PTY and starts an interactive shell. */
    fun startShell(cols: Int, rows: Int): ShellStreams {
        val s = client.startSession()
        s.allocatePTY("xterm-256color", cols, rows, 0, 0, emptyMap())
        val sh = s.startShell()
        session = s
        shell = sh
        return ShellStreams(sh.inputStream, sh.outputStream, sh.errorStream)
    }

    /** Runs a one-shot command (used to detect tmux: `tmux list-sessions`). */
    fun exec(command: String, timeoutSeconds: Long = 10): String {
        client.startSession().use { s ->
            val cmd = s.exec(command)
            val out = cmd.inputStream.readBytes().toString(Charsets.UTF_8)
            cmd.join(timeoutSeconds, TimeUnit.SECONDS)
            return out
        }
    }

    /** Notifies the remote of a new terminal size (on rotate / resize). */
    fun resize(cols: Int, rows: Int) {
        shell?.changeWindowDimensions(cols, rows, 0, 0)
    }

    fun close() {
        runCatching { shell?.close() }
        runCatching { session?.close() }
        runCatching { client.disconnect() }
    }
}

/** Stdin/stdout/stderr of an interactive shell. */
class ShellStreams(
    val output: InputStream, // remote stdout -> terminal
    val input: OutputStream, // terminal -> remote stdin
    val error: InputStream,
)

/** Fingerprint persistence for trust-on-first-use host-key verification. */
interface FingerprintStore {
    fun get(hostPort: String): String?
    fun put(hostPort: String, fingerprint: String)
}

/**
 * Trust-on-first-use host key verifier: on first contact the host key
 * fingerprint is recorded; on subsequent connects it must match, otherwise the
 * connection is rejected (possible MITM). This is the shipping policy — we never
 * blindly accept unknown keys after the first pin.
 */
class TofuHostKeyVerifier(
    private val store: FingerprintStore,
    private val fingerprintOf: (PublicKey) -> String,
) : HostKeyVerifier {
    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val id = "$hostname:$port"
        val fp = fingerprintOf(key)
        val known = store.get(id)
        return if (known == null) {
            store.put(id, fp) // pin on first use
            true
        } else {
            known == fp
        }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}

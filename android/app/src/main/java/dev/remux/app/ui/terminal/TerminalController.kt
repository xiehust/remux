package dev.remux.app.ui.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.remux.app.relay.RelayClient
import dev.remux.app.relay.RelayControlListener
import dev.remux.app.relay.TunnelBridge
import dev.remux.app.data.AuthType
import dev.remux.app.data.ConnectMode
import dev.remux.app.data.HostEntity
import dev.remux.app.ssh.ShellStreams
import dev.remux.app.ssh.SshAuth
import dev.remux.app.ssh.SshSession
import dev.remux.app.ssh.TofuHostKeyVerifier
import dev.remux.app.ui.AppContainer
import dev.remux.core.terminal.Terminal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.common.SecurityUtils
import java.io.IOException

/** Connection state for the terminal screen. */
enum class ConnState { IDLE, CONNECTING, CONNECTED, ERROR, DISCONNECTED }

/**
 * Drives one SSH terminal session: connects (directly or through the relay
 * tunnel), feeds incoming bytes into the [Terminal] buffer, and sends keystrokes
 * back. Exposes Compose-observable [revision] and [state] so the UI recomposes.
 */
class TerminalController(
    private val container: AppContainer,
    cols: Int = 80,
    rows: Int = 24,
) {
    val terminal = Terminal(cols, rows)

    var revision by mutableIntStateOf(0)
        private set
    var state by mutableStateOf(ConnState.IDLE)
        private set
    var statusMessage by mutableStateOf("")
        private set
    var ctrlActive by mutableStateOf(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: SshSession? = null
    private var shell: ShellStreams? = null
    private var tunnel: TunnelBridge? = null
    private var relay: RelayClient? = null
    private var readJob: Job? = null

    fun connect(host: HostEntity, password: String? = null) {
        scope.launch {
            try {
                state = ConnState.CONNECTING
                statusMessage = "Connecting to ${host.label}…"
                val (targetHost, targetPort) = resolveTarget(host)
                val s = SshSession()
                val verifier = TofuHostKeyVerifier(container.fingerprintStore) { SecurityUtils.getFingerprint(it) }
                s.connect(targetHost, targetPort, verifier)
                s.authenticate(host.username, buildAuth(host, password))
                val streams = s.startShell(terminal.cols, terminal.rows)
                session = s
                shell = streams
                state = ConnState.CONNECTED
                statusMessage = "Connected"
                startReadLoop(streams)
            } catch (e: Exception) {
                state = ConnState.ERROR
                statusMessage = e.message ?: "Connection failed"
            }
        }
    }

    private suspend fun resolveTarget(host: HostEntity): Pair<String, Int> {
        if (host.connectMode == ConnectMode.DIRECT) return host.hostName to host.port
        val deviceId = host.relayDeviceId ?: throw IOException("no relay device selected")
        val sessionId = openRelaySession(deviceId)
        val bridge = TunnelBridge(relay!!.dataUrl(sessionId))
        tunnel = bridge
        return "127.0.0.1" to bridge.start()
    }

    private suspend fun openRelaySession(deviceId: String): String {
        val r = container.newRelayClient()
        relay = r
        val opened = CompletableDeferred<String>()
        r.connect(object : RelayControlListener {
            override fun onAuthenticated() = r.openSession(deviceId)
            override fun onSessionOpened(sessionId: String) {
                opened.complete(sessionId)
            }
            override fun onError(message: String) {
                opened.completeExceptionally(IOException(message))
            }
        })
        return opened.await()
    }

    private fun buildAuth(host: HostEntity, password: String?): SshAuth = when (host.authType) {
        AuthType.PASSWORD -> SshAuth.Password(password ?: throw IOException("password required"))
        AuthType.KEY -> {
            val alias = host.keyAlias ?: throw IOException("no key selected")
            val pem = container.keyStore.getPrivateKey(alias) ?: throw IOException("key not found")
            SshAuth.Key(privateKeyPem = pem)
        }
    }

    private fun startReadLoop(streams: ShellStreams) {
        readJob = scope.launch {
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = streams.output.read(buf)
                    if (n < 0) break
                    val chunk = buf.copyOf(n)
                    withContext(Dispatchers.Main) {
                        terminal.feed(chunk)
                        revision++
                    }
                }
            } catch (_: Exception) {
                // fall through to disconnected
            } finally {
                withContext(Dispatchers.Main) {
                    if (state == ConnState.CONNECTED) state = ConnState.DISCONNECTED
                }
            }
        }
    }

    /** Sends raw bytes to the remote shell stdin. */
    fun send(text: String) {
        val s = shell ?: return
        scope.launch {
            runCatching {
                s.input.write(text.toByteArray(Charsets.UTF_8))
                s.input.flush()
            }
        }
    }

    fun resize(cols: Int, rows: Int) {
        session?.resize(cols, rows)
        revision++
    }

    fun close() {
        readJob?.cancel()
        runCatching { session?.close() }
        runCatching { tunnel?.close() }
        runCatching { relay?.close() }
    }
}

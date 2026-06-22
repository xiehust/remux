package dev.remux.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.remux.app.data.AuthType
import dev.remux.app.data.ConnectMode
import dev.remux.app.data.HostEntity
import dev.remux.app.ui.AppContainer
import kotlinx.coroutines.launch

@Composable
fun HostEditScreen(
    container: AppContainer,
    hostId: Long?,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf("") }
    var hostName by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("") }
    var authType by remember { mutableStateOf(AuthType.KEY) }
    var keyPem by remember { mutableStateOf("") }
    var keyAlias by remember { mutableStateOf<String?>(null) }
    var connectMode by remember { mutableStateOf(ConnectMode.DIRECT) }
    var relayDeviceId by remember { mutableStateOf("") }

    LaunchedEffect(hostId) {
        if (hostId != null) {
            container.hostDao.byId(hostId)?.let { h ->
                label = h.label; hostName = h.hostName; port = h.port.toString()
                username = h.username; authType = h.authType; keyAlias = h.keyAlias
                connectMode = h.connectMode; relayDeviceId = h.relayDeviceId ?: ""
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (hostId == null) "Add host" else "Edit host", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(label, { label = it }, label = { Text("Label") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(hostName, { hostName = it }, label = { Text("Host / IP") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            port, { port = it.filter(Char::isDigit) }, label = { Text("Port") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(username, { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())

        Text("Authentication", style = MaterialTheme.typography.titleSmall)
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(authType == AuthType.KEY, { authType = AuthType.KEY }, label = { Text("Key") })
            FilterChip(authType == AuthType.PASSWORD, { authType = AuthType.PASSWORD }, label = { Text("Password") })
        }
        if (authType == AuthType.KEY) {
            OutlinedTextField(
                keyPem, { keyPem = it }, label = { Text("Private key (PEM) — stored encrypted") },
                modifier = Modifier.fillMaxWidth(), minLines = 3,
            )
        }

        Text("Connection", style = MaterialTheme.typography.titleSmall)
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(connectMode == ConnectMode.DIRECT, { connectMode = ConnectMode.DIRECT }, label = { Text("Direct") })
            FilterChip(connectMode == ConnectMode.RELAY, { connectMode = ConnectMode.RELAY }, label = { Text("Via relay") })
        }
        if (connectMode == ConnectMode.RELAY) {
            OutlinedTextField(relayDeviceId, { relayDeviceId = it }, label = { Text("Relay device id") }, modifier = Modifier.fillMaxWidth())
        }

        Button(
            onClick = {
                scope.launch {
                    val alias = if (authType == AuthType.KEY && keyPem.isNotBlank()) {
                        val a = keyAlias ?: ("key-" + System.nanoTime())
                        container.keyStore.storePrivateKey(a, keyPem)
                        a
                    } else {
                        keyAlias
                    }
                    container.hostDao.upsert(
                        HostEntity(
                            id = hostId ?: 0,
                            label = label.ifBlank { hostName },
                            hostName = hostName,
                            port = port.toIntOrNull() ?: 22,
                            username = username,
                            authType = authType,
                            keyAlias = alias,
                            connectMode = connectMode,
                            relayDeviceId = relayDeviceId.ifBlank { null },
                        ),
                    )
                    onDone()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
    }
}

package dev.remux.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.remux.app.relay.RelayClient
import dev.remux.app.relay.RelayControlListener
import dev.remux.app.ui.AppContainer
import dev.remux.core.relay.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun DevicePickerScreen(
    container: AppContainer,
    onPicked: (DeviceInfo) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var relayUrl by remember { mutableStateOf(container.relayBaseUrl) }
    var token by remember { mutableStateOf(container.relayToken) }
    var devices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var status by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Pick a relay device", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(relayUrl, { relayUrl = it }, label = { Text("Relay URL (wss://…)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it }, label = { Text("Relay token") }, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = {
                container.relayBaseUrl = relayUrl
                container.relayToken = token
                status = "Connecting…"
                val client = RelayClient(relayUrl, token)
                client.connect(object : RelayControlListener {
                    override fun onAuthenticated() = client.requestDeviceList()
                    override fun onDevices(list: List<DeviceInfo>) {
                        scope.launch(Dispatchers.Main) { devices = list; status = "${list.size} device(s)" }
                    }
                    override fun onError(message: String) {
                        scope.launch(Dispatchers.Main) { status = "Error: $message" }
                    }
                })
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Connect & list devices") }

        if (status.isNotEmpty()) Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)

        for (d in devices) {
            Card(modifier = Modifier.fillMaxWidth().clickable { onPicked(d) }) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(d.name.ifBlank { d.deviceId }, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${d.platform} · ${if (d.online) "online" else "offline"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

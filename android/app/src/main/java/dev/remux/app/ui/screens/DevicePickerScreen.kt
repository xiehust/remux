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
import dev.remux.app.relay.RelayControlListener
import dev.remux.app.ui.AppContainer
import dev.remux.app.ui.RelayMode
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
    var dataUrl by remember { mutableStateOf(container.relayDataUrl) }
    var token by remember { mutableStateOf(container.relayToken) }
    var apigw by remember { mutableStateOf(container.relayMode == RelayMode.APIGW) }
    var devices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var status by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Pick a relay device", style = MaterialTheme.typography.headlineSmall)
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilterChip(!apigw, { apigw = false }, label = { Text("Self-hosted") })
            androidx.compose.material3.FilterChip(apigw, { apigw = true }, label = { Text("API Gateway") })
        }
        OutlinedTextField(
            relayUrl, { relayUrl = it },
            label = { Text(if (apigw) "API Gateway URL (wss://…/prod)" else "Relay URL (ws(s)://…)") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (apigw) {
            OutlinedTextField(dataUrl, { dataUrl = it }, label = { Text("Data relay URL (NLB, ws://…:8080)") }, modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(token, { token = it }, label = { Text("Relay token") }, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = {
                container.relayBaseUrl = relayUrl
                container.relayDataUrl = dataUrl
                container.relayToken = token
                container.relayMode = if (apigw) RelayMode.APIGW else RelayMode.FARGATE
                status = "Connecting…"
                val client = container.newRelayClient()
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

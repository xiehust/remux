package dev.remux.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import dev.remux.app.data.ConnectMode
import dev.remux.app.data.HostEntity
import dev.remux.app.ui.AppContainer

@Composable
fun HostListScreen(
    container: AppContainer,
    onAddHost: () -> Unit,
    onConnect: (HostEntity) -> Unit,
    onEditHost: (HostEntity) -> Unit,
    onOpenRelay: () -> Unit,
) {
    val hosts by container.hostDao.observeAll().collectAsState(initial = emptyList())

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onAddHost) {
                Icon(Icons.Filled.Add, contentDescription = "Add host")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Remux",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                androidx.compose.material3.TextButton(onClick = onOpenRelay) {
                    Text("Relay")
                }
            }
            if (hosts.isEmpty()) {
                EmptyHosts(onAddHost)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(hosts, key = { it.id }) { host ->
                        HostCard(host, onConnect = { onConnect(host) }, onEdit = { onEditHost(host) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHosts(onAddHost: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No hosts yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Add your EC2 or MacBook to start driving your AI agents.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp).clickable { onAddHost() },
            )
        }
    }
}

@Composable
private fun HostCard(host: HostEntity, onConnect: () -> Unit, onEdit: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onConnect() }) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(host.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val target = if (host.connectMode == ConnectMode.RELAY) {
                "via relay: ${host.relayDeviceId ?: "—"}"
            } else {
                "${host.username}@${host.hostName}:${host.port}"
            }
            Text(target, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Edit",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp).clickable { onEdit() },
            )
        }
    }
}

package dev.remux.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.remux.app.data.HostEntity
import dev.remux.app.ui.AppContainer
import dev.remux.app.ui.components.AiCommandPanel
import dev.remux.app.ui.components.ShortcutToolbar
import dev.remux.app.ui.components.TerminalView
import dev.remux.app.ui.terminal.ConnState
import dev.remux.app.ui.terminal.TerminalController
import dev.remux.core.input.KeyMapping

@Composable
fun TerminalScreen(
    container: AppContainer,
    hostId: Long,
    onBack: () -> Unit,
) {
    var host by remember { mutableStateOf<HostEntity?>(null) }
    val controller = remember { TerminalController(container) }
    var input by remember { mutableStateOf("") }
    var fontSize by remember { mutableFloatStateOf(14f) }

    LaunchedEffect(hostId) {
        val h = container.hostDao.byId(hostId)
        host = h
        if (h != null) controller.connect(h)
    }
    DisposableEffect(Unit) { onDispose { controller.close() } }

    Column(modifier = Modifier.fillMaxSize()) {
        // Status bar
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                if (controller.state == ConnState.CONNECTING) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(host?.label ?: "…", style = MaterialTheme.typography.titleSmall)
                Text(
                    statusLabel(controller.state, controller.statusMessage),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        TerminalView(
            terminal = controller.terminal,
            revision = controller.revision,
            fontSizeSp = fontSize,
            onFontScale = { fontSize = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        AiCommandPanel(
            onInsert = { input += it },
            onSend = { controller.send(it + "\n") },
            modifier = Modifier.fillMaxWidth(),
        )

        ShortcutToolbar(
            ctrlActive = controller.ctrlActive,
            onSendBytes = { controller.send(it) },
            onToggleCtrl = { controller.ctrlActive = !controller.ctrlActive },
            onInsert = { input += it },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { newValue ->
                    if (controller.ctrlActive && newValue.length == 1) {
                        controller.send(KeyMapping.ctrl(newValue[0]))
                        controller.ctrlActive = false
                        input = ""
                    } else {
                        input = newValue
                    }
                },
                label = { Text("Send to agent (multi-line)") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f),
                maxLines = 5,
            )
            Button(
                onClick = {
                    controller.send(input + "\n")
                    input = ""
                },
                enabled = controller.state == ConnState.CONNECTED,
            ) { Text("Send") }
        }
    }
}

private fun statusLabel(state: ConnState, message: String): String = when (state) {
    ConnState.IDLE -> "idle"
    ConnState.CONNECTING -> message.ifBlank { "connecting…" }
    ConnState.CONNECTED -> "connected"
    ConnState.ERROR -> message.ifBlank { "error" }
    ConnState.DISCONNECTED -> "disconnected"
}

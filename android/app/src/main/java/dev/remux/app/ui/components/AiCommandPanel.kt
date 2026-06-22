package dev.remux.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.remux.core.ai.AiCommand
import dev.remux.core.ai.AiCommandCatalog

/**
 * Quick panel of AI-agent commands (`/compact`, `/clear`, `yes`, `no`, …).
 * Tapping a chip either inserts its text into the input or sends it immediately
 * (with Enter), per [AiCommand.sendImmediately].
 */
@Composable
fun AiCommandPanel(
    onInsert: (String) -> Unit,
    onSend: (String) -> Unit,
    commands: List<AiCommand> = AiCommandCatalog.commands,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (cmd in commands) {
                AssistChip(
                    onClick = { if (cmd.sendImmediately) onSend(cmd.insert) else onInsert(cmd.insert) },
                    label = { Text(cmd.label) },
                )
            }
        }
    }
}

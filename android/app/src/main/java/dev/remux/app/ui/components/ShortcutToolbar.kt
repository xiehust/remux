package dev.remux.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.remux.core.input.KeyMapping
import dev.remux.core.input.ToolbarKey
import dev.remux.core.input.ToolbarLayout

/**
 * The customizable mobile shortcut toolbar. Ctrl is a sticky modifier: tap it,
 * then tap a letter on the keyboard / another key to send the Ctrl-combo.
 * All byte sequences come from [KeyMapping] (unit-tested in :core).
 *
 * @param ctrlActive whether the sticky Ctrl modifier is currently armed
 * @param onSendBytes emit raw bytes to the SSH session
 * @param onToggleCtrl toggle the sticky Ctrl modifier
 * @param onInsert insert literal text into the input box
 */
@Composable
fun ShortcutToolbar(
    ctrlActive: Boolean,
    onSendBytes: (String) -> Unit,
    onToggleCtrl: () -> Unit,
    onInsert: (String) -> Unit,
    keys: List<ToolbarKey> = ToolbarLayout.default,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (key in keys) {
                when (key) {
                    is ToolbarKey.Modifier -> FilterChip(
                        selected = ctrlActive,
                        onClick = onToggleCtrl,
                        label = { Text(key.label) },
                    )
                    is ToolbarKey.Send -> FilterChip(
                        selected = false,
                        onClick = { onSendBytes(key.bytes) },
                        label = { Text(key.label) },
                    )
                    is ToolbarKey.Insert -> FilterChip(
                        selected = false,
                        onClick = { onInsert(key.text) },
                        label = { Text(key.label) },
                    )
                }
            }
        }
    }
}

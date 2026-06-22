package dev.remux.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.remux.core.terminal.Terminal

/**
 * Renders a [Terminal] screen buffer as a monospace grid with per-cell colors
 * and attributes. Pinch-to-zoom adjusts the font size via [onFontScale].
 *
 * [revision] is read so the view recomposes whenever the controller feeds new
 * bytes into the terminal.
 */
@Composable
fun TerminalView(
    terminal: Terminal,
    revision: Int,
    fontSizeSp: Float,
    onFontScale: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val defaultFg = MaterialTheme.colorScheme.onSurface
    val defaultBg = MaterialTheme.colorScheme.surface

    // Read revision so recomposition is tied to terminal updates.
    @Suppress("UNUSED_EXPRESSION") revision

    val rows = remember(revision, terminal.rows, terminal.cols, fontSizeSp) {
        (0 until terminal.rows).map { r -> buildRowText(terminal, r, defaultFg.value, defaultBg.value) }
    }

    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .fillMaxSize()
            .background(defaultBg)
            .verticalScroll(scroll)
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom != 1f) onFontScale((fontSizeSp * zoom).coerceIn(8f, 32f))
                }
            }
            .padding(4.dp),
    ) {
        for (row in rows) {
            Text(
                text = row,
                fontFamily = FontFamily.Monospace,
                fontSize = fontSizeSp.sp,
                softWrap = false,
            )
        }
    }
}

private fun buildRowText(terminal: Terminal, row: Int, defaultFg: ULong, defaultBg: ULong): AnnotatedString =
    buildAnnotatedString {
        for (c in 0 until terminal.cols) {
            val cell = terminal.cellAt(row, c)
            if (cell.widePlaceholder) continue
            val fg = AnsiPalette.foreground(cell.fg, androidx.compose.ui.graphics.Color(defaultFg))
            val ch = if (cell.codePoint == 0) ' ' else cell.char
            withStyle(
                SpanStyle(
                    color = fg,
                    fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal,
                ),
            ) {
                append(ch)
            }
        }
    }

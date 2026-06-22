package dev.remux.app.ui.components

import androidx.compose.ui.graphics.Color
import dev.remux.core.terminal.TerminalColor

/** Maps terminal colors (default / 256-indexed / truecolor) to Compose colors. */
object AnsiPalette {
    // The 16 ANSI base colors (a balanced dark-theme-friendly palette).
    private val base16 = intArrayOf(
        0x1B1F1D, 0xE05561, 0x7CF5C4, 0xE5C07B, 0x61AFEF, 0xC678DD, 0x56B6C2, 0xABB2BF,
        0x5C6370, 0xFF7A85, 0xA6F0D4, 0xF0D399, 0x80C4FF, 0xD79BEC, 0x7FD6E0, 0xE6E9EF,
    )

    fun foreground(c: TerminalColor, default: Color): Color = resolve(c, default)
    fun background(c: TerminalColor, default: Color): Color = resolve(c, default)

    private fun resolve(c: TerminalColor, default: Color): Color = when (c) {
        is TerminalColor.Default -> default
        is TerminalColor.Rgb -> Color(c.r, c.g, c.b)
        is TerminalColor.Indexed -> indexed(c.index, default)
    }

    private fun indexed(i: Int, default: Color): Color = when (i) {
        in 0..15 -> Color(0xFF000000.toInt() or base16[i])
        in 16..231 -> {
            val n = i - 16
            val r = (n / 36) % 6
            val g = (n / 6) % 6
            val b = n % 6
            Color(scale(r), scale(g), scale(b))
        }
        in 232..255 -> {
            val v = 8 + (i - 232) * 10
            Color(v, v, v)
        }
        else -> default
    }

    private fun scale(v: Int): Int = if (v == 0) 0 else 55 + v * 40
}

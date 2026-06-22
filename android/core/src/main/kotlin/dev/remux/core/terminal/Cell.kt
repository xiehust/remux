package dev.remux.core.terminal

/**
 * One terminal grid cell: a code point plus its rendered style.
 *
 * Wide (CJK / fullwidth) characters occupy two cells: the lead cell holds the
 * code point with [wide] = true, and the following cell is a [widePlaceholder]
 * with no glyph of its own.
 */
data class Cell(
    val codePoint: Int = ' '.code,
    val fg: TerminalColor = TerminalColor.Default,
    val bg: TerminalColor = TerminalColor.Default,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val reverse: Boolean = false,
    val wide: Boolean = false,
    val widePlaceholder: Boolean = false,
) {
    val char: Char get() = if (codePoint in 0..0xFFFF) codePoint.toChar() else '?'

    companion object {
        val EMPTY = Cell()
    }
}

/** The current drawing pen (active SGR attributes) applied to printed cells. */
data class Pen(
    val fg: TerminalColor = TerminalColor.Default,
    val bg: TerminalColor = TerminalColor.Default,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val reverse: Boolean = false,
)

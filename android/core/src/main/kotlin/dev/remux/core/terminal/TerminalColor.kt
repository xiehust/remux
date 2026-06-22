package dev.remux.core.terminal

/**
 * A terminal cell color. Covers the three SGR color models: the terminal
 * default, the 256-color indexed palette (which subsumes the 16 ANSI colors),
 * and 24-bit truecolor.
 */
sealed interface TerminalColor {
    data object Default : TerminalColor

    /** Indexed palette color, 0..255 (0..15 = the 16 ANSI colors). */
    data class Indexed(val index: Int) : TerminalColor

    /** 24-bit truecolor. */
    data class Rgb(val r: Int, val g: Int, val b: Int) : TerminalColor
}

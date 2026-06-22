package dev.remux.core.input

/**
 * Maps mobile shortcut-toolbar keys to the exact byte sequences a terminal
 * expects, so the Compose toolbar (phase 9) is a thin caller over tested logic.
 *
 * Sequences follow xterm conventions (control codes, CSI/SS3 escape sequences).
 */
object KeyMapping {
    const val ESC = "\u001b"
    const val TAB = "\t"
    const val ENTER = "\r"
    const val BACKSPACE = "\u007f"
    const val SPACE = " "

    const val ARROW_UP = "\u001b[A"
    const val ARROW_DOWN = "\u001b[B"
    const val ARROW_RIGHT = "\u001b[C"
    const val ARROW_LEFT = "\u001b[D"
    const val HOME = "\u001b[H"
    const val END = "\u001b[F"
    const val PAGE_UP = "\u001b[5~"
    const val PAGE_DOWN = "\u001b[6~"

    /**
     * The byte for Ctrl + [c]. For letters this is `code & 0x1f` (Ctrl-A=0x01,
     * Ctrl-C=0x03, …); a few common symbol combos are mapped explicitly.
     */
    fun ctrl(c: Char): String {
        val code = when (c) {
            ' ' -> 0x00
            '[' -> 0x1b
            '\\' -> 0x1c
            ']' -> 0x1d
            '^' -> 0x1e
            '_' -> 0x1f
            else -> c.uppercaseChar().code and 0x1f
        }
        return code.toChar().toString()
    }

    /** Function-key sequences (F1–F4 use SS3, F5–F12 use CSI ~). */
    fun function(n: Int): String = when (n) {
        1 -> "\u001bOP"
        2 -> "\u001bOQ"
        3 -> "\u001bOR"
        4 -> "\u001bOS"
        5 -> "\u001b[15~"
        6 -> "\u001b[17~"
        7 -> "\u001b[18~"
        8 -> "\u001b[19~"
        9 -> "\u001b[20~"
        10 -> "\u001b[21~"
        11 -> "\u001b[23~"
        12 -> "\u001b[24~"
        else -> ""
    }
}

/** A toolbar key: its label and how it behaves when tapped. */
sealed interface ToolbarKey {
    val label: String

    /** Emits a fixed byte sequence immediately (Tab, Esc, arrows, …). */
    data class Send(override val label: String, val bytes: String) : ToolbarKey

    /** A sticky modifier (Ctrl) that combines with the next key. */
    data class Modifier(override val label: String) : ToolbarKey

    /** Inserts literal text into the input box rather than sending. */
    data class Insert(override val label: String, val text: String) : ToolbarKey
}

/** The default mobile shortcut toolbar layout. */
object ToolbarLayout {
    val default: List<ToolbarKey> = listOf(
        ToolbarKey.Modifier("Ctrl"),
        ToolbarKey.Send("Tab", KeyMapping.TAB),
        ToolbarKey.Send("Esc", KeyMapping.ESC),
        ToolbarKey.Send("↑", KeyMapping.ARROW_UP),
        ToolbarKey.Send("↓", KeyMapping.ARROW_DOWN),
        ToolbarKey.Send("←", KeyMapping.ARROW_LEFT),
        ToolbarKey.Send("→", KeyMapping.ARROW_RIGHT),
        ToolbarKey.Insert("|", "|"),
        ToolbarKey.Insert("/", "/"),
        ToolbarKey.Insert("~", "~"),
        ToolbarKey.Insert("-", "-"),
        ToolbarKey.Send("Home", KeyMapping.HOME),
        ToolbarKey.Send("End", KeyMapping.END),
    )
}

package dev.remux.core.tmux

/** A tmux session as reported by `tmux list-sessions`. */
data class TmuxSession(
    val name: String,
    val windows: Int,
    val attached: Boolean,
)

/** A tmux window as reported by `tmux list-windows`. */
data class TmuxWindow(
    val index: Int,
    val name: String,
    val active: Boolean,
    val panes: Int,
)

/**
 * Parses the default output of `tmux list-sessions` and `tmux list-windows`.
 * Used to drive the tmux detect/attach UI. Robust to 0..N entries.
 */
object TmuxParser {

    private val windowsCount = Regex("""(\d+)\s+windows?""")
    private val panesCount = Regex("""(\d+)\s+panes?""")

    fun parseSessions(output: String): List<TmuxSession> =
        output.lineSequence().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@mapNotNull null
            val colon = line.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            val name = line.substring(0, colon)
            val rest = line.substring(colon + 1)
            val windows = windowsCount.find(rest)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val attached = rest.contains("(attached)")
            TmuxSession(name, windows, attached)
        }.toList()

    fun parseWindows(output: String): List<TmuxWindow> =
        output.lineSequence().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@mapNotNull null
            val colon = line.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            val index = line.substring(0, colon).trim().toIntOrNull() ?: return@mapNotNull null
            val rest = line.substring(colon + 1).trim()
            // name is the first token, possibly suffixed with * (active) or - (last)
            val firstToken = rest.substringBefore(' ').ifEmpty { rest }
            val active = firstToken.endsWith("*")
            val name = firstToken.trimEnd('*', '-')
            val panes = panesCount.find(rest)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            TmuxWindow(index, name, active, panes)
        }.toList()
}

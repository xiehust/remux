package dev.remux.core.ssh

/** Effective SSH connection settings resolved for a single host alias. */
data class SshHostConfig(
    val alias: String,
    val hostName: String? = null,
    val port: Int = 22,
    val user: String? = null,
    val identityFile: String? = null,
    val proxyJump: String? = null,
)

/**
 * A minimal parser for the OpenSSH `~/.ssh/config` format, supporting the subset
 * Remux needs: Host blocks with HostName, Port, User, IdentityFile and
 * ProxyJump, including `*`/`?` host-pattern wildcards. Follows ssh_config's
 * first-value-wins semantics.
 */
object SshConfigParser {

    data class Block(val patterns: List<String>, val settings: List<Pair<String, String>>)

    fun parseBlocks(text: String): List<Block> {
        val blocks = mutableListOf<Block>()
        var patterns = listOf("*") // options before the first Host apply globally
        var settings = mutableListOf<Pair<String, String>>()

        fun flush() {
            blocks.add(Block(patterns, settings))
            settings = mutableListOf()
        }

        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val (keyword, value) = splitKeyValue(line) ?: continue
            if (keyword.equals("Host", ignoreCase = true)) {
                flush()
                patterns = value.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            } else {
                settings.add(keyword.lowercase() to value.trim())
            }
        }
        flush()
        return blocks
    }

    /** Resolves the effective config for [alias] from the given config text. */
    fun resolve(text: String, alias: String): SshHostConfig {
        val acc = LinkedHashMap<String, String>()
        for (block in parseBlocks(text)) {
            if (block.patterns.any { matches(it, alias) }) {
                for ((k, v) in block.settings) acc.putIfAbsent(k, v)
            }
        }
        return SshHostConfig(
            alias = alias,
            hostName = acc["hostname"],
            port = acc["port"]?.toIntOrNull() ?: 22,
            user = acc["user"],
            identityFile = acc["identityfile"],
            proxyJump = acc["proxyjump"],
        )
    }

    /** OpenSSH host-pattern match: `*` matches any run, `?` matches one char. */
    fun matches(pattern: String, alias: String): Boolean {
        if (pattern == "*") return true
        val regex = buildString {
            append('^')
            for (c in pattern) {
                when (c) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(c.toString()))
                }
            }
            append('$')
        }
        return Regex(regex).matches(alias)
    }

    private fun splitKeyValue(line: String): Pair<String, String>? {
        // "Keyword value" or "Keyword=value"
        val eq = line.indexOf('=')
        val ws = line.indexOfFirst { it == ' ' || it == '\t' }
        val sep = when {
            eq >= 0 && (ws < 0 || eq < ws) -> eq
            ws >= 0 -> ws
            else -> return null
        }
        val key = line.substring(0, sep).trim()
        val value = line.substring(sep + 1).trim()
        if (key.isEmpty()) return null
        return key to value
    }
}

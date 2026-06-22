package dev.remux.core.ai

/**
 * A quick-panel command optimized for driving AI coding agents (Claude Code,
 * Codex) from the phone. [insert] is the literal text inserted into the input;
 * [sendImmediately] commands are sent with a trailing newline (Enter) rather
 * than just staged in the input box.
 */
data class AiCommand(
    val label: String,
    val insert: String,
    val description: String,
    val sendImmediately: Boolean = false,
)

/**
 * The default AI-agent command catalog surfaced in the mobile quick panel.
 * Covers the most common interactions when supervising an agent from mobile.
 */
object AiCommandCatalog {
    val commands: List<AiCommand> = listOf(
        AiCommand("/compact", "/compact", "Compact the conversation history", sendImmediately = true),
        AiCommand("/clear", "/clear", "Clear the conversation context", sendImmediately = true),
        AiCommand("/help", "/help", "Show available commands", sendImmediately = true),
        AiCommand("/model", "/model ", "Switch the model"),
        AiCommand("/undo", "/undo", "Undo the last change", sendImmediately = true),
        AiCommand("yes", "yes", "Confirm / approve", sendImmediately = true),
        AiCommand("no", "no", "Decline / reject", sendImmediately = true),
        AiCommand("1", "1", "Pick option 1", sendImmediately = true),
        AiCommand("2", "2", "Pick option 2", sendImmediately = true),
        AiCommand("continue", "continue", "Continue", sendImmediately = true),
    )

    fun byLabel(label: String): AiCommand? = commands.firstOrNull { it.label == label }
}

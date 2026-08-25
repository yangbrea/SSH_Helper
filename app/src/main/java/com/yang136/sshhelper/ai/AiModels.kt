package com.yang136.sshhelper.ai

import java.util.UUID

enum class AiMessageRole(val wireName: String) {
    SYSTEM("system"), USER("user"), ASSISTANT("assistant"), TOOL("tool"),
}

data class AiToolCall(val id: String, val name: String, val arguments: String)

data class AiChatMessage(
    val role: AiMessageRole,
    val content: String? = null,
    val toolCalls: List<AiToolCall> = emptyList(),
    val toolCallId: String? = null,
)

data class AiChatRequest(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val messages: List<AiChatMessage>,
    val timeoutSeconds: Long = 90,
    val enableTools: Boolean = true,
)

sealed interface AiStreamEvent {
    data class TextDelta(val text: String) : AiStreamEvent
    data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val name: String? = null,
        val argumentsDelta: String = "",
    ) : AiStreamEvent
    data class Completed(val finishReason: String? = null) : AiStreamEvent
}

enum class CommandRisk { LOW, MEDIUM, HIGH, UNKNOWN }

enum class CommandExecutionStatus {
    PENDING, RUNNING, SUCCEEDED, FAILED, TIMED_OUT, INTERRUPTED, STOPPED, DISCONNECTED, EXPIRED,
}

data class CommandSuggestion(
    val id: String = UUID.randomUUID().toString(),
    val command: String,
    val summary: String,
    val expectedOutcome: String = "",
    val language: String = "shell",
    val risk: CommandRisk = CommandRisk.UNKNOWN,
    val status: CommandExecutionStatus = CommandExecutionStatus.PENDING,
    val sourceToolCallId: String? = null,
)

sealed interface AiContentBlock {
    val id: String
    data class Markdown(
        override val id: String = UUID.randomUUID().toString(),
        val text: String,
        val streaming: Boolean = false,
    ) : AiContentBlock
    data class Command(
        override val id: String = UUID.randomUUID().toString(),
        val suggestion: CommandSuggestion,
    ) : AiContentBlock
    data class CommandResult(
        override val id: String = UUID.randomUUID().toString(),
        val suggestionId: String,
        val output: String,
        val exitCode: Int? = null,
        val status: CommandExecutionStatus,
        val truncated: Boolean = false,
    ) : AiContentBlock
    data class Error(
        override val id: String = UUID.randomUUID().toString(),
        val message: String,
    ) : AiContentBlock
}

data class AiConversationEntry(
    val id: String = UUID.randomUUID().toString(),
    val role: AiMessageRole,
    val blocks: List<AiContentBlock>,
)

data class AiConversationState(
    val entries: List<AiConversationEntry> = emptyList(),
    val generating: Boolean = false,
    val runningSuggestionId: String? = null,
    val waitingForCommand: Boolean = false,
    val error: String? = null,
)

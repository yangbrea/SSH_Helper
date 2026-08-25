package com.yang136.sshhelper.ai

import com.yang136.sshhelper.data.AuthType
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.settings.AppSettings
import com.yang136.sshhelper.ssh.ConnectionState
import com.yang136.sshhelper.ssh.ManagedSessionState
import com.yang136.sshhelper.ssh.SessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAgentManagerTest {
    private val id = SessionId("session")
    private val sessionState = ManagedSessionState(
        id = id,
        profile = HostProfile(1, "host", "localhost", username = "user", authType = AuthType.PASSWORD),
        displayName = "host",
        connection = ConnectionState.Connected("connected"),
    )
    private val settings = AppSettings(aiApiKey = "key", aiModel = "model", aiBaseUrl = "https://example.test/v1")

    @Test
    fun toolSuggestionRequiresConfirmationThenAutomaticallyAnalyzesResult() = runBlocking {
        val client = QueueAiClient(
            listOf(
                listOf(
                    AiStreamEvent.TextDelta("先检查磁盘。"),
                    AiStreamEvent.ToolCallDelta(0, "call_1", "propose_terminal_command", "{\"command\":\"df "),
                    AiStreamEvent.ToolCallDelta(0, argumentsDelta = "-h\",\"summary\":\"检查磁盘\",\"expectedOutcome\":\"显示用量\"}"),
                    AiStreamEvent.Completed("tool_calls"),
                ),
                listOf(AiStreamEvent.TextDelta("磁盘状态正常。"), AiStreamEvent.Completed("stop")),
            ),
        )
        val runner = FakeAgentRunner(TerminalCommandResult(CommandExecutionStatus.SUCCEEDED, "disk ok", "disk ok", 0))
        val sessions = MutableStateFlow(listOf(sessionState))
        val manager = manager(sessions, client, runner)

        manager.send(id, "看看磁盘", settings)
        await { !manager.state(id).value.generating }
        val suggestion = suggestions(manager).single()
        assertEquals(CommandRisk.LOW, suggestion.risk)
        assertEquals(CommandExecutionStatus.PENDING, suggestion.status)

        manager.confirmCommand(id, suggestion.id)
        await { manager.state(id).value.entries.any { entry -> entry.blocks.any { it is AiContentBlock.Markdown && it.text.contains("磁盘状态正常") } } }

        assertEquals(listOf("df -h"), runner.commands)
        assertTrue(client.requests[1].messages.any { it.role == AiMessageRole.TOOL && it.toolCallId == "call_1" })
        assertEquals(CommandExecutionStatus.SUCCEEDED, suggestions(manager).single().status)
    }

    @Test
    fun executingOneMarkdownSuggestionExpiresTheOthers() = runBlocking {
        val client = QueueAiClient(listOf(listOf(
            AiStreamEvent.TextDelta("```bash\npwd\n```\n```sh\nid\n```"),
            AiStreamEvent.Completed("stop"),
        )))
        val runner = FakeAgentRunner(TerminalCommandResult(CommandExecutionStatus.STOPPED, "", ""))
        val manager = manager(MutableStateFlow(listOf(sessionState)), client, runner)

        manager.send(id, "建议命令", settings)
        await { !manager.state(id).value.generating }
        val before = suggestions(manager)
        assertEquals(2, before.size)

        manager.confirmCommand(id, before.first().id)
        await { !manager.state(id).value.waitingForCommand }

        val after = suggestions(manager)
        assertEquals(CommandExecutionStatus.STOPPED, after.first { it.id == before.first().id }.status)
        assertEquals(CommandExecutionStatus.EXPIRED, after.first { it.id == before.last().id }.status)
        assertEquals(1, client.requests.size)
    }

    @Test
    fun cancellationKeepsPartialTextWithoutOrdinaryError() = runBlocking {
        val client = object : AiClient {
            override fun stream(request: AiChatRequest): Flow<AiStreamEvent> = flow {
                emit(AiStreamEvent.TextDelta("partial"))
                awaitCancellation()
            }
        }
        val manager = manager(MutableStateFlow(listOf(sessionState)), client, FakeAgentRunner())

        manager.send(id, "long", settings)
        await { manager.state(id).value.generating }
        manager.cancelGeneration(id)
        await { !manager.state(id).value.generating }

        assertNull(manager.state(id).value.error)
        assertTrue(manager.state(id).value.entries.any { entry -> entry.blocks.any { it is AiContentBlock.Markdown && it.text == "partial" } })
    }

    @Test
    fun removingSshSessionCancelsAndClearsConversation() = runBlocking {
        val sessions = MutableStateFlow(listOf(sessionState))
        val manager = manager(sessions, QueueAiClient(listOf(listOf(AiStreamEvent.TextDelta("answer"), AiStreamEvent.Completed()))), FakeAgentRunner())
        val state = manager.state(id)
        manager.send(id, "question", settings)
        await { state.value.entries.isNotEmpty() && !state.value.generating }

        sessions.value = emptyList()
        await { state.value.entries.isEmpty() }

        assertEquals(AiConversationState(), state.value)
    }

    @Test
    fun disablingAmbientContextDoesNotSuppressConfirmedCommandResults() = runBlocking {
        val client = QueueAiClient(
            listOf(
                listOf(
                    AiStreamEvent.ToolCallDelta(0, "call", "propose_terminal_command", "{\"command\":\"pwd\",\"summary\":\"路径\",\"expectedOutcome\":\"当前目录\"}"),
                    AiStreamEvent.Completed("tool_calls"),
                ),
                listOf(AiStreamEvent.TextDelta("done"), AiStreamEvent.Completed()),
            ),
        )
        val manager = manager(
            MutableStateFlow(listOf(sessionState)),
            client,
            FakeAgentRunner(TerminalCommandResult(CommandExecutionStatus.SUCCEEDED, "/tmp", "/tmp", 0)),
        )
        val noContext = settings.copy(aiSendContext = false)

        manager.send(id, "where", noContext)
        await { !manager.state(id).value.generating }
        assertFalse(client.requests.first().messages.any { it.content.orEmpty().contains("ambient terminal") })
        manager.confirmCommand(id, suggestions(manager).single().id)
        await { client.requests.size == 2 }

        assertTrue(client.requests[1].messages.any { it.role == AiMessageRole.TOOL && it.content.orEmpty().contains("/tmp") })
    }

    private fun manager(
        sessions: MutableStateFlow<List<ManagedSessionState>>,
        client: AiClient,
        runner: TerminalAgentCommandRunner,
    ) = AiAgentManager(
        sessions = sessions,
        client = client,
        commandRunner = runner,
        recentOutput = { _, _ -> "ambient terminal".encodeToByteArray() },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    private fun suggestions(manager: AiAgentManager): List<CommandSuggestion> =
        manager.state(id).value.entries.flatMap(AiConversationEntry::blocks)
            .filterIsInstance<AiContentBlock.Command>().map(AiContentBlock.Command::suggestion)

    private suspend fun await(predicate: () -> Boolean) {
        withTimeout(2_000) {
            while (!predicate()) delay(1)
        }
    }
}

private class QueueAiClient(responses: List<List<AiStreamEvent>>) : AiClient {
    private val responses = ArrayDeque(responses)
    val requests = mutableListOf<AiChatRequest>()

    override fun stream(request: AiChatRequest): Flow<AiStreamEvent> = flow {
        requests += request
        responses.removeFirst().forEach { emit(it) }
    }
}

private class FakeAgentRunner(
    private val result: TerminalCommandResult = TerminalCommandResult(CommandExecutionStatus.STOPPED, "", ""),
) : TerminalAgentCommandRunner {
    val commands = mutableListOf<String>()

    override suspend fun execute(
        sessionId: SessionId,
        command: String,
        timeoutMillis: Long,
        onUpdate: (TerminalCommandUpdate) -> Unit,
    ): TerminalCommandResult {
        commands += command
        return result
    }

    override suspend fun interrupt(sessionId: SessionId) = Unit
    override fun stopWaiting(sessionId: SessionId) = Unit
    override fun clear(sessionId: SessionId) = Unit
}

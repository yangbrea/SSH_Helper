package com.yang136.sshhelper.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yang136.sshhelper.ai.AiContentBlock
import com.yang136.sshhelper.ai.AiConversationEntry
import com.yang136.sshhelper.ai.AiConversationState
import com.yang136.sshhelper.ai.AiMessageRole
import com.yang136.sshhelper.ai.CommandRisk
import com.yang136.sshhelper.ai.CommandSuggestion
import com.yang136.sshhelper.data.AuthType
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.settings.AppSettings
import com.yang136.sshhelper.ssh.ConnectionState
import com.yang136.sshhelper.ssh.ManagedSessionState
import com.yang136.sshhelper.ssh.SessionId
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AiBubbleTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rendersCodeAndRequiresSecondConfirmationForHighRiskCommand() {
        val command = CommandSuggestion(
            id = "danger",
            command = "rm -rf /tmp/demo",
            summary = "删除测试目录",
            risk = CommandRisk.HIGH,
        )
        val state = MutableStateFlow(
            AiConversationState(
                entries = listOf(
                    AiConversationEntry(
                        role = AiMessageRole.ASSISTANT,
                        blocks = listOf(
                            AiContentBlock.Markdown(text = "说明\n```bash\nls -la\n```"),
                            AiContentBlock.Command(suggestion = command),
                        ),
                    ),
                ),
            ),
        )
        var confirmed: String? = null
        compose.setContent {
            MaterialTheme {
                AiBubble(
                    session = session(),
                    stateFlow = state,
                    settings = AppSettings(aiApiKey = "key"),
                    onSend = {},
                    onConfirmCommand = { confirmed = it },
                    onFillTerminal = {},
                    onCancelGeneration = {},
                    onInterruptCommand = {},
                    onStopWaiting = {},
                    onClear = {},
                    onOpenSettings = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithContentDescription("打开 Terminal Agent").performClick()
        compose.onNodeWithTag("agent_code_block").assertExists()
        compose.onNodeWithTag("agent_command_card").assertExists()
        compose.onNodeWithText("核对并执行").performClick()
        compose.onNodeWithText("确认执行高风险命令？").assertExists()
        assertEquals(null, confirmed)
        compose.onNodeWithText("仍然执行").performClick()
        assertEquals("danger", confirmed)
    }

    private fun session() = ManagedSessionState(
        id = SessionId("session"),
        profile = HostProfile(1, "host", "localhost", username = "user", authType = AuthType.PASSWORD),
        displayName = "host",
        connection = ConnectionState.Connected("connected"),
    )
}

package com.yang136.sshhelper.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HighlightOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yang136.sshhelper.ai.AiContext
import com.yang136.sshhelper.ai.AiException
import com.yang136.sshhelper.ai.AiRequest
import com.yang136.sshhelper.ai.OkHttpAiClient
import com.yang136.sshhelper.settings.AppSettings
import com.yang136.sshhelper.ssh.ManagedSessionState
import kotlinx.coroutines.launch

/**
 * In-app floating bubble on the terminal page. Collapsed it is a draggable pill; expanded it is a
 * single-turn chat panel whose suggestions can be pasted into the current terminal input line.
 * No agent loop, no automatic command execution. Long-press the collapsed pill or tap the close
 * button in the panel to hide the bubble for this terminal session.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AiBubble(
    session: ManagedSessionState?,
    settings: AppSettings,
    recentContext: () -> ByteArray,
    onFillTerminal: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var response by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val aiClient = remember { OkHttpAiClient() }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || loading) return
        if (settings.aiApiKey.isBlank()) {
            error = "未配置 API Key，请先在设置中填写"
            return
        }
        loading = true
        error = null
        response = null
        scope.launch {
            try {
                val terminalContext = if (settings.aiSendContext) AiContext.recentTerminalText(recentContext()) else ""
                val systemPrompt = "你是嵌入在 SSH 终端里的 AI 助手。结合给定的终端上下文回答；当用户需要命令时，直接给出可执行命令，保持简洁。"
                val userMessage = buildString {
                    if (terminalContext.isNotBlank()) append("最近终端输出：\n$terminalContext\n\n")
                    append("用户：$text")
                }
                val result = aiClient.ask(
                    AiRequest(
                        baseUrl = settings.aiBaseUrl,
                        apiKey = settings.aiApiKey,
                        model = settings.aiModel,
                        systemPrompt = systemPrompt,
                        userMessage = userMessage,
                    ),
                )
                response = result.text
            } catch (failure: AiException) {
                error = failure.message
            } catch (failure: Exception) {
                error = "请求失败：${failure.message ?: "未知错误"}"
            } finally {
                loading = false
            }
        }
    }

    Box(modifier = modifier) {
        if (expanded) {
            Surface(
                modifier = Modifier.widthIn(max = 340.dp).heightIn(max = 460.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 16.dp,
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text("AI 助手", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(session?.displayName ?: "未连接", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { expanded = false }) { Icon(Icons.Default.Close, "收起") }
                        IconButton(onClick = onClose) { Icon(Icons.Default.HighlightOff, "关闭悬浮窗") }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(28.dp))
                            }
                            error != null -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                if (error.orEmpty().contains("API Key")) {
                                    TextButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, null, Modifier.size(16.dp)); Text("去设置", Modifier.padding(start = 4.dp)) }
                                }
                            }
                            response != null -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(response.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                                FilledTonalButton(onClick = { onFillTerminal(AiContext.extractCommand(response.orEmpty())) }) {
                                    Icon(Icons.Default.ContentPaste, null, Modifier.size(16.dp))
                                    Text("填入终端", Modifier.padding(start = 6.dp))
                                }
                            }
                            else -> Text(
                                "结合最近终端输出提问，例如「查看磁盘占用最多的目录」或「这个报错是什么问题」",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("输入问题…") },
                            maxLines = 3,
                        )
                        IconButton(onClick = ::send, enabled = input.isNotBlank() && !loading) {
                            Icon(Icons.Default.Send, "发送", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        } else {
            Surface(
                modifier = Modifier.size(54.dp)
                    .combinedClickable(onClick = { expanded = true }, onLongClick = onClose),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 8.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.AutoAwesome, "打开 AI 助手", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}

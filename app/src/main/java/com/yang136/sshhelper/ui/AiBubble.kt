package com.yang136.sshhelper.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yang136.sshhelper.ai.AiContentBlock
import com.yang136.sshhelper.ai.AiConversationEntry
import com.yang136.sshhelper.ai.AiConversationState
import com.yang136.sshhelper.ai.AiMessageRole
import com.yang136.sshhelper.ai.CommandExecutionStatus
import com.yang136.sshhelper.ai.CommandRisk
import com.yang136.sshhelper.ai.CommandSuggestion
import com.yang136.sshhelper.ai.MarkdownBlock
import com.yang136.sshhelper.ai.MarkdownContentParser
import com.yang136.sshhelper.ai.MarkdownInline
import com.yang136.sshhelper.ai.validateCommand
import com.yang136.sshhelper.settings.AppSettings
import com.yang136.sshhelper.ssh.ManagedSessionState
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AiBubble(
    session: ManagedSessionState,
    stateFlow: StateFlow<AiConversationState>,
    settings: AppSettings,
    onSend: (String) -> Unit,
    onConfirmCommand: (String) -> Unit,
    onFillTerminal: (String) -> Unit,
    onCancelGeneration: () -> Unit,
    onInterruptCommand: () -> Unit,
    onStopWaiting: () -> Unit,
    onAnalyzePartial: (String) -> Unit,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by stateFlow.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    var input by remember(session.id) { mutableStateOf("") }
    var highRiskCommand by remember(session.id) { mutableStateOf<CommandSuggestion?>(null) }
    var bubbleOffsetX by remember { mutableFloatStateOf(0f) }
    var bubbleOffsetY by remember { mutableFloatStateOf(0f) }

    fun send() {
        val prompt = input.trim()
        if (prompt.isNotEmpty() && !state.generating && !state.waitingForCommand) {
            onSend(prompt)
            input = ""
        }
    }

    Box(modifier) {
        Box(
            Modifier
                .offset { IntOffset(bubbleOffsetX.roundToInt(), bubbleOffsetY.roundToInt()) }
                .size(52.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        bubbleOffsetX += dragAmount.x
                        bubbleOffsetY += dragAmount.y
                    }
                }
                .combinedClickable(onClick = { expanded = true }, onLongClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.AutoAwesome, "打开 Terminal Agent", tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }

    if (expanded) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { expanded = false },
            sheetState = sheetState,
            modifier = Modifier.fillMaxHeight(0.86f),
        ) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentHeader(
                    sessionName = session.displayName,
                    generating = state.generating,
                    onCancelGeneration = onCancelGeneration,
                    onClear = onClear,
                    onCollapse = { expanded = false },
                    onClose = onClose,
                )
                HorizontalDivider()
                AgentConversation(
                    state = state,
                    onConfirm = { suggestion ->
                        if (suggestion.risk == CommandRisk.HIGH) highRiskCommand = suggestion
                        else onConfirmCommand(suggestion.id)
                    },
                    onFillTerminal = onFillTerminal,
                    onInterruptCommand = onInterruptCommand,
                    onStopWaiting = onStopWaiting,
                    onAnalyzePartial = onAnalyzePartial,
                    modifier = Modifier.weight(1f),
                )
                state.error?.let { error ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        if (error.contains("API Key")) TextButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, null); Text("设置") }
                    }
                }
                AgentInput(
                    input = input,
                    enabled = !state.generating && !state.waitingForCommand,
                    onInputChange = { input = it },
                    onSend = ::send,
                )
            }
        }
    }

    highRiskCommand?.let { suggestion ->
        AlertDialog(
            onDismissRequest = { highRiskCommand = null },
            title = { Text("确认执行高风险命令？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("该命令可能删除数据、修改磁盘或中断系统。请逐字核对：", color = MaterialTheme.colorScheme.error)
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                        Text(
                            suggestion.command,
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { highRiskCommand = null; onConfirmCommand(suggestion.id) }) { Text("仍然执行") }
            },
            dismissButton = { TextButton(onClick = { highRiskCommand = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun AgentHeader(
    sessionName: String,
    generating: Boolean,
    onCancelGeneration: () -> Unit,
    onClear: () -> Unit,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text("Terminal Agent", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(sessionName, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (generating) IconButton(onClick = onCancelGeneration) { Icon(Icons.Default.Stop, "停止生成") }
        IconButton(onClick = onClear) { Icon(Icons.Default.ClearAll, "清空对话") }
        IconButton(onClick = onCollapse) { Icon(Icons.Default.KeyboardArrowDown, "收起") }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "隐藏入口") }
    }
}

@Composable
private fun AgentConversation(
    state: AiConversationState,
    onConfirm: (CommandSuggestion) -> Unit,
    onFillTerminal: (String) -> Unit,
    onInterruptCommand: () -> Unit,
    onStopWaiting: () -> Unit,
    onAnalyzePartial: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val contentVersion = state.entries.sumOf { entry -> entry.blocks.sumOf(::blockLength) }
    LaunchedEffect(state.entries.size, contentVersion) {
        val lastIndex = state.entries.lastIndex
        if (lastIndex >= 0 && listState.firstVisibleItemIndex >= (lastIndex - 1).coerceAtLeast(0)) {
            listState.animateScrollToItem(lastIndex)
        }
    }
    if (state.entries.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "结合当前 SSH 会话提问。命令只会在你确认后执行，执行结果会自动继续分析。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.entries, key = AiConversationEntry::id) { entry ->
                AgentEntry(
                    entry = entry,
                    waitingForCommand = state.waitingForCommand,
                    onConfirm = onConfirm,
                    onFillTerminal = onFillTerminal,
                    onInterruptCommand = onInterruptCommand,
                    onStopWaiting = onStopWaiting,
                    onAnalyzePartial = onAnalyzePartial,
                )
            }
        }
    }
}

private fun blockLength(block: AiContentBlock): Int = when (block) {
    is AiContentBlock.Markdown -> block.text.length
    is AiContentBlock.Command -> block.suggestion.command.length
    is AiContentBlock.CommandResult -> block.output.length
    is AiContentBlock.Error -> block.message.length
}

@Composable
private fun AgentEntry(
    entry: AiConversationEntry,
    waitingForCommand: Boolean,
    onConfirm: (CommandSuggestion) -> Unit,
    onFillTerminal: (String) -> Unit,
    onInterruptCommand: () -> Unit,
    onStopWaiting: () -> Unit,
    onAnalyzePartial: (String) -> Unit,
) {
    val isUser = entry.role == AiMessageRole.USER
    Column(
        Modifier.fillMaxWidth().padding(start = if (isUser) 42.dp else 0.dp, end = if (isUser) 0.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            when (entry.role) {
                AiMessageRole.USER -> "你"
                AiMessageRole.ASSISTANT -> "Agent"
                AiMessageRole.TOOL -> "终端结果"
                AiMessageRole.SYSTEM -> "系统"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        entry.blocks.forEach { block ->
            when (block) {
                is AiContentBlock.Markdown -> MarkdownCard(block.text, isUser)
                is AiContentBlock.Command -> CommandCard(block.suggestion, onConfirm, onFillTerminal)
                is AiContentBlock.CommandResult -> CommandResultCard(
                    block,
                    waitingForCommand,
                    onInterruptCommand,
                    onStopWaiting,
                    onAnalyzePartial,
                )
                is AiContentBlock.Error -> Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) { Text(block.message, Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer) }
            }
        }
    }
}

@Composable
private fun MarkdownCard(markdown: String, user: Boolean) {
    val document = remember(markdown) { MarkdownContentParser.parse(markdown) }
    Surface(
        color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            document.blocks.forEach { block -> MarkdownBlockView(block) }
        }
    }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock) {
    when (block) {
        is MarkdownBlock.Paragraph -> InlineText(block.content, MaterialTheme.typography.bodyMedium)
        is MarkdownBlock.Heading -> InlineText(
            block.content,
            when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
        )
        is MarkdownBlock.ListEntry -> Row {
            Text(block.marker, Modifier.width(24.dp), fontWeight = FontWeight.Bold)
            InlineText(block.content, MaterialTheme.typography.bodyMedium, Modifier.weight(1f))
        }
        is MarkdownBlock.Quote -> Row {
            Spacer(Modifier.width(3.dp).background(MaterialTheme.colorScheme.primary).fillMaxHeight())
            InlineText(block.content, MaterialTheme.typography.bodyMedium, Modifier.padding(start = 10.dp))
        }
        is MarkdownBlock.CodeBlock -> CodeBlockCard(block)
    }
}

@Composable
private fun InlineText(
    content: List<MarkdownInline>,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = buildAnnotatedString {
        content.forEach { inline ->
            val start = length
            pushStyle(
                SpanStyle(
                    fontFamily = if (inline.code) FontFamily.Monospace else null,
                    background = if (inline.code) codeBackground else Color.Unspecified,
                    fontStyle = if (inline.emphasis) FontStyle.Italic else null,
                    fontWeight = if (inline.strong) FontWeight.Bold else null,
                    color = if (inline.link != null) linkColor else Color.Unspecified,
                    textDecoration = if (inline.link != null) TextDecoration.Underline else null,
                ),
            )
            append(inline.text)
            pop()
            inline.link?.let { addStringAnnotation("URL", it, start, length) }
        }
    }
    ClickableText(
        text = annotated,
        modifier = modifier,
        style = style.copy(color = MaterialTheme.colorScheme.onSurface),
        onClick = { offset -> annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { uriHandler.openUri(it.item) } },
    )
}

@Composable
private fun CodeBlockCard(block: MarkdownBlock.CodeBlock) {
    val clipboard = LocalClipboardManager.current
    Surface(
        modifier = Modifier.testTag("agent_code_block"),
        color = MaterialTheme.colorScheme.inverseSurface,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Code, null, Modifier.size(16.dp))
                Text(block.language ?: "code", Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { clipboard.setText(AnnotatedString(block.code)) }) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(15.dp)); Text("复制")
                }
            }
            Text(
                block.code,
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CommandCard(
    suggestion: CommandSuggestion,
    onConfirm: (CommandSuggestion) -> Unit,
    onFillTerminal: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val executable = validateCommand(suggestion.command) == null && suggestion.status == CommandExecutionStatus.PENDING
    Surface(
        modifier = Modifier.testTag("agent_command_card"),
        color = riskColor(suggestion.risk),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("命令建议", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${riskLabel(suggestion.risk)} · ${statusLabel(suggestion.status)}", style = MaterialTheme.typography.labelSmall)
            }
            Text(suggestion.summary, style = MaterialTheme.typography.bodyMedium)
            if (suggestion.expectedOutcome.isNotBlank()) {
                Text("预期：${suggestion.expectedOutcome}", style = MaterialTheme.typography.bodySmall)
            }
            Surface(color = MaterialTheme.colorScheme.inverseSurface, shape = MaterialTheme.shapes.small) {
                Text(
                    suggestion.command,
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (suggestion.risk == CommandRisk.UNKNOWN) {
                Text("本地规则无法确定风险，请在执行前完整核对。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(suggestion.command)) }) { Text("复制") }
                TextButton(onClick = { onFillTerminal(suggestion.command) }) { Icon(Icons.Default.ContentPaste, null); Text("填入终端") }
                FilledTonalButton(onClick = { onConfirm(suggestion) }, enabled = executable) {
                    Icon(Icons.Default.PlayArrow, null); Text(if (suggestion.risk == CommandRisk.HIGH) "核对并执行" else "确认执行")
                }
            }
        }
    }
}

@Composable
private fun CommandResultCard(
    result: AiContentBlock.CommandResult,
    waitingForCommand: Boolean,
    onInterruptCommand: () -> Unit,
    onStopWaiting: () -> Unit,
    onAnalyzePartial: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.testTag("agent_command_result"),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Text("终端输出", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text(
                    buildString {
                        append(statusLabel(result.status))
                        result.exitCode?.let { append(" · exit ").append(it) }
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (result.output.isNotBlank()) {
                Text(
                    result.output,
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (result.truncated) Text("输出过长，已保留首尾内容。", style = MaterialTheme.typography.labelSmall)
            result.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer) }
            if (result.status == CommandExecutionStatus.TIMED_OUT && waitingForCommand) {
                Text("等待已超时；命令仍可能在远端运行。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onInterruptCommand) { Text("发送 Ctrl-C") }
                    TextButton(onClick = onStopWaiting) { Text("停止采集") }
                    TextButton(onClick = { onAnalyzePartial(result.suggestionId) }) { Text("分析已有输出") }
                }
            }
        }
    }
}

@Composable
private fun AgentInput(input: String, enabled: Boolean, onInputChange: (String) -> Unit, onSend: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(if (enabled) "询问当前终端…" else "等待当前任务结束…") },
            enabled = enabled,
            maxLines = 4,
        )
        IconButton(onClick = onSend, enabled = enabled && input.isNotBlank()) {
            Icon(Icons.AutoMirrored.Filled.Send, "发送", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun riskColor(risk: CommandRisk): Color = when (risk) {
    CommandRisk.LOW -> MaterialTheme.colorScheme.primaryContainer
    CommandRisk.MEDIUM -> MaterialTheme.colorScheme.tertiaryContainer
    CommandRisk.HIGH -> MaterialTheme.colorScheme.errorContainer
    CommandRisk.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant
}

private fun riskLabel(risk: CommandRisk): String = when (risk) {
    CommandRisk.LOW -> "低风险"
    CommandRisk.MEDIUM -> "中风险"
    CommandRisk.HIGH -> "高风险"
    CommandRisk.UNKNOWN -> "风险未知"
}

private fun statusLabel(status: CommandExecutionStatus): String = when (status) {
    CommandExecutionStatus.PENDING -> "待确认"
    CommandExecutionStatus.RUNNING -> "运行中"
    CommandExecutionStatus.SUCCEEDED -> "已完成"
    CommandExecutionStatus.FAILED -> "退出非零"
    CommandExecutionStatus.TIMED_OUT -> "等待超时"
    CommandExecutionStatus.INTERRUPTED -> "已中断"
    CommandExecutionStatus.STOPPED -> "已停止采集"
    CommandExecutionStatus.DISCONNECTED -> "SSH 已断开"
    CommandExecutionStatus.UNSUPPORTED_SHELL -> "不支持自动执行"
    CommandExecutionStatus.EXPIRED -> "已过期"
}

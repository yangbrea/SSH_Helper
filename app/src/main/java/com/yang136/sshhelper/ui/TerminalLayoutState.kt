package com.yang136.sshhelper.ui

/**
 * 终端当前占用的上下文面板。面板互斥，避免搜索、会话、快捷命令和选择工具同时叠加。
 */
internal enum class TerminalPanel {
    NONE,
    SESSIONS,
    SEARCH,
    SNIPPETS,
    SELECTION,
}

internal data class TerminalLayoutState(
    val panel: TerminalPanel = TerminalPanel.NONE,
    val extraKeysVisible: Boolean = false,
) : java.io.Serializable

internal sealed interface TerminalLayoutAction {
    data class TogglePanel(val panel: TerminalPanel) : TerminalLayoutAction
    data class SelectionChanged(val active: Boolean) : TerminalLayoutAction
    data object ToggleExtraKeys : TerminalLayoutAction
    data object ClosePanel : TerminalLayoutAction
}

internal fun reduceTerminalLayout(
    state: TerminalLayoutState,
    action: TerminalLayoutAction,
): TerminalLayoutState = when (action) {
    is TerminalLayoutAction.TogglePanel -> state.copy(
        panel = if (state.panel == action.panel) TerminalPanel.NONE else action.panel,
    )
    is TerminalLayoutAction.SelectionChanged -> when {
        action.active -> state.copy(panel = TerminalPanel.SELECTION)
        state.panel == TerminalPanel.SELECTION -> state.copy(panel = TerminalPanel.NONE)
        else -> state
    }
    TerminalLayoutAction.ToggleExtraKeys -> state.copy(extraKeysVisible = !state.extraKeysVisible)
    TerminalLayoutAction.ClosePanel -> state.copy(panel = TerminalPanel.NONE)
}

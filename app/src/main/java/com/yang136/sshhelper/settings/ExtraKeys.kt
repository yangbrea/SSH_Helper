package com.yang136.sshhelper.settings

enum class ExtraKeyId(val label: String, val sequence: String? = null) {
    KEYBOARD("键盘"),
    CTRL("Ctrl"),
    CTRL_C("Ctrl+C", "\u0003"),
    CTRL_A("Ctrl+A", "\u0001"),
    CTRL_D("Ctrl+D", "\u0004"),
    CTRL_L("Ctrl+L", "\u000c"),
    CTRL_Z("Ctrl+Z", "\u001a"),
    ESC("Esc", "\u001b"),
    TAB("Tab", "\t"),
    UP("↑", "\u001b[A"),
    DOWN("↓", "\u001b[B"),
    LEFT("←", "\u001b[D"),
    RIGHT("→", "\u001b[C"),
    HOME("Home", "\u001b[H"),
    END("End", "\u001b[F"),
    PAGE_UP("PgUp", "\u001b[5~"),
    PAGE_DOWN("PgDn", "\u001b[6~"),
    INSERT("Ins", "\u001b[2~"),
    DELETE("Del", "\u001b[3~"),
    F1("F1", "\u001bOP"), F2("F2", "\u001bOQ"), F3("F3", "\u001bOR"), F4("F4", "\u001bOS"),
    F5("F5", "\u001b[15~"), F6("F6", "\u001b[17~"), F7("F7", "\u001b[18~"), F8("F8", "\u001b[19~"),
    F9("F9", "\u001b[20~"), F10("F10", "\u001b[21~"), F11("F11", "\u001b[23~"), F12("F12", "\u001b[24~"),
}

val DEFAULT_EXTRA_KEYS = listOf(
    ExtraKeyId.KEYBOARD,
    ExtraKeyId.CTRL,
    ExtraKeyId.CTRL_C,
    ExtraKeyId.ESC,
    ExtraKeyId.TAB,
    ExtraKeyId.UP,
    ExtraKeyId.DOWN,
    ExtraKeyId.LEFT,
    ExtraKeyId.RIGHT,
    ExtraKeyId.HOME,
    ExtraKeyId.END,
)

internal fun sanitizeExtraKeys(ids: List<ExtraKeyId>): List<ExtraKeyId> =
    ids.distinct().ifEmpty { DEFAULT_EXTRA_KEYS }

internal fun decodeExtraKeys(value: String?): List<ExtraKeyId> {
    if (value.isNullOrBlank()) return DEFAULT_EXTRA_KEYS
    return sanitizeExtraKeys(value.split(',').mapNotNull { stored ->
        ExtraKeyId.entries.firstOrNull { it.name == stored }
    })
}

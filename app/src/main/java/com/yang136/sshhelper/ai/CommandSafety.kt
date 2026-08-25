package com.yang136.sshhelper.ai

private const val MAX_COMMAND_BYTES = 16 * 1024

fun validateCommand(command: String): String? = when {
    command.isBlank() -> "命令为空"
    '\u0000' in command -> "命令包含 NUL 字符"
    command.toByteArray(Charsets.UTF_8).size > MAX_COMMAND_BYTES -> "命令超过 16KiB"
    else -> null
}

object CommandRiskClassifier {
    private val highCommands = setOf(
        "rm", "rmdir", "mkfs", "wipefs", "fdisk", "parted", "shutdown", "reboot", "poweroff", "halt",
    )
    private val mediumCommands = setOf(
        "apt", "apt-get", "yum", "dnf", "pacman", "apk", "brew", "snap", "flatpak",
        "systemctl", "service", "chmod", "chown", "chgrp", "kill", "pkill", "killall",
        "cp", "mv", "touch", "mkdir", "install", "tee", "sed",
    )
    private val lowCommands = setOf(
        "ls", "pwd", "whoami", "id", "uname", "hostname", "df", "du", "free", "ps", "top", "htop",
        "cat", "head", "tail", "less", "more", "grep", "rg", "find", "stat", "file", "which", "type",
        "env", "printenv", "date", "uptime", "ip", "ss", "netstat", "dig", "nslookup", "ping", "wc",
        "sort", "uniq", "cut", "awk", "journalctl", "dmesg", "lsof", "git",
    )

    fun classify(command: String): CommandRisk {
        if (validateCommand(command) != null) return CommandRisk.UNKNOWN
        val lexed = ShellLexer.lex(command)
        if (lexed.unclosedQuote || lexed.tokens.isEmpty()) return CommandRisk.UNKNOWN
        val lower = lexed.tokens.map(String::lowercase)
        if (containsHighRisk(lower, command)) return CommandRisk.HIGH
        if (lexed.operators.any { it == ">" || it == ">>" || it == "<>&" }) return CommandRisk.MEDIUM

        val commandHeads = mutableListOf<String>()
        var expectHead = true
        lexed.parts.forEach { part ->
            if (part.operator != null) {
                if (part.operator in setOf("|", "||", "&&", ";", "\n")) expectHead = true
            } else if (expectHead) {
                val token = part.token.orEmpty().substringAfterLast('/').lowercase()
                if (token !in setOf("sudo", "command", "env", "nohup", "time")) {
                    commandHeads += token
                    expectHead = false
                }
            }
        }
        if (commandHeads.any { it in highCommands || it.startsWith("mkfs.") }) return CommandRisk.HIGH
        if (commandHeads.any { it in mediumCommands }) return CommandRisk.MEDIUM
        if (commandHeads.isNotEmpty() && commandHeads.all { it in lowCommands }) {
            if (commandHeads.first() == "git") {
                val gitAction = lower.dropWhile { it.substringAfterLast('/') != "git" }.drop(1).firstOrNull()
                if (gitAction !in setOf("status", "log", "diff", "show", "branch", "remote", "rev-parse")) {
                    return CommandRisk.MEDIUM
                }
            }
            return CommandRisk.LOW
        }
        return CommandRisk.UNKNOWN
    }

    private fun containsHighRisk(tokens: List<String>, raw: String): Boolean {
        val text = raw.lowercase()
        if (Regex("\\bdrop\\s+(database|schema|table)\\b").containsMatchIn(text)) return true
        if (Regex("\\bdd\\b[\\s\\S]*\\bof=/dev/").containsMatchIn(text)) return true
        if (Regex("\\b(chmod|chown|chgrp)\\b[\\s\\S]*\\s-[^\\s]*r", RegexOption.IGNORE_CASE).containsMatchIn(raw)) return true
        return tokens.any { it.substringAfterLast('/') in highCommands || it.substringAfterLast('/').startsWith("mkfs.") }
    }
}

internal data class ShellPart(val token: String? = null, val operator: String? = null)
internal data class ShellLexResult(
    val parts: List<ShellPart>,
    val unclosedQuote: Boolean,
) {
    val tokens: List<String> get() = parts.mapNotNull(ShellPart::token)
    val operators: List<String> get() = parts.mapNotNull(ShellPart::operator)
}

internal object ShellLexer {
    private val operatorChars = setOf('|', '&', ';', '>', '<', '\n')

    fun lex(command: String): ShellLexResult {
        val parts = mutableListOf<ShellPart>()
        val token = StringBuilder()
        var quote: Char? = null
        var escaped = false
        fun flushToken() {
            if (token.isNotEmpty()) {
                parts += ShellPart(token = token.toString())
                token.clear()
            }
        }
        var index = 0
        while (index < command.length) {
            val char = command[index]
            if (escaped) {
                token.append(char)
                escaped = false
            } else if (char == '\\' && quote != '\'') {
                escaped = true
            } else if (quote != null) {
                if (char == quote) quote = null else token.append(char)
            } else if (char == '\'' || char == '"') {
                quote = char
            } else if (char.isWhitespace() && char != '\n') {
                flushToken()
            } else if (char in operatorChars) {
                flushToken()
                val pair = command.substring(index, minOf(index + 2, command.length))
                val operator = if (pair in setOf("||", "&&", ">>", "<<")) pair else char.toString()
                parts += ShellPart(operator = operator)
                if (operator.length == 2) index++
            } else {
                token.append(char)
            }
            index++
        }
        if (escaped) token.append('\\')
        flushToken()
        return ShellLexResult(parts, unclosedQuote = quote != null)
    }
}

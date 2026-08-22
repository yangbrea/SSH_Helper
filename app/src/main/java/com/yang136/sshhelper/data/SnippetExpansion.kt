package com.yang136.sshhelper.data

// Escape both braces explicitly. The desktop JDK accepts an unmatched closing
// brace as a literal, while Android's ICU regex engine rejects it at runtime.
private val inputVariable = Regex("""\$\{input:([^}]+)\}""")
private val unresolvedVariable = Regex("""\$\{[^}]+\}""")

data class SnippetExpansion(
    val text: String? = null,
    val missingInputs: List<String> = emptyList(),
    val error: String? = null,
)

fun requiredSnippetInputs(command: String): List<String> =
    inputVariable.findAll(command).map { it.groupValues[1].trim() }.filter(String::isNotEmpty).distinct().toList()

fun expandSnippet(
    snippet: CommandSnippet,
    profile: HostProfile,
    inputs: Map<String, String> = emptyMap(),
): SnippetExpansion {
    val required = requiredSnippetInputs(snippet.command)
    val missing = required.filterNot(inputs::containsKey)
    if (missing.isNotEmpty()) return SnippetExpansion(missingInputs = missing)
    var text = snippet.command
        .replace("\${host}", profile.hostname)
        .replace("\${user}", profile.username)
        .replace("\${port}", profile.port.toString())
        .replace("\${profile}", profile.name)
    text = inputVariable.replace(text) { result -> inputs[result.groupValues[1].trim()].orEmpty() }
    if (unresolvedVariable.containsMatchIn(text)) {
        return SnippetExpansion(error = "命令中存在无法识别的变量")
    }
    if (snippet.executeImmediately && text.contains('\n')) {
        return SnippetExpansion(error = "多行命令不能设置为立即执行")
    }
    return SnippetExpansion(text = text)
}

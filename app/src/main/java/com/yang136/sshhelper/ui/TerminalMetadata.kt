package com.yang136.sshhelper.ui

import android.net.Uri
import com.yang136.sshhelper.terminal.GhosttyRenderFrameStore
import java.net.URI

internal fun sanitizeTerminalMetadata(value: String, maxCodePoints: Int = 256): String {
    var accepted = 0
    val cleaned = buildString(value.length.coerceAtMost(maxCodePoints)) {
        val iterator = value.codePoints().iterator()
        while (iterator.hasNext() && accepted < maxCodePoints) {
            val codePoint = iterator.nextInt()
            if (codePoint >= 0x20 && codePoint != 0x7f) {
                appendCodePoint(codePoint)
                accepted++
            }
        }
    }
    return cleaned.trim()
}

internal fun displayTerminalWorkingDirectory(value: String): String {
    val cleaned = sanitizeTerminalMetadata(value)
    if (!cleaned.startsWith("file://", ignoreCase = true)) return cleaned
    return runCatching { Uri.parse(cleaned).path.orEmpty() }.getOrDefault("")
}

internal object TerminalUrlDetector {
    private val urlPattern = Regex(
        pattern = "https?://[^\\s\\p{Cntrl}<>\\\"']{1,4096}(?![^\\s\\p{Cntrl}<>\\\"'])",
        option = RegexOption.IGNORE_CASE,
    )
    private val trailingPunctuation = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')

    fun findAt(frame: GhosttyRenderFrameStore.Frame, row: Int, col: Int): String? {
        if (row !in frame.rows.indices || col !in frame.rows[row].indices) return null
        var firstRow = row
        while (firstRow > 0 && frame.rowMetadata[firstRow]?.wrapContinuation == true) firstRow--
        var lastRow = row
        while (lastRow + 1 < frame.rows.size && frame.rowMetadata[lastRow]?.wrap == true) lastRow++

        val text = StringBuilder()
        var targetOffset = -1
        for (rowIndex in firstRow..lastRow) {
            frame.rows[rowIndex].forEachIndexed { column, cell ->
                if (rowIndex == row && column == col) targetOffset = text.length
                if (cell?.wideTail == true) return@forEachIndexed
                text.append(cell?.text?.takeIf { it.isNotEmpty() } ?: " ")
            }
        }
        if (targetOffset < 0) return null
        return urlPattern.findAll(text).firstNotNullOfOrNull { match ->
            var candidate = match.value
            while (candidate.lastOrNull() in trailingPunctuation) candidate = candidate.dropLast(1)
            if (candidate.length > MAX_URL_CHARS) return@firstNotNullOfOrNull null
            val endExclusive = match.range.first + candidate.length
            if (targetOffset !in match.range.first until endExclusive) return@firstNotNullOfOrNull null
            runCatching { URI(candidate) }.getOrNull()
                ?.takeIf { it.scheme.equals("http", true) || it.scheme.equals("https", true) }
                ?.toASCIIString()
        }
    }

    private const val MAX_URL_CHARS = 4096
}

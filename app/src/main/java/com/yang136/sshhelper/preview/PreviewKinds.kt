package com.yang136.sshhelper.preview

/**
 * How a remote file should be previewed. Classification is extension based (SFTP carries
 * no MIME type); unknown or ambiguous extensions fall back to [UNSUPPORTED], letting the
 * caller use the existing content-sniffing / download-and-open fallbacks.
 */
enum class PreviewKind { TEXT, IMAGE, AUDIO, VIDEO, UNSUPPORTED }

// Only formats the streaming pipeline actually handles. WMA/AIFF/MIDI and the like are
// intentionally absent so they fall back to the "download and open elsewhere" path.
private val AUDIO_EXTENSIONS = setOf(
    "mp3", "aac", "m4a", "wav", "flac", "ogg", "opus", "amr", "weba",
)

// Reserved for a future video surface; the audio pipeline can already decode these
// containers (audio-only), so keep the classification honest about what Media3 reads.
private val VIDEO_EXTENSIONS = setOf(
    "mp4", "m4v", "webm", "3gp", "3g2", "mkv",
)

// Only raster formats Coil's built-in decoders handle. SVG/ICO need extra Coil modules,
// so they stay out and take the "download and open elsewhere" fallback instead.
private val IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif",
)

private val TEXT_EXTENSIONS = setOf(
    "txt", "log", "md", "markdown", "json", "xml", "yml", "yaml", "toml", "ini", "conf", "cfg",
    "sh", "py", "js", "ts", "kt", "kts", "java", "c", "h", "cpp", "hpp", "go", "rs", "sql",
    "csv", "properties", "env", "html", "css", "rb", "php", "lua", "bat", "ps1", "gradle",
    "dockerfile", "gitignore",
)

/** Classifies a remote file by its file name extension. */
fun previewKind(name: String): PreviewKind {
    val extension = name.substringAfterLast('.', "").lowercase()
    return when {
        extension in AUDIO_EXTENSIONS -> PreviewKind.AUDIO
        extension in VIDEO_EXTENSIONS -> PreviewKind.VIDEO
        extension in IMAGE_EXTENSIONS -> PreviewKind.IMAGE
        extension in TEXT_EXTENSIONS -> PreviewKind.TEXT
        else -> PreviewKind.UNSUPPORTED
    }
}

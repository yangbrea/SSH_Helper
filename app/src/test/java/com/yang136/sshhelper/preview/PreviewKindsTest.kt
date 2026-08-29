package com.yang136.sshhelper.preview

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewKindsTest {
    @Test
    fun classifiesAudioExtensions() {
        listOf("song.mp3", "a.m4a", "b.flac", "c.ogg", "d.opus", "e.wav", "f.aac", "g.weba", "h.amr")
            .forEach { assertEquals("$it 应为 AUDIO", PreviewKind.AUDIO, previewKind(it)) }
    }

    @Test
    fun classifiesImageExtensions() {
        listOf("a.jpg", "b.jpeg", "c.png", "d.webp", "e.gif", "f.heic", "g.avif")
            .forEach { assertEquals("$it 应为 IMAGE", PreviewKind.IMAGE, previewKind(it)) }
    }

    @Test
    fun classifiesTextExtensions() {
        listOf("a.txt", "b.log", "c.json", "d.md", "e.sh", "f.kt", "dockerfile.txt")
            .forEach { assertEquals("$it 应为 TEXT", PreviewKind.TEXT, previewKind(it)) }
    }

    @Test
    fun classifiesVideoExtensions() {
        listOf("a.mp4", "b.mkv", "c.webm", "d.m4v", "e.3gp")
            .forEach { assertEquals("$it 应为 VIDEO", PreviewKind.VIDEO, previewKind(it)) }
    }

    @Test
    fun unknownAndNoExtensionFallBackToUnsupported() {
        assertEquals(PreviewKind.UNSUPPORTED, previewKind("archive.tar.gz"))
        assertEquals(PreviewKind.UNSUPPORTED, previewKind("README"))
        assertEquals(PreviewKind.UNSUPPORTED, previewKind(""))
        assertEquals(PreviewKind.UNSUPPORTED, previewKind("a.wma")) // 不支持，走下载兜底
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertEquals(PreviewKind.AUDIO, previewKind("SONG.MP3"))
        assertEquals(PreviewKind.IMAGE, previewKind("Photo.JPG"))
    }
}

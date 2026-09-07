package com.yang136.sshhelper.sftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeSftpPayloadTest {
    @Test
    fun decodesEscapedDirectoryEntryNames() {
        assertEquals("tab\tline\npercent%", decodeNativeSftpField("tab%09line%0Apercent%25"))
        assertEquals("中文.txt", decodeNativeSftpField("中文.txt"))
    }

    @Test
    fun parsesOpaqueFileHandleAndFullSize() {
        assertEquals(
            NativeSftpOpenFile(handle = 42L, size = 9_223_372_036L),
            parseNativeSftpOpenPayload("handle=42\nsize=9223372036"),
        )
    }

    @Test
    fun rejectsMissingFileHandle() {
        assertThrows(IllegalStateException::class.java) {
            parseNativeSftpOpenPayload("size=10")
        }
    }
}

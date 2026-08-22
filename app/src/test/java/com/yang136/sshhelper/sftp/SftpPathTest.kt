package com.yang136.sshhelper.sftp

import org.junit.Assert.assertEquals
import org.junit.Test

class SftpPathTest {
    @Test fun normalizesDotsWithoutFollowingAboveRoot() {
        assertEquals("/etc/ssh", normalizeRemotePath("/var/../etc/./ssh"))
        assertEquals("/etc", normalizeRemotePath("/../../etc"))
        assertEquals(".", normalizeRemotePath("../../"))
    }

    @Test fun joinsUnicodeAndNormalizesParentSegments() {
        assertEquals("/home/用户/文档", joinRemotePath("/home/用户", "文档"))
        assertEquals("/home/tmp", joinRemotePath("/home/user", "../tmp"))
    }
}

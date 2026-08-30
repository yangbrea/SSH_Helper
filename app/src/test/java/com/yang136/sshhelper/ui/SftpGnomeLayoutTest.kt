package com.yang136.sshhelper.ui

import com.yang136.sshhelper.sftp.RemoteFileType
import com.yang136.sshhelper.sftp.SftpSearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SftpGnomeLayoutTest {

    @Test
    fun breadcrumb_shortPath_showsAllSegments() {
        val crumbs = breadcrumbSegments("/home/user/proj")
        assertEquals(listOf("home", "user", "proj"), crumbs.map { it.label })
        assertEquals(listOf("/home", "/home/user", "/home/user/proj"), crumbs.map { it.path })
        assertFalse(crumbs.any { it.ellipsis })
    }

    @Test
    fun breadcrumb_longPath_collapsesLeadingSegments() {
        val crumbs = breadcrumbSegments("/a/b/c/d/e")
        assertEquals(4, crumbs.size)
        assertTrue(crumbs.first().ellipsis)
        assertEquals("…", crumbs.first().label)
        assertEquals(listOf("c", "d", "e"), crumbs.drop(1).map { it.label })
        assertEquals(listOf("/a/b", "/a/b/c", "/a/b/c/d", "/a/b/c/d/e"), crumbs.map { it.path })
    }

    @Test
    fun breadcrumb_root_returnsSingleSegment() {
        assertEquals(listOf(BreadcrumbSegment("/", "/")), breadcrumbSegments("/"))
    }

    @Test
    fun breadcrumb_relativePath_staysRelative() {
        val crumbs = breadcrumbSegments("var/log")
        assertEquals(listOf("var", "var/log"), crumbs.map { it.path })
    }

    @Test
    fun sortSearchHits_groupsByParentThenNameIgnoringCase() {
        val hits = listOf(
            SftpSearchHit("/b/2.log", "2.log", "/b", RemoteFileType.FILE),
            SftpSearchHit("/a/B.txt", "B.txt", "/a", RemoteFileType.FILE),
            SftpSearchHit("/a/1.txt", "1.txt", "/a", RemoteFileType.FILE),
        )
        assertEquals(
            listOf("/a/1.txt", "/a/B.txt", "/b/2.log"),
            sortSearchHits(hits).map { it.path },
        )
    }

    @Test
    fun searchHit_toRemoteFile_preservesMetadata() {
        val hit = SftpSearchHit("/x/notes.txt", "notes.txt", "/x", RemoteFileType.FILE, size = 42, modifiedAt = 7, permissions = 0x1A4)
        val file = hit.toRemoteFile()
        assertEquals("/x/notes.txt", file.path)
        assertEquals("notes.txt", file.name)
        assertEquals(42L, file.size)
        assertEquals(7L, file.modifiedAt)
        assertEquals(0x1A4, file.permissions)
    }
}

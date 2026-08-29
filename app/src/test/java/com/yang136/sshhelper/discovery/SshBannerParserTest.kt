package com.yang136.sshhelper.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshBannerParserTest {
    @Test
    fun acceptsBannerAfterServerNotice() {
        val banner = SshBannerParser.parse("Authorized access only\r\nSSH-2.0-OpenSSH_9.9\r\n".toByteArray())

        assertEquals("OpenSSH_9.9", banner?.softwareVersion)
        assertTrue(banner!!.supported)
    }

    @Test
    fun acceptsCompatibilityVersionButMarksSshOneUnsupported() {
        assertTrue(SshBannerParser.parse("SSH-1.99-legacy\n".toByteArray())!!.supported)
        assertFalse(SshBannerParser.parse("SSH-1.5-legacy\n".toByteArray())!!.supported)
    }

    @Test
    fun rejectsControlCharactersAndOversizedIdentification() {
        assertNull(SshBannerParser.parse("SSH-2.0-bad\u001Bname\r\n".toByteArray()))
        assertNull(SshBannerParser.parse(("SSH-2.0-" + "x".repeat(250) + "\r\n").toByteArray()))
    }

    @Test
    fun ignoresNonSshServices() {
        assertNull(SshBannerParser.parse("HTTP/1.1 200 OK\r\n".toByteArray()))
    }
}

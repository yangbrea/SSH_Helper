package com.yang136.sshhelper.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortScannerCoreTest {
    @Test fun parsesListsRangesAndDeduplicates() {
        assertEquals(sortedSetOf(22, 80, 81, 82, 443), parsePortScanList("22,80-82,443,22").getOrThrow())
    }

    @Test fun rejectsInvalidRanges() {
        assertTrue(parsePortScanList("0").isFailure)
        assertTrue(parsePortScanList("90-80").isFailure)
        assertTrue(parsePortScanList("1-70000").isFailure)
        assertTrue(parsePortScanList("22,,80").isFailure)
    }

    @Test fun recognizesStrongProtocolEvidence() {
        val ssh = fingerprintFromBanner(2222, "notice\r\nSSH-2.0-OpenSSH_9.8\r\n")
        assertEquals("SSH", ssh.service)
        assertEquals(FingerprintConfidence.HIGH, ssh.confidence)
        val http = fingerprintFromBanner(8080, "HTTP/1.1 200 OK\r\nServer: nginx/1.27\r\n\r\n")
        assertEquals("HTTP", http.service)
        assertEquals("nginx", http.product)
        assertEquals("1.27", http.version)
    }

    @Test fun portOnlyGuessIsLowConfidence() {
        val result = fallbackFingerprint(3306)
        assertEquals("MYSQL", result.service)
        assertEquals(FingerprintConfidence.LOW, result.confidence)
        assertFalse(result.tlsUnverified)
    }

    @Test fun sanitizesBannerAndCapsLength() {
        val result = sanitizeBanner("SSH\u0001" + "x".repeat(10_000))
        assertTrue(result.contains('�'))
        assertTrue(result.length <= MAX_PORT_BANNER_BYTES)
    }
}

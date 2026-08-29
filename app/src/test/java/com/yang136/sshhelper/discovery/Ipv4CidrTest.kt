package com.yang136.sshhelper.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv4CidrTest {
    @Test
    fun parsesAndEnumeratesUsableAddresses() {
        val cidr = Ipv4Cidr.parse("192.168.7.99/30").getOrThrow()

        assertEquals("192.168.7.96/30", cidr.toString())
        assertEquals(listOf("192.168.7.97", "192.168.7.98"), cidr.usableAddresses())
    }

    @Test
    fun excludesOwnAddress() {
        val cidr = Ipv4Cidr.parse("10.0.0.0/30").getOrThrow()

        assertEquals(listOf("10.0.0.2"), cidr.usableAddresses("10.0.0.1"))
    }

    @Test
    fun broadNetworkDefaultsToLocalSlash24() {
        assertEquals("172.20.8.0/24", Ipv4Cidr.defaultFor("172.20.8.42", 16).toString())
    }

    @Test
    fun validatesAllowedAndBoundedRanges() {
        assertNull(validateScanCidr(Ipv4Cidr.parse("192.168.1.0/24").getOrThrow()))
        assertTrue(validateScanCidr(Ipv4Cidr.parse("8.8.8.0/24").getOrThrow())!!.contains("私有"))
        assertTrue(validateScanCidr(Ipv4Cidr.parse("10.0.0.0/8").getOrThrow())!!.contains("1024"))
    }

    @Test
    fun rejectsAmbiguousOrMalformedAddresses() {
        assertFalse(Ipv4Cidr.parse("192.168.001.1/24").isSuccess)
        assertFalse(Ipv4Cidr.parse("192.168.1.1/33").isSuccess)
    }

    @Test
    fun parsesAndDeduplicatesPorts() {
        assertEquals(linkedSetOf(22, 2222), parsePortList("22, 2222，22").getOrThrow())
        assertFalse(parsePortList("0").isSuccess)
        assertFalse(parsePortList((1..17).joinToString(",")).isSuccess)
    }
}

package com.yang136.sshhelper.discovery

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SsdpAndDescriptionTest {
    @Test
    fun searchRequestUsesRequiredDiscoveryHeaders() {
        val request = SsdpProtocol.searchRequest.toString(StandardCharsets.US_ASCII)
        assertTrue(request.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(request.contains("HOST: 239.255.255.250:1900\r\n"))
        assertTrue(request.contains("MAN: \"ssdp:discover\"\r\n"))
        assertTrue(request.contains("MX: 1\r\n"))
        assertTrue(request.endsWith("ST: ssdp:all\r\n\r\n"))
    }

    @Test
    fun responseAccumulatorRejectsDuplicatesOutsideCidrAndLimitOverflow() {
        val cidr = Ipv4Cidr.parse("192.168.1.0/24").getOrThrow()
        val accumulator = SsdpResponseAccumulator(cidr, limit = 2)
        val first = response("uuid:one", "upnp:rootdevice")
        val second = response("uuid:two", "urn:schemas-upnp-org:device:MediaRenderer:1")

        assertTrue(accumulator.add("192.168.1.2", first))
        assertFalse(accumulator.add("192.168.1.2", first))
        assertFalse(accumulator.add("10.0.0.2", second))
        assertTrue(accumulator.add("192.168.1.3", second))
        assertFalse(accumulator.add("192.168.1.4", response("uuid:three", "ssdp:all")))
        assertEquals(2, accumulator.snapshot().size)
    }

    @Test
    fun rejectsOversizedOrMalformedSsdpPackets() {
        assertNull(SsdpProtocol.parseResponse("192.168.1.2", ByteArray(SSDP_MAX_PACKET_BYTES + 1)))
        assertNull(SsdpProtocol.parseResponse("not-an-ip", response("uuid:one", "ssdp:all")))
        assertNull(SsdpProtocol.parseResponse("192.168.1.2", "NOTIFY * HTTP/1.1\r\n\r\n".toByteArray()))
    }

    @Test
    fun validatesLocationAndParsesBoundedDescription() {
        assertNull(DeviceDescriptionParser.validateLocation("192.168.1.2", "https://192.168.1.2/device.xml"))
        assertNull(DeviceDescriptionParser.validateLocation("192.168.1.2", "http://192.168.1.3/device.xml"))
        assertNull(DeviceDescriptionParser.validateLocation("192.168.1.2", "http://example.com/device.xml"))
        assertEquals(
            "http://192.168.1.2:8080/device.xml",
            DeviceDescriptionParser.validateLocation("192.168.1.2", "http://192.168.1.2:8080/device.xml").toString(),
        )

        val xml = """
            <root><device>
              <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
              <friendlyName>Living Room</friendlyName>
              <manufacturer>Example</manufacturer><modelName>Player</modelName><modelNumber>42</modelNumber>
            </device></root>
        """.trimIndent().toByteArray()
        val value = DeviceDescriptionParser.parse(xml)
        assertEquals("Living Room", value.friendlyName)
        assertEquals("Example", value.manufacturer)
        assertEquals("Player", value.modelName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDoctype() {
        DeviceDescriptionParser.parse("<!DOCTYPE root [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><root/>".toByteArray())
    }

    @Test
    fun strictHttpParserRejectsRedirectAndDecodesBoundedChunkedBody() {
        val redirect = "HTTP/1.1 302 Found\r\nLocation: http://192.168.1.2/other\r\nContent-Length: 0\r\n\r\n"
            .toByteArray(StandardCharsets.ISO_8859_1)
        assertTrue(runCatching { DeviceDescriptionHttp.parseResponse(redirect) }.isFailure)

        val response = (
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
                "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
            ).toByteArray(StandardCharsets.ISO_8859_1)
        assertEquals("hello world", DeviceDescriptionHttp.parseResponse(response).toString(StandardCharsets.UTF_8))

        val oversized = (
            "HTTP/1.1 200 OK\r\nContent-Length: ${DESCRIPTION_MAX_BYTES + 1}\r\n\r\n"
            ).toByteArray(StandardCharsets.ISO_8859_1)
        assertTrue(runCatching { DeviceDescriptionHttp.parseResponse(oversized) }.isFailure)
    }

    private fun response(usn: String, st: String): ByteArray = (
        "HTTP/1.1 200 OK\r\n" +
            "ST: $st\r\n" +
            "USN: $usn\r\n" +
            "LOCATION: http://192.168.1.2/device.xml\r\n\r\n"
        ).toByteArray(StandardCharsets.ISO_8859_1)
}

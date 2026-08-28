package com.yang136.sshhelper.documents

import com.yang136.sshhelper.data.AuthType
import com.yang136.sshhelper.data.Credential
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.ssh.RouteCredentials
import com.yang136.sshhelper.ssh.clearCredential
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentsContractTest {
    @Test
    fun documentIdsAreStableOpaqueAndRoundTripUnicodePaths() {
        assertEquals("v1|42|~", DocumentIdCodec.root(42))
        val id = DocumentId(42, "/home/user/项目/空 格.txt")
        val encoded = DocumentIdCodec.encode(id)
        assertFalse(encoded.contains("/home/user"))
        assertEquals(id, DocumentIdCodec.decode(encoded))
    }

    @Test
    fun documentIdsRejectTraversalMalformedAndNonPositiveHosts() {
        listOf(
            "v1|1|Li4vc2VjcmV0",
            "v1|0|~",
            "v2|1|~",
            "v1|1|%%not-base64%%",
        ).forEach { value -> assertTrue(runCatching { DocumentIdCodec.decode(value) }.isFailure) }
        assertTrue(runCatching { DocumentIdCodec.root(0) }.isFailure)
    }

    @Test
    fun pathContainmentDoesNotAcceptPrefixCollisions() {
        assertTrue(isWithin("/home/user", "/home/user"))
        assertTrue(isWithin("/home/user", "/home/user/project/file"))
        assertFalse(isWithin("/home/user", "/home/user2/file"))
        assertFalse(isWithin("/home/user", "/home"))
    }

    @Test
    fun credentialBundleRoundTripsTargetJumpProxyAndVersion() {
        val source = RouteCredentials(
            target = Credential.Password("target-secret".toCharArray()),
            jump = Credential.PrivateKey(byteArrayOf(1, 2, 3, 4), "phrase".toCharArray(), "id_ed25519"),
            targetProxyPassword = "target-proxy",
            jumpProxyPassword = "jump-proxy",
        )
        val bytes = DocumentCredentialCodec.encode(source)
        val decoded = DocumentCredentialCodec.decode(bytes)
        assertArrayEquals("target-secret".toCharArray(), (decoded.target as Credential.Password).value)
        val jump = decoded.jump as Credential.PrivateKey
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), jump.bytes)
        assertArrayEquals("phrase".toCharArray(), jump.passphrase)
        assertEquals("id_ed25519", jump.fileName)
        assertEquals("target-proxy", decoded.targetProxyPassword)
        assertEquals("jump-proxy", decoded.jumpProxyPassword)
        clearCredential(source.target)
        clearCredential(source.jump)
        clearCredential(decoded.target)
        clearCredential(decoded.jump)
        bytes.fill(0)
    }

    @Test
    fun routeSignatureChangesWhenConnectionParametersChange() {
        val host = HostProfile(7, "server", "example.test", 22, "user", AuthType.PASSWORD, true)
        assertEquals(routeSignature(host, null), routeSignature(host.copy(name = "renamed"), null))
        assertNotEquals(routeSignature(host, null), routeSignature(host.copy(port = 2222), null))
        assertNotEquals(routeSignature(host, null), routeSignature(host.copy(proxyHost = "proxy.test"), null))
    }
}

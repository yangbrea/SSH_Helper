package com.yang136.sshhelper.security

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.security.SecureRandom

data class GeneratedKeyPair(
    /** OpenSSH 格式私钥 PEM(未加密;存储层的保险库负责加密保护)。 */
    val privateKey: ByteArray,
    /** OpenSSH 公钥单行(含注释)。 */
    val publicKey: String,
    /** SHA-256 指纹,如 `SHA256:xxxx`。 */
    val fingerprint: String,
)

/**
 * Generates an ed25519 SSH key pair using BouncyCastle's lightweight API (provider-free, stable
 * across Android API levels). The private key is emitted as an OpenSSH `openssh-key-v1` PEM, the
 * format JSch loads natively. Storage-layer encryption (the credential vault) protects the key at
 * rest, so no second passphrase is baked into the file.
 */
object KeyGenerator {

    fun generateEd25519(comment: String = "ssh-helper"): GeneratedKeyPair {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val keyPair = generator.generateKeyPair()
        val publicBytes = (keyPair.public as Ed25519PublicKeyParameters).encoded
        val privateSeed = (keyPair.private as Ed25519PrivateKeyParameters).encoded

        val publicBlob = encodeString("ssh-ed25519".encodeToByteArray()) + encodeString(publicBytes)
        val publicKeyLine = "ssh-ed25519 ${Base64.getEncoder().encodeToString(publicBlob)} $comment"
        val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(publicBlob))

        val opensshPem = encodeOpenSshPrivateKey(publicBytes, privateSeed, comment)
        return GeneratedKeyPair(
            privateKey = opensshPem.toByteArray(Charsets.UTF_8),
            publicKey = publicKeyLine,
            fingerprint = fingerprint,
        )
    }

    /** `openssh-key-v1` unencrypted container, PEM-wrapped. */
    private fun encodeOpenSshPrivateKey(publicBytes: ByteArray, privateSeed: ByteArray, comment: String): String {
        val check = SecureRandom().nextInt()
        val body = ByteArrayOutputStream().apply {
            writeInt(check)
            writeInt(check)
            write(encodeString("ssh-ed25519".encodeToByteArray()))
            write(encodeString(publicBytes))
            write(encodeString(privateSeed + publicBytes))
            write(encodeString(comment.encodeToByteArray()))
        }
        // Pad to a multiple of 8 with 1,2,3…
        val padding = (8 - (body.size() % 8)) % 8
        repeat(padding) { index -> body.write(index + 1) }

        val container = ByteArrayOutputStream().apply {
            write("openssh-key-v1\u0000".encodeToByteArray())
            write(encodeString("none".encodeToByteArray()))
            write(encodeString("none".encodeToByteArray()))
            write(encodeString(ByteArray(0)))
            writeInt(1)
            write(encodeString(encodeString("ssh-ed25519".encodeToByteArray()) + encodeString(publicBytes)))
            write(encodeString(body.toByteArray()))
        }
        val base64 = Base64.getEncoder().encodeToString(container.toByteArray())
        return buildString {
            append("-----BEGIN OPENSSH PRIVATE KEY-----\n")
            base64.chunked(70).forEach { append(it).append('\n') }
            append("-----END OPENSSH PRIVATE KEY-----\n")
        }
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun encodeString(bytes: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        writeInt(bytes.size)
        write(bytes)
    }.toByteArray()
}

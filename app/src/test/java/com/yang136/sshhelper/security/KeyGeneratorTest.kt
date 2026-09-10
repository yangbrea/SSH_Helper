package com.yang136.sshhelper.security

import java.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyGeneratorTest {
    @Test
    fun generatesEd25519PublicKeyWithFingerprint() {
        val pair = KeyGenerator.generateEd25519("test@host")
        assertTrue("公钥应以 ssh-ed25519 开头，实际：${pair.publicKey.take(40)}", pair.publicKey.startsWith("ssh-ed25519 "))
        assertTrue("公钥应含注释", pair.publicKey.endsWith(" test@host"))
        assertTrue("指纹应为 SHA256 前缀，实际：${pair.fingerprint}", pair.fingerprint.startsWith("SHA256:"))
        assertTrue(pair.privateKey.isNotEmpty())
        assertTrue(pair.privateKey.toString(Charsets.UTF_8).contains("BEGIN OPENSSH PRIVATE KEY"))
    }

    /**
     * 独立校验**手写的 OpenSSH 编码器**。
     *
     * KeyGenerator 的密钥材料来自 BouncyCastle，但 `openssh-key-v1` 信封是它按字节自己拼的
     * ——真正需要第三方把关的是那个编码器，而不是密钥本身。这里用 BouncyCastle 自带的
     * OpenSSH 解析器把私钥读回来：与手写编码器是两条独立的代码路径。原先由 JSch 承担的
     * 正是这件事，移除 JSch 后由已是应用依赖的 bcprov 接手。
     */
    @Test
    fun opensshPrivateKeyParsesWithIndependentParser() {
        val pair = KeyGenerator.generateEd25519("test@host")
        val parsed = parsePrivateKey(pair.privateKey)
        assertTrue(
            "生成的私钥应能被独立解析器读回 Ed25519 私钥，实际：${parsed::class.java.simpleName}",
            parsed is Ed25519PrivateKeyParameters,
        )
    }

    /**
     * 私钥里蕴含的公钥必须与公钥行里通告的一致。手写编码器如果把两者的字节写反或写错位，
     * 公钥看着正常、私钥却对不上，认证会在服务端才失败——这条能在这里拦住。
     */
    @Test
    fun embeddedPublicKeyMatchesAdvertisedPublicKey() {
        val pair = KeyGenerator.generateEd25519("test@host")
        val derived = (parsePrivateKey(pair.privateKey) as Ed25519PrivateKeyParameters).generatePublicKey().encoded
        // 公钥行是 `ssh-ed25519 <base64(string("ssh-ed25519") + key)> <comment>`。
        val blob = Base64.getDecoder().decode(pair.publicKey.split(' ')[1])
        val advertised = blob.copyOfRange(blob.size - derived.size, blob.size)
        assertArrayEquals("私钥蕴含的公钥应与公钥行一致", derived, advertised)
    }

    @Test
    fun generatedKeyPairCanSign() {
        val pair = KeyGenerator.generateEd25519("test@host")
        val challenge = "challenge-bytes".toByteArray()
        val signer = Ed25519Signer().apply {
            init(true, parsePrivateKey(pair.privateKey))
            update(challenge, 0, challenge.size)
        }
        assertTrue("签名应非空", signer.generateSignature().isNotEmpty())
    }

    /** 剥掉 PEM 外壳后交给第三方解析器——与 KeyGenerator 自己拼信封的路径无关。 */
    private fun parsePrivateKey(pem: ByteArray) = OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(
        Base64.getDecoder().decode(
            String(pem, Charsets.UTF_8)
                .lineSequence()
                .filterNot { it.startsWith("-----") }
                .joinToString("")
                .trim(),
        ),
    )
}

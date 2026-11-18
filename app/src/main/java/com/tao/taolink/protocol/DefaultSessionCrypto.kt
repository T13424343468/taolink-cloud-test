package com.tao.tao.link.protocol

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 会话加密实现（协议文档 §2-4 契约：TLS 1.3 + 配对码派生密钥双层）。
 * 本层承担第二层：配对码 PBKFD2 派生密钥，AES-GCM 对帧载荷透明加解密；
 * 密钥只在内存（byte[] 清零后丢引用），不落盘不出日志（SessionCrypto 契约口径）。
 * 派生盐为协议常量（写死对象是数学/协议事实，非外部状态，编码规范一.9 允许）。
 */
class DefaultSessionCrypto : SessionCrypto {
    private var key: SecretKey? = null
    private val random = SecureRandom()

    override fun deriveKey(pairCode: CharArray) {
        require(pairCode.size == 6) { "配对码固定 6 位" }
        val spec = PBEKeySpec(pairCode, PBKDF2_SALT, PBKDF2_ROUNDS, KEY_BITS)
        val factory = SecretKeyFactoryHolder.get
        try {
            val derived = factory.generateSecret(spec).encoded
            key = SecretKeySpec(derived, "AES")
        } finally {
            spec.clearPassword()
            pairCode.fill('\u0000')
        }
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val k = key ?: throw IllegalStateException("会话密钥未派生，先 deriveKey")
        val iv = ByteArray(IV_LEN).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(GCM_TAG_BITS, iv))
        val body = cipher.doFinal(plain)
        // IV 前置随帧走，对端无需另发密钥
        return iv + body
    }

    override fun decrypt(cipherBytes: ByteArray): ByteArray {
        val k = key ?: throw IllegalStateException("会话密钥未派生，先 deriveKey")
        if (cipherBytes.size <= IV_LEN) throw IllegalArgumentException("载荷不足，无 IV")
        val iv = cipherBytes.copyOfRange(0, IV_LEN)
        val body = cipherBytes.copyOfRange(IV_LEN, cipherBytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(body)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val IV_LEN = 12
        private const val GCM_TAG_BITS = 128
        private const val PBKDF2_ROUNDS = 120_000
        private val PBKDF2_SALT = byteArrayOf(
            0x54, 0x4C, 0x4B, 0x53, 0x41, 0x4C, 0x54, 0x31, 0x00, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        )
    }
}

private object SecretKeyFactoryHolder {
    val get by lazy { javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256") }
}

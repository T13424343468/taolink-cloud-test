package com.tao.tao.link

import com.tao.tao.link.protocol.DefaultSessionCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DefaultSessionCryptoTest {

    @Test
    fun deriveKeyThenEncryptDecrypt_roundtripIntact() {
        val crypto = DefaultSessionCrypto()
        crypto.deriveKey("123456".toCharArray())
        val plain = "taolink-session-payload".toByteArray()
        val cipher = crypto.encrypt(plain)
        assertArrayEquals(plain, crypto.decrypt(cipher))
    }

    @Test
    fun encrypt_cipherDiffersFromPlain_andIvPrepended() {
        val crypto = DefaultSessionCrypto()
        crypto.deriveKey("000000".toCharArray())
        val plain = ByteArray(16)
        val cipher = crypto.encrypt(plain)
        assertEquals(16 + 12 + 16, cipher.size) // IV 12B + GCM tag 16B
        assertNotEquals(cipher.contentHashCode(), plain.contentHashCode())
    }

    @Test
    fun decrypt_beforeDeriveKey_throws() {
        assertThrows(IllegalStateException::class.java) {
            DefaultSessionCrypto().decrypt(ByteArray(16))
        }
    }

    @Test
    fun decrypt_withDifferentCodeKey_fails() {
        val sender = DefaultSessionCrypto()
        sender.deriveKey("111111".toCharArray())
        val receiver = DefaultSessionCrypto()
        receiver.deriveKey("222222".toCharArray())
        val cipher = sender.encrypt("secret".toByteArray())
        assertThrows(Exception::class.java) { receiver.decrypt(cipher) }
    }

    @Test
    fun deriveKey_shorterThanSixDigits_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DefaultSessionCrypto().deriveKey("12345".toCharArray())
        }
    }
}

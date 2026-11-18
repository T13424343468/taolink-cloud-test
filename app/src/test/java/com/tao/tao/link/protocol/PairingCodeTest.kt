package com.tao.tao.link.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 配对码生成与校验考卷（协议文档 §2-2 契约）。 */
class PairingCodeTest {

    @Test
    fun `generate always six digits`() {
        repeat(200) {
            val code = PairingCode.generate()
            assertEquals(PairingCode.LENGTH, code.length)
            assertTrue(code.all { it.isDigit() })
        }
    }

    @Test
    fun `verify accepts exact match`() {
        val code = "012345"
        assertTrue(PairingCode.verify(code, "012345"))
    }

    @Test
    fun `verify keeps leading zeros`() {
        assertTrue(PairingCode.verify("000000", "000000"))
        assertFalse(PairingCode.verify("000001", "000000"))
    }

    @Test
    fun `verify rejects wrong length non digits and mismatch`() {
        val code = "123456"
        assertFalse(PairingCode.verify(code, "12345"))
        assertFalse(PairingCode.verify(code, "1234567"))
        assertFalse(PairingCode.verify(code, "12345a"))
        assertFalse(PairingCode.verify(code, "123457"))
    }
}

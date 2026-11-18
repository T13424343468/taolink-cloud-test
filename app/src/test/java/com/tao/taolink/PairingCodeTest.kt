package com.tao.tao.link

import com.tao.tao.link.protocol.PairingCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeTest {

    @Test
    fun generate_sixDigits_allZeroPaddedLegitimate() {
        repeat(20) {
            val code = PairingCode.generate()
            assertEquals(6, code.length)
            assertTrue(code.all { it.isDigit() })
        }
    }

    @Test
    fun verify_correctCode_passes() {
        assertTrue(PairingCode.verify("012345", "012345"))
    }

    @Test
    fun verify_wrongDigit_fails() {
        assertFalse(PairingCode.verify("012345", "012346"))
    }

    @Test
    fun verify_wrongLengthOrNonDigit_fails() {
        assertFalse(PairingCode.verify("012345", "12345"))
        assertFalse(PairingCode.verify("012345", "0123456"))
        assertFalse(PairingCode.verify("012345", "01234a"))
    }
}

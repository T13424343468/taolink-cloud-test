package com.tao.tao.link

import com.tao.tao.link.protocol.DeviceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceIdentityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun ownFingerprint_persistsUniqueId_acrossCalls() {
        val first = DeviceIdentity.ownFingerprint(tmp.root, "手机A")
        val second = DeviceIdentity.ownFingerprint(tmp.root, "手机A")
        assertEquals(first.hex, second.hex)
    }

    @Test
    fun ownFingerprint_blankDeviceName_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DeviceIdentity.ownFingerprint(tmp.root, " ")
        }
    }

    @Test
    fun ownFingerprint_hexIsSha256Length() {
        val fp = DeviceIdentity.ownFingerprint(tmp.root, "手机A")
        assertEquals(64, fp.hex.length)
        assertTrue(fp.hex.all { it.isDigit() || it in 'a'..'f' })
    }
}

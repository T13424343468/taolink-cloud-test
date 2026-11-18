package com.tao.tao.link

import com.tao.tao.link.protocol.DeviceFingerprint
import com.tao.tao.link.protocol.PairingResponder
import com.tao.tao.link.protocol.PairingRecordStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PairingResponderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newResponder(): PairingResponder {
        val store = PairingRecordStore(tmp.root)
        return PairingResponder(DeviceFingerprint.of("被控机", "uid-1"), store)
    }

    @Test
    fun displayCode_alwaysSixDigits() {
        assertEquals(6, newResponder().displayCode().length)
    }

    @Test
    fun onSubmit_correctCode_returnsOwnFingerprintAndPersistsPeer() {
        val own = DeviceFingerprint.of("被控机", "uid-1")
        val responder = PairingResponder(own, PairingRecordStore(tmp.root))
        val code = responder.displayCode()
        val peer = DeviceFingerprint.of("主控机", "uid-2")
        val confirm = responder.onSubmit(code, peer, 1000L)
        assertNotNull(confirm)
        // PAIR_CONFIRM 载荷=本端指纹 hex（同料同 hex，见 fingerprint 用例）
        assertEquals(own.hex, confirm)
        assertTrue(responder.isKnownPeer(peer.hex))
    }

    @Test
    fun onSubmit_wrongCode_rejectedAndNoRecord() {
        val responder = newResponder()
        val correct = responder.displayCode()
        val wrong = if (correct[0] == '9') "0" + correct.substring(1) else "9" + correct.substring(1)
        assertNull(responder.onSubmit(wrong, DeviceFingerprint.of("主控机", "uid-2"), 1000L))
        assertEquals(1, PairingRecordStore(tmp.root).load().size) // 只有本端自录一条
    }

    @Test
    fun onSubmit_resubmission_replacesNotDuplicates() {
        val responder = newResponder()
        val code = responder.displayCode()
        responder.onSubmit(code, DeviceFingerprint.of("主控机", "uid-2"), 1000L)
        responder.onSubmit(code, DeviceFingerprint.of("主控机", "uid-2"), 2000L)
        assertEquals(2, PairingRecordStore(tmp.root).load().size)
    }

    @Test
    fun fingerprint_sameMaterialSameHex_differentMaterialDifferentHex() {
        val a = DeviceFingerprint.of("手机", "uid")
        val b = DeviceFingerprint.of("手机", "uid")
        val c = DeviceFingerprint.of("手机", "other")
        assertEquals(a.hex, b.hex)
        assertNotEquals(a.hex, c.hex)
    }

    @Test
    fun displayCode_expiresAndRefreshes() {
        val responder = newResponder()
        val first = responder.displayCode(0L)
        // 有效期内取码：同一码不刷新
        assertEquals(first, responder.displayCode(PairingResponder.CODE_TTL_MILLIS - 1))
        // 过期后再取：自动重新签发，旧码作废
        val second = responder.displayCode(PairingResponder.CODE_TTL_MILLIS.toLong())
        assertNotEquals(first, second)
    }

    @Test
    fun onSubmit_expiredCode_rejectedAndNoPeerRecord() {
        val responder = newResponder()
        val code = responder.displayCode(0L)
        // 过期后提交旧码：拒绝（猜码/旧码不放行），账上只有本端自录一条
        assertNull(responder.onSubmit(code, DeviceFingerprint.of("主控机", "uid-2"), PairingResponder.CODE_TTL_MILLIS + 1_000L))
        assertEquals(1, PairingRecordStore(tmp.root).load().size)
    }

    @Test
    fun regenerateCode_oldCodeInvalidatedImmediately_withinTtl() {
        val responder = newResponder()
        val old = responder.displayCode(0L)
        val fresh = responder.regenerateCode(1000L) // 有效期内手动重签
        assertNotEquals(old, fresh)
        // 旧码即时作废：含有效期内（不等到 TTL 过期）
        assertNull(responder.onSubmit(old, DeviceFingerprint.of("主控机", "uid-2"), 1000L))
        // 新码放行正常
        val confirm = responder.onSubmit(fresh, DeviceFingerprint.of("主控机", "uid-2"), 1000L)
        assertNotNull(confirm)
    }

    @Test
    fun regenerateCode_resetsTtlClock() {
        val responder = newResponder()
        responder.displayCode(0L)
        val fresh = responder.regenerateCode(PairingResponder.CODE_TTL_MILLIS - 1000L)
        // 重签后按新签发时刻起算 TTL：原时钟下本已临期，重签后仍有效
        assertEquals(fresh, responder.displayCode(PairingResponder.CODE_TTL_MILLIS - 1))
        // 再过满 TTL 才过期
        assertNotEquals(fresh, responder.displayCode(2 * PairingResponder.CODE_TTL_MILLIS))
    }
}

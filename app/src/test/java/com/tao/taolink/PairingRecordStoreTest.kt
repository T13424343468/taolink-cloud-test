package com.tao.tao.link

import com.tao.tao.link.protocol.PairingRecord
import com.tao.tao.link.protocol.PairingRecordStore
import com.tao.tao.link.protocol.PathType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PairingRecordStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dir get() = tmp.root

    @Test
    fun load_missingFile_emptyList() {
        assertTrue(PairingRecordStore(tmp.root).load().isEmpty())
    }

    @Test
    fun upsert_thenLoad_roundtripIntact() {
        val store = PairingRecordStore(tmp.root)
        store.upsert(PairingRecord("abc", "主控机", 123L))
        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals("abc", loaded[0].fingerprintHex)
        assertEquals("主控机", loaded[0].deviceName)
        assertEquals(123L, loaded[0].pairedAtMillis)
    }

    @Test
    fun upsert_sameFingerprint_replacesSingle() {
        val store = PairingRecordStore(tmp.root)
        store.upsert(PairingRecord("abc", "旧名", 1L))
        store.upsert(PairingRecord("abc", "新名", 2L))
        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals("新名", loaded[0].deviceName)
        assertEquals(2L, loaded[0].pairedAtMillis)
    }

    @Test
    fun contains_existingAndMissing() {
        val store = PairingRecordStore(tmp.root)
        store.upsert(PairingRecord("abc", "名", 1L))
        assertTrue(store.contains("abc"))
        assertFalse(store.contains("def"))
    }

    @Test
    fun upsert_noTmpFileLeftBehind() {
        val store = PairingRecordStore(tmp.root)
        store.upsert(PairingRecord("abc", "名", 1L))
        assertFalse(java.io.File(tmp.root, "pairing_records.txt.tmp").exists())
    }

    // ---- Y4 拍板（2026-11-18 12:27）：配对记录增「最后连接时间+路径类型」----

    @Test
    fun markConnected_updatesLastConnectedAndPath_singleRecord() {
        val store = PairingRecordStore(dir)
        store.upsert(PairingRecord("abc", "主控机", 1000L))
        assertTrue(store.markConnected("abc", PathType.WAN_DIRECT, 9000L))
        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals(9000L, loaded[0].lastConnectedMillis)
        assertEquals(PathType.WAN_DIRECT, loaded[0].pathType)
        assertEquals(1000L, loaded[0].pairedAtMillis) // 配对时间不动
    }

    @Test
    fun markConnected_unknownFingerprint_returnsFalseAndNoRecord() {
        val store = PairingRecordStore(dir)
        assertFalse(store.markConnected("ghost", PathType.LAN, 1000L))
        assertTrue(store.load().isEmpty()) // 不隐式建账
    }

    @Test
    fun markConnected_pathRoundtrip_allThreeTypes() {
        for (path in PathType.values()) {
            val store = PairingRecordStore(dir)
            store.upsert(PairingRecord("abc", "名", 1L))
            assertTrue(store.markConnected("abc", path, 2000L))
            assertEquals(path, store.load()[0].pathType)
        }
    }

    @Test
    fun load_legacyThreeColumnRecord_fallsBackPairedTimeAndNullPath() {
        java.io.File(dir, PairingRecordStore.FILE_NAME).writeText("abc\t旧机\t1234\n")
        val loaded = PairingRecordStore(dir).load()
        assertEquals(1, loaded.size)
        assertEquals(1234L, loaded[0].pairedAtMillis)
        assertEquals(1234L, loaded[0].lastConnectedMillis) // 旧账回退配对时间
        assertNull(loaded[0].pathType)
    }

    @Test
    fun load_malformedLines_skipped() {
        val file = java.io.File(dir, PairingRecordStore.FILE_NAME)
        file.writeText("abc\t名\t1\t2\tlan\ndef\t名\t3\nbad-line\nghi\t名\t5\t6\t未知tag\n")
        val loaded = PairingRecordStore(dir).load()
        // 恶意行 bad-line 跳过；旧 3 列账（def）与新 5 列账（abc/ghi）都是有效账，须保留
        assertEquals(3, loaded.size)
        assertEquals(PathType.LAN, loaded[0].pathType)
        assertNull(loaded[1].pathType) // 旧 3 列账按未记录处理
        assertEquals(3L, loaded[1].lastConnectedMillis) // 旧账最后连接时间回退配对时间
        assertNull(loaded[2].pathType) // 未知 tag 按未记录处理，记录不丢
    }

    @Test
    fun persistedLine_isFiveColumnsWithNoneTag() {
        val store = PairingRecordStore(dir)
        store.upsert(PairingRecord("abc", "名", 1L))
        val line = java.io.File(dir, PairingRecordStore.FILE_NAME).readText().trim()
        assertEquals(5, line.split('\t').size)
        assertTrue(line.endsWith(PairingRecordStore.TAG_NONE)) // 无路径信息落 none 占位
    }
}

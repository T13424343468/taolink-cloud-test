package com.tao.tao.link

import com.tao.tao.link.file.FileReceiver
import com.tao.tao.link.file.FileTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileReceiverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun makeMeta(size: Long, sha: String) = FileTransfer.Meta("sample.bin", size, sha)

    private fun digestOf(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun totalChunks_zeroSize_isOneChunk() {
        assertEquals(1, FileReceiver.totalChunksOf(0L, 256 * 1024))
    }

    @Test
    fun totalChunks_boundaries_exclusive() {
        val chunk = 4
        assertEquals(1, FileReceiver.totalChunksOf(1L, chunk))
        assertEquals(1, FileReceiver.totalChunksOf(4L, chunk))
        assertEquals(2, FileReceiver.totalChunksOf(5L, chunk))
    }

    @Test
    fun writeChunk_allChunks_thenVerify_passes() {
        val content = ByteArray(10) { (it + 1).toByte() }
        val meta = makeMeta(content.size.toLong(), digestOf(content))
        val receiver = FileReceiver(
            java.io.File(tmp.root, "out.bin"),
            java.io.File(tmp.root, "out.bin.rcv"),
            meta,
            chunkSizeBytes = 4,
        )
        val chunks = listOf(content.copyOfRange(0, 4), content.copyOfRange(4, 8), content.copyOfRange(8, 10))
        chunks.forEachIndexed { i, c -> receiver.writeChunk(i, c) }
        assertTrue(receiver.isComplete())
        assertTrue(receiver.verifyAndFinish())
    }

    @Test
    fun writeChunk_duplicateChunk_idempotent() {
        val content = byteArrayOf(1, 2, 3, 4)
        val meta = makeMeta(content.size.toLong(), digestOf(content))
        val receiver = FileReceiver(
            java.io.File(tmp.root, "out.bin"),
            java.io.File(tmp.root, "out.bin.rcv"),
            meta,
            chunkSizeBytes = 4,
        )
        receiver.writeChunk(0, content)
        receiver.writeChunk(0, content)
        assertEquals(setOf(0), receiver.receivedChunks())
    }

    @Test
    fun writeChunk_outOfRange_rejected() {
        val receiver = FileReceiver(
            java.io.File(tmp.root, "out.bin"),
            java.io.File(tmp.root, "out.bin.rcv"),
            makeMeta(4L, "x"),
            chunkSizeBytes = 4,
        )
        assertThrows(IndexOutOfBoundsException::class.java) { receiver.writeChunk(5, byteArrayOf(1)) }
    }

    @Test
    fun resume_fromSidecar_missingOnlyLaterChunks() {
        val content = ByteArray(10) { it.toByte() }
        val meta = makeMeta(content.size.toLong(), digestOf(content))
        val target = java.io.File(tmp.root, "out.bin")
        val sidecar = java.io.File(tmp.root, "out.bin.rcv")
        val first = FileReceiver(target, sidecar, meta, chunkSizeBytes = 4)
        first.writeChunk(0, content.copyOfRange(0, 4))
        first.writeChunk(1, content.copyOfRange(4, 8))
        first.close()

        val resumed = FileReceiver(target, sidecar, meta, chunkSizeBytes = 4)
        assertEquals(setOf(0, 1), resumed.receivedChunks())
        assertEquals(setOf(2), resumed.missingChunks())
        resumed.writeChunk(2, content.copyOfRange(8, 10))
        assertTrue(resumed.verifyAndFinish())
    }

    @Test
    fun verify_hashMismatch_wholeFileRejected() {
        val meta = makeMeta(4L, "deadbeef")
        val receiver = FileReceiver(
            java.io.File(tmp.root, "out.bin"),
            java.io.File(tmp.root, "out.bin.rcv"),
            meta,
            chunkSizeBytes = 4,
        )
        receiver.writeChunk(0, byteArrayOf(1, 2, 3, 4))
        assertThrows(IllegalStateException::class.java) { receiver.verifyAndFinish() }
    }

    @Test
    fun verify_beforeComplete_rejected() {
        val content = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val receiver = FileReceiver(
            java.io.File(tmp.root, "out.bin"),
            java.io.File(tmp.root, "out.bin.rcv"),
            makeMeta(content.size.toLong(), digestOf(content)),
            chunkSizeBytes = 4,
        )
        receiver.writeChunk(0, content.copyOfRange(0, 4))
        assertFalse(receiver.isComplete())
        assertThrows(IllegalStateException::class.java) { receiver.verifyAndFinish() }
    }

    @Test
    fun cancel_deletesTargetAndSidecar() {
        val target = java.io.File(tmp.root, "out.bin")
        val sidecar = java.io.File(tmp.root, "out.bin.rcv")
        val receiver = FileReceiver(target, sidecar, makeMeta(4L, "x"), chunkSizeBytes = 4)
        receiver.writeChunk(0, byteArrayOf(1, 2, 3, 4))
        receiver.cancel()
        assertFalse(target.exists())
        assertFalse(sidecar.exists())
    }
}

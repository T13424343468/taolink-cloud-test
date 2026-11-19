package com.tao.tao.link.net

import com.tao.tao.link.file.ControlledFileHost
import com.tao.tao.link.file.FileReceiver
import com.tao.tao.link.protocol.DefaultSessionCrypto
import com.tao.tao.link.protocol.DeviceFingerprint
import com.tao.tao.link.protocol.DeviceIdentity
import com.tao.tao.link.protocol.Frame
import com.tao.tao.link.protocol.FrameType
import com.tao.tao.link.protocol.JsonText
import com.tao.tao.link.protocol.PairingRecordStore
import com.tao.tao.link.protocol.PairingResponder
import com.tao.tao.link.protocol.Route
import com.tao.tao.link.status.StatusChannel
import com.tao.tao.link.transport.SessionServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 文件互传两端真连集成测试（真 socket，非 mock）：
 * 元数据建档 → 逐块收账 → 整册校验 → 回执收班，全链路真实跑一遍。
 * 此前文件段只测各自半边，接线后两端格式/回执契约在写码时即拦。
 */
class FileTransferEndToEndIntegrationTest {

    private val tmpDir: File = File.createTempFile("taolink-file-it", "").let { it.delete(); it }

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    private fun helloBytes(own: DeviceFingerprint): ByteArray = JsonText.obj(
        JsonText.field("v", 1),
        JsonText.field("name", own.deviceName),
        JsonText.field("fp", own.hex),
    ).toByteArray(Charsets.UTF_8)

    @Test
    fun `文件端到端传输收妥且回执确认`() {
        val port = freePort()
        val ctrlDir = tmpDir.resolve("ctrl")
        val responder = PairingResponder(
            DeviceIdentity.ownFingerprint(ctrlDir, "被控端"),
            PairingRecordStore(ctrlDir),
        )
        val displayedCode = responder.displayCode()
        val own = DeviceIdentity.ownFingerprint(ctrlDir, "被控端")
        val inboxDir = tmpDir.resolve("inbox").apply { mkdirs() }
        val fileHost = ControlledFileHost(inboxDir)
        val server = SessionServer(port) { connection ->
            connection.startReading { frame ->
                when (frame.type) {
                    FrameType.HELLO -> connection.send(
                        Frame(Route.CONTROL, FrameType.HELLO, frame.seq, helloBytes(own)),
                    )
                    FrameType.PAIR_REQUEST -> {
                        val parts = String(frame.payload, Charsets.UTF_8).split("\t")
                        val confirm = if (parts.size != 3) null else responder.onSubmit(
                            parts[0], DeviceFingerprint(parts[2], parts[1]), System.currentTimeMillis(),
                        )
                        connection.send(
                            Frame(Route.CONTROL, FrameType.PAIR_CONFIRM, frame.seq, confirm?.toByteArray(Charsets.UTF_8) ?: byteArrayOf(0)),
                        )
                    }
                    // 与 ControlledSessionService 相同的文件帧契约
                    FrameType.FILE_META -> if (frame.route == Route.FILE) {
                        fileHost.onFileMeta(frame.payload)?.let {
                            connection.send(Frame(Route.FILE, FrameType.ACK, frame.seq, it))
                        }
                    }
                    FrameType.CHUNK -> if (frame.route == Route.FILE) {
                        fileHost.onChunk(frame.payload)?.let {
                            connection.send(Frame(Route.FILE, FrameType.ACK, frame.seq, it))
                        }
                    }
                    else -> {}
                }
            }
        }
        assertTrue("被控端监听启动失败", server.start())
        try {
            val master = MasterSession(
                localDeviceName = "主控端",
                localUniqueId = "master-uid-file-it",
                crypto = DefaultSessionCrypto(),
                statusSnapshot = { StatusChannel.PeerStatus(online = true, batteryPercent = 80, storageFreeBytes = 1L, storageTotalBytes = 2L) },
            )
            assertTrue(master.pairAndConnect("127.0.0.1", port, displayedCode.toCharArray()))

            // 发端：确定性内容 700KB（跨 256KB 块边界），先按真实文件算整册哈希
            val outbox = tmpDir.resolve("outbox").apply { mkdirs() }
            val data = ByteArray(700 * 1024) { i -> ((i * 31 + 7) % 256).toByte() }
            val file = outbox.resolve("report.bin").apply { writeBytes(data) }
            val meta = com.tao.tao.link.file.FileTransfer.Meta(
                file.name, file.length(), FileReceiver.sha256Hex(file),
            )

            val done = CountDownLatch(1)
            val verified = AtomicReference<Boolean>()
            master.onFileReceipt = { payload ->
                val obj = JSONObject(String(payload, Charsets.UTF_8))
                if (obj.optBoolean("complete", false) && obj.optString("error") != "sha_mismatch") {
                    verified.set(true)
                    done.countDown()
                }
            }
            master.sendFileMeta(
                JSONObject().put("name", meta.name).put("sizeBytes", meta.sizeBytes).put("sha256", meta.sha256)
                    .put("chunkSize", com.tao.tao.link.file.MasterFileSender.DEFAULT_CHUNK_SIZE)
                    .toString().toByteArray(Charsets.UTF_8),
            )
            val sender = com.tao.tao.link.file.MasterFileSender(outbox, sendChunk = { payload -> master.sendFileChunk(payload) })
            sender.send(meta, onProgress = {})

            assertTrue("对端未在时限内回执收妥", done.await(30, TimeUnit.SECONDS))
            assertTrue("收端应回执校验通过", verified.get() == true)
            assertEquals(
                "收端落盘文件必须与发端逐字节一致",
                meta.sha256,
                FileReceiver.sha256Hex(inboxDir.resolve("report.bin")),
            )
        } finally {
            server.stop()
        }
    }
}

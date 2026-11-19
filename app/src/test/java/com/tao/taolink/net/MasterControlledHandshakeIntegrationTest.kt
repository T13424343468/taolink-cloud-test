package com.tao.tao.link.net

import com.tao.tao.link.protocol.DeviceFingerprint
import com.tao.tao.link.protocol.DeviceIdentity
import com.tao.tao.link.protocol.Frame
import com.tao.tao.link.protocol.FrameType
import com.tao.tao.link.protocol.JsonText
import com.tao.tao.link.protocol.PairingCode
import com.tao.tao.link.protocol.PairingRecordStore
import com.tao.tao.link.protocol.PairingResponder
import com.tao.tao.link.protocol.Route
import com.tao.tao.link.protocol.DefaultSessionCrypto
import com.tao.tao.link.status.StatusChannel
import com.tao.tao.link.transport.SessionServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 两端同进程真连集成测试（真 socket，非 mock）：
 * 2026-11-19 双机实弹连环定因的教训——单元测试各自测各自的，契约对不上照样全绿。
 * 本测试把 HELLO→PAIR_REQUEST→PAIR_CONFIRM 全链路真实跑一遍，此类病根写码时即拦。
 */
class MasterControlledHandshakeIntegrationTest {

    private val tmpDir: File = File.createTempFile("taolink-it", "").let { it.delete(); it }

    private fun newControlledResponder(): PairingResponder =
        PairingResponder(
            DeviceIdentity.ownFingerprint(tmpDir.resolve("ctrl"), "被控端"),
            PairingRecordStore(tmpDir.resolve("ctrl")),
        )

    /** 被控端最小实现：与 ControlledSessionService 相同的帧契约（HELLO 应答 + PAIR_REQUEST 校验）。 */
    private fun startControlledSide(port: Int, responder: PairingResponder): SessionServer {
        val own = DeviceIdentity.ownFingerprint(tmpDir.resolve("ctrl"), "被控端")
        val server = SessionServer(port) { connection ->
            connection.startReading { frame ->
                when (frame.type) {
                    FrameType.HELLO -> connection.send(
                        Frame(Route.CONTROL, FrameType.HELLO, frame.seq, helloBytes(own)),
                    )
                    FrameType.PAIR_REQUEST -> {
                        val parts = String(frame.payload, Charsets.UTF_8).split("\t")
                        val confirm = if (parts.size != 3) null else responder.onSubmit(
                            parts[0],
                            DeviceFingerprint(parts[2], parts[1]),
                            System.currentTimeMillis(),
                        )
                        connection.send(
                            Frame(Route.CONTROL, FrameType.PAIR_CONFIRM, frame.seq, confirm?.toByteArray(Charsets.UTF_8) ?: byteArrayOf(0)),
                        )
                    }
                    else -> {}
                }
            }
        }
        assertTrue("被控端监听启动失败", server.start())
        return server
    }

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    private fun helloBytes(own: DeviceFingerprint): ByteArray = JsonText.obj(
        JsonText.field("v", 1),
        JsonText.field("name", own.deviceName),
        JsonText.field("fp", own.hex),
    ).toByteArray(Charsets.UTF_8)

    @Test
    fun `正确配对码端到端握手成功且指纹一致`() {
        val port = freePort()
        val responder = newControlledResponder()
        val displayedCode = responder.displayCode()
        val server = startControlledSide(port, responder)
        try {
            val master = MasterSession(
                localDeviceName = "主控端",
                localUniqueId = "master-uid-it",
                crypto = DefaultSessionCrypto(),
                statusSnapshot = { StatusChannel.PeerStatus(online = true, batteryPercent = 80, storageFreeBytes = 1L, storageTotalBytes = 2L) },
            )
            val paired = CountDownLatch(1)
            val peerFingerprint = AtomicReference<String>()
            master.onPairCompleted = { peer ->
                peerFingerprint.set(peer.hex)
                paired.countDown()
            }
            val controlledFp = DeviceIdentity.ownFingerprint(tmpDir.resolve("ctrl"), "被控端").hex
            val ok = master.pairAndConnect("127.0.0.1", port, displayedCode.toCharArray())
            assertTrue("正确码应配对成功", ok)
            assertTrue("应触发配对完成回调", paired.await(10, TimeUnit.SECONDS))
            assertEquals("对端指纹必须等于被控端本端指纹", controlledFp, peerFingerprint.get())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `错误配对码不放行`() {
        val port = freePort()
        val responder = newControlledResponder()
        responder.displayCode() // 签发真实码，但主控端故意输错
        val server = startControlledSide(port, responder)
        try {
            val master = MasterSession(
                localDeviceName = "主控端",
                localUniqueId = "master-uid-it",
                crypto = DefaultSessionCrypto(),
                statusSnapshot = { StatusChannel.PeerStatus(online = true, batteryPercent = 80, storageFreeBytes = 1L, storageTotalBytes = 2L) },
            )
            val wrongCode = PairingCode.generate()
            assumeTrue("错码不得碰巧等于真码", wrongCode != responder.displayCode())
            val ok = master.pairAndConnect("127.0.0.1", port, wrongCode.toCharArray())
            assertFalse("错码不得配对成功", ok)
        } finally {
            server.stop()
        }
    }

    private fun assumeTrue(msg: String, cond: Boolean) {
        if (!cond) throw org.junit.AssumptionViolatedException(msg)
    }
}

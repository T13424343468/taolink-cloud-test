package com.tao.tao.link

import com.tao.tao.link.control.ControlChannel
import com.tao.tao.link.control.ControlDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlDispatcherTest {

    /** 记录型执行器：断言解析结果，不碰无障碍真服务。 */
    private class RecordingExecutor : ControlChannel {
        var setNodeTextArgs: Pair<String, String>? = null
        var gesture: ControlChannel.Gesture? = null
        var keyCode: Int? = null
        var nextResult = true
        override fun setNodeText(nodeId: String, text: String): Boolean {
            setNodeTextArgs = nodeId to text
            return nextResult
        }
        override fun dispatchGesture(gesture: ControlChannel.Gesture): Boolean {
            this.gesture = gesture
            return nextResult
        }
        override fun sendKey(keyCode: Int): Boolean {
            this.keyCode = keyCode
            return nextResult
        }
    }

    @Test
    fun dispatch_setTextPayload_routesToNodeText() {
        val executor = RecordingExecutor()
        val payload = byteArrayOf(0) + "com.example/id\t你好世界".toByteArray()
        assertTrue(ControlDispatcher.dispatch(payload, executor))
        assertEquals("com.example/id" to "你好世界", executor.setNodeTextArgs)
    }

    @Test
    fun dispatch_setTextWithoutTabSeparator_fails() {
        val executor = RecordingExecutor()
        val payload = byteArrayOf(0) + "noseparator".toByteArray()
        assertFalse(ControlDispatcher.dispatch(payload, executor))
    }

    @Test
    fun dispatch_gesturePayload_routesToDispatchGesture() {
        val executor = RecordingExecutor()
        // kind=0(TAP) x=1 y=2 x2=3 y2=4 duration=5
        val payload = byteArrayOf(1) + "0 1.0 2.0 3.0 4.0 5.0".toByteArray()
        assertTrue(ControlDispatcher.dispatch(payload, executor))
        val g = executor.gesture!!
        assertEquals(ControlChannel.Gesture.Kind.TAP, g.kind)
        assertEquals(1.0f, g.x, 0.001f)
        assertEquals(2.0f, g.y, 0.001f)
        assertEquals(5L, g.durationMs)
    }

    @Test
    fun dispatch_gestureUnknownKind_fails() {
        val executor = RecordingExecutor()
        val payload = byteArrayOf(1) + "9 1 2 3 4 5".toByteArray()
        assertFalse(ControlDispatcher.dispatch(payload, executor))
    }

    @Test
    fun dispatch_keyPayload_routesToSendKey() {
        val executor = RecordingExecutor()
        val keyCode = 4 // BACK
        val payload = byteArrayOf(2, 0, 0, 0, keyCode.toByte())
        assertTrue(ControlDispatcher.dispatch(payload, executor))
        assertEquals(keyCode, executor.keyCode)
    }

    @Test
    fun dispatch_keyWrongPayloadLength_fails() {
        val executor = RecordingExecutor()
        val payload = byteArrayOf(2, 0, 0, 0)
        assertFalse(ControlDispatcher.dispatch(payload, executor))
    }

    @Test
    fun dispatch_emptyOrUnknownType_fails() {
        val executor = RecordingExecutor()
        assertFalse(ControlDispatcher.dispatch(ByteArray(0), executor))
        assertFalse(ControlDispatcher.dispatch(byteArrayOf(9), executor))
    }
}

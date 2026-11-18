package com.tao.tao.link.transport

import com.tao.tao.link.protocol.Frame
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 被控端会话监听：单端口收主控端连接，帧按路由分发给会话处理器。
 * 单线程 accept + 每连接一线程（局域网少连接场景，稳定优先不搞并发池花活）。
 */
class SessionServer(
    private val port: Int,
    private val onConnected: (SessionConnection) -> Unit,
) {
    private var server: ServerSocket? = null
    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "taolink-session").apply { isDaemon = true }
    }

    fun start(): Boolean {
        if (server != null) return true
        return try {
            val s = ServerSocket()
            s.bind(InetSocketAddress(port))
            server = s
            executor.execute {
                while (!s.isClosed) {
                    val client = try {
                        s.accept()
                    } catch (e: Exception) {
                        break
                    }
                    onConnected(SessionConnection(client))
                }
            }
            true
        } catch (e: Exception) {
            server = null
            false
        }
    }

    fun stop() {
        server?.let {
            try { it.close() } catch (e: Exception) { /* 关闭失败无伤，端口随进程回收 */ }
        }
        server = null
    }
}

/** 一条已建立的连接：收帧回调 + 发帧出口。 */
class SessionConnection internal constructor(private val socket: Socket) {

    private val outputLock = Any()

    fun startReading(onFrame: (Frame) -> Unit) {
        Thread {
            val input = socket.getInputStream()
            while (true) {
                val frame = FrameIo.readFrame(input) ?: break
                onFrame(frame)
            }
            close()
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun send(frame: Frame): Boolean = synchronized(outputLock) {
        FrameIo.writeFrame(socket.getOutputStream(), frame)
    }

    fun close() {
        try { socket.close() } catch (e: Exception) { /* 已断开，无伤 */ }
    }
}

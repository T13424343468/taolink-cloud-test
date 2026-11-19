package com.tao.tao.link.net

/**
 * 主控端活会话持有者（单槽）：配对成功即挂入，通话/操控页从这取活会话。
 * 旧账：ConnectingActivity 局部建 MasterSession，引用随页面销毁而丢失，通话页拿不到会话。
 * 单槽足矣——定稿原型一个主控同一时刻只连一台被控，重配对即覆盖（新会话顶旧会话）。
 */
object MasterSessionHolder {

    @Volatile
    private var session: MasterSession? = null

    fun attach(s: MasterSession) {
        session = s
    }

    fun get(): MasterSession? = session
}

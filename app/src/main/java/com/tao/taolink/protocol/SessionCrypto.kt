package com.tao.tao.link.protocol

/** 会话加密契约（协议文档 §2-4）：TLS 1.3 + 配对码派生密钥双层；密钥不出内存、不落盘。 */
interface SessionCrypto {
    /** 用配对码派生会话密钥，建立后对帧载荷透明加解密。 */
    fun deriveKey(pairCode: CharArray)

    fun encrypt(plain: ByteArray): ByteArray

    fun decrypt(cipher: ByteArray): ByteArray
}

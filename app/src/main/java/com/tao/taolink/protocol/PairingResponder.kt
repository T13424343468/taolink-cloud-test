package com.tao.tao.link.protocol

/**
 * 被控端配对应答（协议 §2-2 被控侧）：大字显示码由界面层调 [displayCode]，
 * 主控端提交码到达后校验并交换指纹、落配对记录；被控端全程零输入（需求清单 §1）。
 * 配对码带有效期（拍板口径：5 分钟，过期自动刷新），防旧码/被瞄码长期有效。
 * 纯逻辑可测；会话密钥派生由会话层用双方码派生（SessionCrypto）。
 */
class PairingResponder(
    private val ownFingerprint: DeviceFingerprint,
    private val store: PairingRecordStore,
    private val codeTtlMillis: Long = CODE_TTL_MILLIS,
) {

    private var code = PairingCode.generate()

    /** 当前码签发时刻；首次取码时起算，无时钟入参前不预签发。 */
    private var codeIssuedAt = Long.MIN_VALUE

    /**
     * 本端 6 位配对码，界面层大字显示用。
     * nowMillis 缺省走系统钟（界面层直调）；测试传显式时刻。
     * 超过有效期即重新签发（过期自动刷新），旧码随之作废。
     */
    fun displayCode(nowMillis: Long = System.currentTimeMillis()): String {
        if (codeIssuedAt == Long.MIN_VALUE || nowMillis - codeIssuedAt >= codeTtlMillis) {
            code = PairingCode.generate()
            codeIssuedAt = nowMillis
        }
        return code
    }

    /**
     * 主控端提交码 + 对端指纹到达：校验通过 → 落双方配对记录，回 PAIR_CONFIRM 载荷（本端指纹 hex）；
     * 码错或已过有效期返回 null（过期码一律作废重签），由会话层断开（猜码不放行）。
     */
    fun onSubmit(submittedCode: String, peerFingerprint: DeviceFingerprint, nowMillis: Long): String? {
        // 本端自录先行：无论对错码，本端指纹都在账上（对端可据此反向认亲，重连免输码）
        store.upsert(PairingRecord(ownFingerprint.hex, ownFingerprint.deviceName, nowMillis))
        val expired = codeIssuedAt != Long.MIN_VALUE && nowMillis - codeIssuedAt >= codeTtlMillis
        if (expired || !PairingCode.verify(code, submittedCode)) return null
        store.upsert(PairingRecord(peerFingerprint.hex, peerFingerprint.deviceName, nowMillis))
        return ownFingerprint.hex
    }

    /**
     * 一键重生成（拍板口径：5 分钟+一键重生成，老人念码慢/被瞄即换）：
     * 界面层手动触发，立即重签新码，旧码即时作废（含有效期内旧码）。
     */
    fun regenerateCode(nowMillis: Long = System.currentTimeMillis()): String {
        code = PairingCode.generate()
        codeIssuedAt = nowMillis
        return code
    }

    /** 重连免输码：指纹在配对记录中即放行（协议 §2-2）。 */
    fun isKnownPeer(fingerprintHex: String): Boolean = store.contains(fingerprintHex)

    /**
     * 连接成立回填（Y4 拍板 2026-11-18 12:27）：最后连接时间+路径类型，主页「最近连过」展示依据。
     * 指纹不在账上不隐式建账（建账只走配对，口径不混）。
     */
    fun markConnected(peerFingerprintHex: String, pathType: PathType, atMillis: Long): Boolean =
        store.markConnected(peerFingerprintHex, pathType, atMillis)

    companion object {
        /** 配对码有效期（拍板口径 2026-11-18 午：5 分钟，过期自动刷新）。 */
        const val CODE_TTL_MILLIS = 5 * 60_000L
    }
}

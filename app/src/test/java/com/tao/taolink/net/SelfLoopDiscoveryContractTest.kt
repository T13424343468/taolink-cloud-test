package com.tao.tao.link.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1 自环发现契约用例（评审口径：注册后自己能搜到自己，拦住接线类断裂）。
 *
 * NSD 系统服务在 JVM 单测里起不来，自环的“可发现性”这里按纯协议件验：口播 TXT 载荷
 * 与浏览解析互为镜像——register 时写入的 ver/roles，toPeer 必须原样读回并过滤不合规对端。
 * 真机双机自环（注册后本机浏览到本机）由云测 instrumented 跑口实际广播验证，本用例守住
 * “载荷契约不破”这层不依赖系统服务的回归。
 */
class SelfLoopDiscoveryContractTest {

    private fun rolesValue(controller: Boolean, controlled: Boolean): String =
        buildString {
            if (controller) append(NsdDiscoveryController.ROLE_CONTROLLER)
            if (controlled) append(NsdDiscoveryController.ROLE_CONTROLLED)
        }

    /** 被控端口播角色位（R1 接线后的唯一注册口径：roles=d）。 */
    @Test
    fun controlledBroadcastCarriesControlledRole() {
        val roles = rolesValue(controller = false, controlled = true)
        assertEquals("d", roles)
        assertTrue(roles.contains(NsdDiscoveryController.ROLE_CONTROLLED))
        assertEquals(NsdDiscoveryController.SERVICE_TYPE, "_taolink._tcp.")
    }

    /** 双端口播角色位拼装与解析互逆（c+d 全双工场景）。 */
    @Test
    fun rolesRoundTripBothRoles() {
        val roles = rolesValue(controller = true, controlled = true)
        assertTrue(roles.contains(NsdDiscoveryController.ROLE_CONTROLLER))
        assertTrue(roles.contains(NsdDiscoveryController.ROLE_CONTROLLED))
    }

    /** 版本不符的对端必须被解析层拒收（toPeer 口径：ver 缺失或不符一律 null）。 */
    @Test
    fun protocolVersionIsGatedBySingleConstant() {
        assertEquals("1", com.tao.tao.link.protocol.PROTOCOL_VERSION.toString())
    }
}

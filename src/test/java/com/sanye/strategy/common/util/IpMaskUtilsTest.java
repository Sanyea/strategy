package com.sanye.strategy.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * IpMaskUtils 单测 — IPv4 末段掩码 / IPv6 interim 规则 / 边界原样返回
 */
class IpMaskUtilsTest {

    @Test
    void masksIpv4LastSegment() {
        assertEquals("192.168.1.***", IpMaskUtils.maskLastSegment("192.168.1.100"));
    }

    @Test
    void masksIpv6KeepingFirstThreeGroups() {
        assertEquals("2001:db8:abcd::***",
                IpMaskUtils.maskLastSegment("2001:db8:abcd:1234::1"));
    }

    @Test
    void returnsNullOrBlankAsIs() {
        assertNull(IpMaskUtils.maskLastSegment(null));
        assertEquals("", IpMaskUtils.maskLastSegment(""));
        assertEquals("   ", IpMaskUtils.maskLastSegment("   "));
    }

    @Test
    void returnsUnparseableAsIs() {
        assertEquals("not-an-ip", IpMaskUtils.maskLastSegment("not-an-ip"));
        assertEquals("10.0.0", IpMaskUtils.maskLastSegment("10.0.0"));
    }
}
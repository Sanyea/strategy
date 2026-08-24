package com.sanye.strategy.common.util;

/**
 * <p>
 * IP 掩码工具 — 产生端 IP 分级策略的「末段掩码」实现
 * </p>
 * <p>
 * 规格 6.3：普通登录事件与请求轨接入访问的 IP 末段掩码（保留网段抹主机位，兼顾统计与隐私）。
 * 高威胁安全事件不走本工具（完整 IP 保留，见 {@code SecurityEventLogger}）。
 * IPv6 掩码位数为规格待决事项 4，当前 interim 规则：保留前 3 组（/48）+ {@code ::***}，
 * 定稿后只改本类。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：纯函数工具，供 AccessLogFilter / SecurityEventLogger 产生端掩码。</li>
 *   <li>优缺点：无状态零依赖、规则收口一处；代价为不引入 IP 解析库，
 *       无法解析的串原样返回（宁可少掩不误删，兜底靠传输端 PII 正则，阶段1）。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
public final class IpMaskUtils {

    private static final String IPV4_MASK_SUFFIX = "***";
    private static final int IPV4_SEGMENT_COUNT = 4;
    private static final int IPV6_KEEP_GROUPS = 3;

    private IpMaskUtils() {
    }

    /**
     * 末段掩码：IPv4 {@code a.b.c.***}；IPv6 保留前 3 组 + {@code ::***}；
     * null/空白/无法解析原样返回
     *
     * @param ip 原始 IP
     * @return 掩码后 IP
     */
    public static String maskLastSegment(String ip) {
        if (ip == null || ip.isBlank()) {
            return ip;
        }
        if (ip.contains(":")) {
            return maskIpv6(ip);
        }
        return maskIpv4(ip);
    }

    private static String maskIpv4(String ip) {
        String[] segments = ip.split("\\.");
        if (segments.length != IPV4_SEGMENT_COUNT) {
            return ip;
        }
        for (String segment : segments) {
            if (!isDigits(segment)) {
                return ip;
            }
        }
        return segments[0] + "." + segments[1] + "." + segments[2] + "." + IPV4_MASK_SUFFIX;
    }

    private static String maskIpv6(String ip) {
        String[] groups = ip.split(":");
        if (groups.length < IPV6_KEEP_GROUPS + 1) {
            return ip;
        }
        return groups[0] + ":" + groups[1] + ":" + groups[2] + "::***";
    }

    private static boolean isDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
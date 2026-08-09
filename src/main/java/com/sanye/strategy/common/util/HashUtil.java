package com.sanye.strategy.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * <p>
 * 哈希工具 — SHA-256（Hex）
 * </p>
 * <p>
 * 用于 refreshToken 落库哈希（不存明文，碰撞时凭哈希定位会话行）。
 * </p>
 *
 * @author 31372
 */
public final class HashUtil {

    private HashUtil() {
    }

    /**
     * SHA-256 十六进制小写串；入参 null 返回 null
     */
    public static String sha256Hex(String value) {
        if (value == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

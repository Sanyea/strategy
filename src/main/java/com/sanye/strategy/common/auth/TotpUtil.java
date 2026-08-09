package com.sanye.strategy.common.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * <p>
 * TOTP 一次性密码工具 — RFC 6238（HmacSHA1，6 位数字，30s 时间步）
 * </p>
 * <p>
 * JDK 内置 {@link Mac} HmacSHA1；JDK 无内置 Base32——自实现 RFC 4648 Base32 解码（不引第三方库）。
 * {@code mfa_secret} 存 Base32 密钥；校验 30s 时间窗口 ±1（容忍时钟偏移一步）。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：TOTP 运算封装，双因子认证核心；{@link Component} Bean，供 {@code AuthService} 构造注入。</li>
 *   <li>优缺点：零第三方依赖、常量时间比较防时序侧信道；缺点：HmacSHA1 强度低于 SHA-256，
 *       对 MFA 场景足够（共享密钥 + 30s 步进），换算法只改本类常量。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Component
public class TotpUtil {

    private static final int TIME_STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    /**
     * 校验 TOTP 验证码（时间窗口 ±1 步）
     *
     * @param base32Secret Base32 密钥
     * @param code         用户输入的 6 位验证码
     * @return true 校验通过
     */
    public boolean verify(String base32Secret, String code) {
        if (base32Secret == null || base32Secret.isEmpty() || code == null || code.isEmpty()) {
            return false;
        }
        try {
            byte[] key = decodeBase32(base32Secret);
            long counter = System.currentTimeMillis() / 1000L / TIME_STEP_SECONDS;
            for (long offset = -1; offset <= 1; offset++) {
                String expected = generateForCounter(key, counter + offset);
                if (MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.US_ASCII),
                        code.getBytes(StandardCharsets.US_ASCII))) {
                    return true;
                }
            }
            return false;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 固定时间点生成 TOTP（供 RFC 6238 测试向量验证，包私有）
     */
    String generateAt(String base32Secret, long timeSeconds) {
        return generateForCounter(decodeBase32(base32Secret), timeSeconds / TIME_STEP_SECONDS);
    }

    private static String generateForCounter(byte[] key, long counter) {
        byte[] data = new byte[8];
        for (int i = 7; i >= 0; i--) {
            data[i] = (byte) (counter & 0xFF);
            counter >>= 8;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA1 不可用", e);
        }
    }

    private static byte[] decodeBase32(String base32) {
        String clean = base32.replaceAll("=", "").toUpperCase(Locale.ROOT);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;
        for (int i = 0; i < clean.length(); i++) {
            int value = BASE32_ALPHABET.indexOf(clean.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("非法 Base32 字符: " + clean.charAt(i));
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }
}

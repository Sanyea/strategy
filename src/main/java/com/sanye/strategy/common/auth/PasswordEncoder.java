package com.sanye.strategy.common.auth;

import org.mindrot.jbcrypt.BCrypt;
import org.springframework.stereotype.Component;

/**
 * <p>
 * 密码哈希工具 — BCrypt 封装
 * </p>
 * <p>
 * 库选 {@code org.mindrot:jbcrypt}（纯库，零 Spring 依赖，契合 DIP 零框架风格）。
 * 注册/改密/重置统一走本封装；密码算法演进（Argon2）只换实现类，调用方签名不变。
 * 兼容旧体系：{@code salt} 字段废弃不再写入（BCrypt 自带盐），旧数据 {@code matches} 仍可验证。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：密码学操作封装，隔离第三方库细节；{@link Component} Bean，供 {@code AuthService} 构造注入。</li>
 *   <li>优缺点：零框架依赖、单测无需 Spring 上下文（实例直接 mock）；缺点：BCrypt 单次哈希慢（<100ms），可接受。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Component
public class PasswordEncoder {

    /**
     * 明文密码 → BCrypt 哈希
     *
     * @param rawPassword 明文密码
     * @return BCrypt 哈希串（自带随机盐）
     */
    public String encode(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt());
    }

    /**
     * 校验明文密码与已存哈希是否匹配
     *
     * @param rawPassword    明文密码
     * @param encodedPassword 已存哈希（null/空直接返回 false）
     * @return true 匹配
     */
    public boolean matches(String rawPassword, String encodedPassword) {
        if (encodedPassword == null || encodedPassword.isEmpty()) {
            return false;
        }
        try {
            return BCrypt.checkpw(rawPassword, encodedPassword);
        } catch (IllegalArgumentException e) {
            // 兼容旧体系/损坏哈希：BCrypt 对非法哈希串（格式/盐版本不符）抛 IllegalArgumentException，
            // 降级视为不匹配（调用方走 401 而非 500）
            return false;
        }
    }
}

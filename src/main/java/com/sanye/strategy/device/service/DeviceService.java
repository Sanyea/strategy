package com.sanye.strategy.device.service;

import com.sanye.strategy.common.base.DefaultQueryWrapper;
import com.sanye.strategy.common.util.HashUtil;
import com.sanye.strategy.device.dto.DeviceInfo;
import com.sanye.strategy.domain.UmsUserLoginDevice;
import com.sanye.strategy.enums.DeviceTypeEnum;
import com.sanye.strategy.enums.YesNoEnum;
import com.sanye.strategy.service.UmsUserLoginDeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * <p>
 * 登录设备/会话门面 — {@code ums_user_login_device} 会话行属主
 * </p>
 * <p>
 * 认证与设备管理共用本服务，会话行读写一律经此收口（spec 3.1）：创建会话、按 refreshToken 哈希定位、
 * 轮换、失效、整户失效、列表。本批提供会话属主核心；设备管理端点（列表/踢设备）批4 在此之上扩展。
 * refreshToken 只存 SHA-256 哈希（{@code refresh_token_hash}），不存明文。
 * </p>
 * <p>
 * 设计说明（门面模式）：
 * <ul>
 *   <li>角色：子系统（{@link UmsUserLoginDeviceService} 单表数据访问）之上的门面，暴露会话级粗粒度操作。</li>
 *   <li>优点：表属主单一，会话读写规则（hash 化、is_current 语义、过期计算）集中一处；设备管理与认证共用。</li>
 *   <li>缺点：多一层抽象；批量失效（{@link #invalidateAllByUser}）逐行更新，量大时改走单条 UPDATE 批量 SQL（YAGNI，本批不引入）。</li>
 * </ul>
 * <p>
 * UML 类图：
 * <pre class="mermaid">
 * classDiagram
 *     class AuthService {
 *         +login()
 *         +refresh()
 *     }
 *     class DeviceService {
 *         +createSession()
 *         +rotateRefreshToken()
 *         +invalidateSession()
 *         +findByRefreshTokenHash()
 *     }
 *     class UmsUserLoginDeviceService {
 *         <<interface>>
 *     }
 *     AuthService --> DeviceService : 会话行读写
 *     DeviceService --> UmsUserLoginDeviceService : 数据访问
 * </pre>
 * </p>
 * </p>
 *
 * @author 31372
 */
@Service
@RequiredArgsConstructor
public class DeviceService {

    private final UmsUserLoginDeviceService loginDeviceService;
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建登录会话行
     *
     * @param userId       用户ID
     * @param info         设备信息（可 null）
     * @param loginIp      登录 IP（服务端注入）
     * @param refreshToken 明文 refreshToken（仅此处哈希后落库）
     * @param ttlDays      refresh 有效期天数
     * @return 已落库会话实体（id 即 jti）
     */
    public UmsUserLoginDevice createSession(Long userId, DeviceInfo info, String loginIp,
                                            String refreshToken, int ttlDays) {
        UmsUserLoginDevice entity = new UmsUserLoginDevice();
        entity.setUserId(userId);
        entity.setDeviceType(info == null ? null : DeviceTypeEnum.valueOf(info.getDeviceType()));
        entity.setDeviceOs(info == null ? null : info.getDeviceOs());
        entity.setDeviceBrand(info == null ? null : info.getDeviceBrand());
        entity.setDeviceModel(info == null ? null : info.getDeviceModel());
        entity.setDeviceId(info == null ? null : info.getDeviceId());
        entity.setAppVersion(info == null ? null : info.getAppVersion());
        entity.setLoginIp(loginIp);
        entity.setLoginTime(LocalDateTime.now());
        entity.setExpireTime(LocalDateTime.now().plusDays(ttlDays));
        entity.setIsCurrent(YesNoEnum.YES);
        entity.setRefreshTokenHash(HashUtil.sha256Hex(refreshToken));
        loginDeviceService.insert(entity);
        return entity;
    }

    /**
     * 按 refreshToken 哈希定位有效会话（未逻辑删除 + is_current=1）
     *
     * @param hash refreshToken SHA-256 哈希
     * @return 会话实体，未找到返回 null
     */
    public UmsUserLoginDevice findByRefreshTokenHash(String hash) {
        return loginDeviceService.getOne(new DefaultQueryWrapper<UmsUserLoginDevice>()
                .eq("refresh_token_hash", hash)
                .eq("is_current", YesNoEnum.YES.getCode()));
    }

    /**
     * 条件轮换 refreshToken（防重放 + 原子性）：
     * <p>
     * 在共享 {@link TransactionTemplate} 单事务内，以 {@code FOR UPDATE} 悲观锁重读会话行，
     * 仅当库中 {@code refresh_token_hash} 仍等于旧哈希时才写新哈希——两个并发请求携带同一
     * refreshToken 同时轮换时，先到者成功、后到者因哈希不匹配返回 false，保证「一次一换」。
     * 更新为部分更新（仅 id + 新哈希 + 新过期时间），其余字段不动。
     * </p>
     *
     * @param sessionId         会话行 ID（jti）
     * @param oldRefreshTokenHash 轮换前旧 refreshToken 的 SHA-256 哈希（比对凭证）
     * @param newRefreshToken   明文新 refreshToken（仅此处哈希后落库）
     * @param ttlDays           新 refresh 有效期天数
     * @return true 轮换成功；false 会话行不存在或哈希已被并发轮换（调用方按 TOKEN_EXPIRED 处理）
     */
    public boolean rotateRefreshToken(Long sessionId, String oldRefreshTokenHash, String newRefreshToken, int ttlDays) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            // FOR UPDATE 悲观锁重读（getOne 见 last() 不再追加 LIMIT 1，主键唯一无碍）
            UmsUserLoginDevice current = loginDeviceService.getOne(new DefaultQueryWrapper<UmsUserLoginDevice>()
                    .eq("id", sessionId)
                    .last("FOR UPDATE"));
            if (current == null) {
                return false;
            }
            // 库中哈希已非旧值 → 已被并发轮换，拒绝本次轮换
            if (!Objects.equals(oldRefreshTokenHash, current.getRefreshTokenHash())) {
                return false;
            }
            UmsUserLoginDevice entity = new UmsUserLoginDevice();
            entity.setId(sessionId);
            entity.setRefreshTokenHash(HashUtil.sha256Hex(newRefreshToken));
            entity.setExpireTime(LocalDateTime.now().plusDays(ttlDays));
            loginDeviceService.updateById(entity);
            return true;
        }));
    }

    /**
     * 失效单会话（登出）
     */
    public void invalidateSession(Long sessionId) {
        UmsUserLoginDevice entity = new UmsUserLoginDevice();
        entity.setId(sessionId);
        entity.setIsCurrent(YesNoEnum.NO);
        loginDeviceService.updateById(entity);
    }

    /**
     * 失效某用户全部会话（改密吊销/后台冻结注销，批3/批5 使用）
     */
    public void invalidateAllByUser(Long userId) {
        for (UmsUserLoginDevice session : listByUser(userId)) {
            invalidateSession(session.getId());
        }
    }

    /**
     * 用户全部会话行（按登录时间倒序）
     */
    public List<UmsUserLoginDevice> listByUser(Long userId) {
        return loginDeviceService.list(new DefaultQueryWrapper<UmsUserLoginDevice>()
                .eq("user_id", userId)
                .orderByDesc("login_time"));
    }
}

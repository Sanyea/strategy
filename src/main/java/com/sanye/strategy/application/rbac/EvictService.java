package com.sanye.strategy.application.rbac;

import com.sanye.strategy.domain.user.entity.UmsUserLoginDevice;
import com.sanye.strategy.domain.user.repository.UmsUserLoginDeviceService;
import com.sanye.strategy.domain.user.repository.UmsUserRoleService;
import com.sanye.strategy.infrastructure.redis.JtiBlacklistService;
import com.sanye.strategy.infrastructure.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * <p>
 * 用户踢下线服务 — 查活动会话写 jti 黑名单（TTL=token 剩余 exp）
 * </p>
 * <p>
 * 功能权限 JWT 快照变更后经此立即收回 accessToken（jti 黑名单秒级冻结）。
 * 与数据权限（实时）无关；本服务只收权限快照。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：权限变更 → accessToken 即时失效的执行器；隔离 Redis 黑名单写入与分页遍历策略。</li>
 *   <li>优缺点：秒级吊销、TTL 精确到会话剩余 exp（避免黑名单残留占用）；代价为逐会话写 Redis
 *       （大批量经 {@link EvictTaskRegistry} 异步执行，见 spec 待办——内存实现重启丢任务）。</li>
 *   <li>重试：Redis 抖动指数退避 3 次，仍失败上抛 → 调用方审计失败状态（权限变更已生效，仅踢人延迟）。</li>
 * </ul>
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvictService {

    private final UmsUserLoginDeviceService deviceService;
    private final UmsUserRoleService userRoleService;
    private final JtiBlacklistService jtiBlacklistService;
    private final JwtUtil jwtUtil;

    private static final int EVICT_RETRY = 3;
    private static final long BACKOFF_BASE_MS = 100L;

    /**
     * 踢用户集：查活动会话，写 jti 黑名单（TTL=token 剩余），已过期会话跳过
     *
     * @param userIds 用户ID集合
     * @return 踢中会话数
     */
    public int evictUsers(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        List<UmsUserLoginDevice> sessions = deviceService.listActiveSessionsByUserIds(userIds);
        int kicked = 0;
        for (UmsUserLoginDevice s : sessions) {
            long ttl = remainingTtlSeconds(s);
            if (ttl <= 0) {
                continue;
            }
            revokeWithRetry(s.getId(), ttl);
            kicked++;
        }
        return kicked;
    }

    /**
     * 角色下活动用户 evict（同步，offset 分页遍历，供自动链路小量 + 调试接口）
     *
     * @param roleId 角色ID
     * @return 踢中会话数
     */
    public int evictRoleUsers(Long roleId) {
        int kicked = 0;
        long offset = 0;
        List<Long> ids;
        do {
            ids = userRoleService.listActiveUserIdsByRoleId(roleId, offset, 500);
            offset += ids.size();
            kicked += evictUsers(ids);
        } while (ids.size() == 500);   // 无 offset 会反复返回同一批 500，用户 501+ 永不踢
        return kicked;
    }

    /**
     * Redis 抖动重试 3 次（指数退避）；仍失败上抛 → 调用方审计失败状态（权限变更已生效，仅踢人延迟）
     */
    private void revokeWithRetry(Long jti, long ttl) {
        for (int i = 0; i < EVICT_RETRY; i++) {
            try {
                jtiBlacklistService.revoke(jti, ttl);
                return;
            } catch (Exception e) {
                if (i == EVICT_RETRY - 1) {
                    log.error("evict jti={} 重试{}次仍失败", jti, EVICT_RETRY, e);
                    throw e;
                }
                try {
                    Thread.sleep(BACKOFF_BASE_MS << i);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /**
     * 会话剩余有效秒数；expireTime 为 null 时回落到 accessToken 全局 TTL（兜底保守值）
     * <p>已过期（剩余 ≤0）返回 -1 上抛跳过信号——evictUsers 遇 {@code ttl <= 0} 不写黑名单；
     * 未过期返回正剩余秒（截断取整，至少 1）。</p>
     */
    private long remainingTtlSeconds(UmsUserLoginDevice session) {
        if (session.getExpireTime() == null) {
            return jwtUtil.getAccessTokenTtlSeconds();
        }
        long seconds = Duration.between(LocalDateTime.now(), session.getExpireTime()).getSeconds();
        if (seconds <= 0) {
            return -1;
        }
        return Math.max(1, seconds);
    }
}

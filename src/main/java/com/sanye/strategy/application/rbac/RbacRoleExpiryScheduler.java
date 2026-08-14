package com.sanye.strategy.application.rbac;

import com.sanye.strategy.domain.enums.OperTypeEnum;
import com.sanye.strategy.domain.user.entity.UmsUserRole;
import com.sanye.strategy.domain.user.repository.UmsUserRoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** 角色到期自动 evict — 只扫已过期绑定（end_time <= now），踢受影响用户收回过期授权
 * <p>与功能权限（JWT 快照）对比：本任务兜底「token 有效期内角色到期」安全窗口。
 * 不提前扫（提前踢会在角色真正到期前反复踢——重登新 token 仍含该角色，体验受损）；
 * begin_time 到达不踢（缺权限安全默认，refresh/重登自然获得）。
 * 周期幂等：全量扫已过期，会话黑名单 TTL 取剩余，已过期会话跳过，无重复踢实际效果。
 * 集群：本期单实例；多实例部署须分布式锁保证同刻仅一实例执行（TODO）。
 * 审计：批量记录一条摘要（oper_type=EVICT_USER，来源「角色到期定时任务」）。</p> */
@Component
@RequiredArgsConstructor
@Slf4j
public class RbacRoleExpiryScheduler {
    private static final int BATCH_SIZE = 500;
    private final UmsUserRoleService userRoleService;
    private final EvictService evictService;
    private final OperLogService operLogService;

    // fixedDelayString + timeUnit=MINUTES：Spring 6.1+ 支持 String 变体指定 timeUnit（验证通过后沿用；
    // 若不支持则 fixedDelayString 退化为 1ms 每周期——届时改用 fixedDelay = 60_000L）
    @Scheduled(fixedDelayString = "${rbac.expiry-scan-interval-minutes:1}", timeUnit = TimeUnit.MINUTES)
    public void scanExpiredRoles() {
        int totalExpired = 0, totalKicked = 0;
        long offset = 0;
        List<UmsUserRole> expired;
        do {
            // offset 分页：全量扫完已过期（无 offset 会每周期反复处理同一批前 500，后续饿死）
            expired = userRoleService.listExpired(LocalDateTime.now(), offset, BATCH_SIZE);
            offset += expired.size();
            if (expired.isEmpty()) { break; }
            Set<Long> userIds = expired.stream().map(UmsUserRole::getUserId).collect(Collectors.toSet());
            totalKicked += evictService.evictUsers(userIds);
            totalExpired += expired.size();
        } while (expired.size() == BATCH_SIZE);
        if (totalExpired == 0) { return; }
        // 批量摘要审计（不逐用户）
        operLogService.record(OperLogReq.builder()
                .module("rbac").action("evict").type(OperTypeEnum.EVICT_USER)
                .desc("角色到期定时任务 已过期绑定=" + totalExpired + " 踢中会话=" + totalKicked)
                .success(true).build());
        log.info("RBAC 角色到期扫描: {} 条已过期绑定，踢中 {} 会话", totalExpired, totalKicked);
    }
}

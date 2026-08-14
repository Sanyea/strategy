package com.sanye.strategy.infrastructure.config;

import com.sanye.strategy.application.rbac.PermissionSyncService;
import com.sanye.strategy.application.rbac.SyncReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * <p>
 * 启动权限扫描注册 — 只做新增+复活，不做残留停用（防误停用，发布流程强制手动 sync）
 * </p>
 * <p>
 * 通过 {@code rbac.sync-on-startup} 开关控制（默认 true）；false 时跳过启动扫描，
 * 仅保留手动 sync 通道。
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PermissionScanRegistrar implements ApplicationRunner {

    private final PermissionSyncService syncService;

    /**
     * 启动是否执行权限扫描注册（默认 true）
     */
    @Value("${rbac.sync-on-startup:true}")
    private boolean syncOnStartup;

    @Override
    public void run(ApplicationArguments args) {
        if (syncOnStartup) {
            SyncReport r = syncService.syncStartup();
            log.info("RBAC 权限启动同步: 新增{}, 复活{}, 残留停用{}",
                    r.getAdded().size(), r.getRevived().size(), r.getDeprecated().size());
        }
    }
}

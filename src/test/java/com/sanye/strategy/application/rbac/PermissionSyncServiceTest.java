package com.sanye.strategy.application.rbac;

import com.sanye.strategy.common.base.IWrapper;
import com.sanye.strategy.domain.enums.PermissionTypeEnum;
import com.sanye.strategy.domain.enums.RoleStatusEnum;
import com.sanye.strategy.domain.rbac.entity.UmsPermission;
import com.sanye.strategy.domain.rbac.repository.UmsPermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <p>
 * 权限同步服务单测 — 扫描桩 + mock {@link UmsPermissionService}
 * </p>
 * <p>
 * 扫描结果以同包匿名子类覆写包级私有钩子 {@code scanAnnotatedCodes()} 注入（确定性），
 * 而非 Mockito spy（spy 无法拦截 doSync 内部 {@code this.scanAnnotatedCodes()} 自调用）。
 * 违规权限码 fail-fast 以同构异常模拟真实扫描抛错（PermissionCodeValidator 职责由
 * {@code PermissionCodeValidatorTest} 覆盖）。
 * </p>
 */
class PermissionSyncServiceTest {

    private UmsPermissionService permissionService;
    private PermissionSyncService svc;

    @BeforeEach
    void setUp() {
        permissionService = mock(UmsPermissionService.class);
        svc = new PermissionSyncService(permissionService) {
            @Override
            Map<String, String> scanAnnotatedCodes() {
                return scannedCodes();
            }
        };
    }

    /**
     * 扫描结果桩：新增 2（create/role:manage）、复活 1（update）、残留停用 1
     * （permission:manage 不在扫描集，须仅在手动同步时停用）
     */
    private static Map<String, String> scannedCodes() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("system:user:create", "UserController");
        m.put("system:user:update", "UserController");
        m.put("system:role:manage", "RoleController");
        return m;
    }

    private static UmsPermission permission(String code, RoleStatusEnum status, PermissionTypeEnum type) {
        UmsPermission p = new UmsPermission();
        p.setPermissionCode(code);
        p.setStatus(status);
        p.setPermissionType(type);
        return p;
    }

    private void stubExisting() {
        when(permissionService.list(any(IWrapper.class))).thenReturn(List.of(
                permission("system:user:update", RoleStatusEnum.DISABLED, PermissionTypeEnum.INTERFACE),
                permission("system:permission:manage", RoleStatusEnum.NORMAL, PermissionTypeEnum.INTERFACE)));
    }

    @Test
    void syncAddsRevivesAndDeprecates() {
        stubExisting();
        SyncReport report = svc.sync(false);

        assertEquals(List.of("system:user:create", "system:role:manage"), report.getAdded());
        assertEquals(List.of("system:user:update"), report.getRevived());
        assertEquals(List.of("system:permission:manage"), report.getDeprecated());

        verify(permissionService, times(2)).insert(any(UmsPermission.class));
        verify(permissionService, times(2)).updateById(any(UmsPermission.class));

        // 复活码状态置 NORMAL、残留码状态置 DISABLED
        ArgumentCaptor<UmsPermission> updated = ArgumentCaptor.forClass(UmsPermission.class);
        verify(permissionService, times(2)).updateById(updated.capture());
        Map<String, RoleStatusEnum> statuses = updated.getAllValues().stream()
                .collect(Collectors.toMap(UmsPermission::getPermissionCode, UmsPermission::getStatus));
        assertEquals(RoleStatusEnum.NORMAL, statuses.get("system:user:update"));
        assertEquals(RoleStatusEnum.DISABLED, statuses.get("system:permission:manage"));
    }

    @Test
    void syncDryRunReturnsDiffWithoutWriting() {
        stubExisting();
        SyncReport report = svc.sync(true);

        assertEquals(List.of("system:user:create", "system:role:manage"), report.getAdded());
        assertEquals(List.of("system:user:update"), report.getRevived());
        assertEquals(List.of("system:permission:manage"), report.getDeprecated());

        verify(permissionService, never()).insert(any());
        verify(permissionService, never()).updateById(any());
    }

    @Test
    void startupSyncSkipsDeprecate() {
        stubExisting();
        SyncReport report = svc.syncStartup();

        assertEquals(List.of("system:user:create", "system:role:manage"), report.getAdded());
        assertEquals(List.of("system:user:update"), report.getRevived());
        assertTrue(report.getDeprecated().isEmpty());

        verify(permissionService, times(2)).insert(any(UmsPermission.class));
        verify(permissionService, times(1)).updateById(any(UmsPermission.class));
    }

    @Test
    void invalidScannedCodePropagatesFailFast() {
        PermissionSyncService failing = new PermissionSyncService(permissionService) {
            @Override
            Map<String, String> scanAnnotatedCodes() {
                // 真实 scanAnnotatedCodes 对违规码抛 IllegalArgumentException（PermissionCodeValidator），
                // 此处以同构异常模拟：doSync 不得吞掉，须向上传播（fail-fast）
                throw new IllegalArgumentException("权限码不能为空");
            }
        };
        assertThrows(IllegalArgumentException.class, () -> failing.sync(false));
        verify(permissionService, never()).insert(any());
        verify(permissionService, never()).updateById(any());
    }

    @Test
    void realScanFindsAnnotatedClasses() {
        // 真实类路径扫描（非桩）：interfaces 测试夹具类被扫描并校验（类级 + 方法级注解）
        PermissionSyncService real = new PermissionSyncService(permissionService);
        Map<String, String> scanned = real.scanAnnotatedCodes();
        assertTrue(scanned.containsKey("system:fixture:scan"));
        assertEquals("PermissionSyncFixture", scanned.get("system:fixture:scan"));
        assertEquals("PermissionSyncFixture#detail", scanned.get("system:fixture:detail"));
    }
}

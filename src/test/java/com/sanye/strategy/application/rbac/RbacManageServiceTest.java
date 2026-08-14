package com.sanye.strategy.application.rbac;

import com.sanye.strategy.common.base.DefaultQueryWrapper;
import com.sanye.strategy.common.base.IQueryCondition;
import com.sanye.strategy.common.base.IWrapper;
import com.sanye.strategy.common.exception.BizException;
import com.sanye.strategy.common.response.ResultCode;
import com.sanye.strategy.domain.enums.RoleStatusEnum;
import com.sanye.strategy.domain.enums.YesNoEnum;
import com.sanye.strategy.domain.rbac.entity.UmsPermission;
import com.sanye.strategy.domain.rbac.repository.UmsPermissionService;
import com.sanye.strategy.domain.rbac.repository.UmsRolePermissionService;
import com.sanye.strategy.domain.user.entity.UmsRole;
import com.sanye.strategy.domain.user.repository.UmsRoleService;
import com.sanye.strategy.domain.user.repository.UmsUserRoleService;
import com.sanye.strategy.infrastructure.security.UserContext;
import com.sanye.strategy.interfaces.rbac.dto.UserRoleAssignDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <p>
 * RBAC 管理门面单测 — mock 全部依赖，事务经 {@link TransactionTemplate} +
 * mock {@link PlatformTransactionManager} 同步执行（commit/rollback 空操作）
 * </p>
 * <p>
 * 关键设置：{@code evictAsyncThreshold} 为 {@code @Value} 非 final 字段，纯单测（无 Spring
 * 上下文）下默认 0——会导致所有 evict 走异步、破坏「阈值内同步」用例。故 {@code @BeforeEach}
 * 经 {@link ReflectionTestUtils#setField} 注入 100 做阈值分流（≤100 同步、&gt;100 异步）。
 * </p>
 * <p>
 * 覆盖 brief Step 1 七用例：updateRoleStatus 停用 → 同步 evictRoleUsers；管理面过滤
 * SUPER_ADMIN 无过滤 / 普通管理员 nested(isNull.or.eq)；内置角色 delete 冲突；角色权限覆盖
 * 事务内 revokeByRoleId + grantBatch；批量授角色 begin≥end 参数校验；受影响用户数超阈值 →
 * 异步 evictTaskRegistry.submit；用户角色覆盖事务内 replaceRoles。
 * </p>
 */
class RbacManageServiceTest {

    private static final Long USER_ID = 1L;
    private static final String DEVICE_ID = "dev1";
    private static final long EVICT_THRESHOLD = 100L;

    private UmsRoleService roleService;
    private UmsPermissionService permissionService;
    private UmsUserRoleService userRoleService;
    private UmsRolePermissionService rolePermissionService;
    private PermissionSyncService syncService;
    private EvictService evictService;
    private EvictTaskRegistry evictTaskRegistry;
    private OperLogService operLogService;
    private RbacManageService facade;

    @BeforeEach
    void setUp() {
        roleService = mock(UmsRoleService.class);
        permissionService = mock(UmsPermissionService.class);
        userRoleService = mock(UmsUserRoleService.class);
        rolePermissionService = mock(UmsRolePermissionService.class);
        syncService = mock(PermissionSyncService.class);
        evictService = mock(EvictService.class);
        evictTaskRegistry = mock(EvictTaskRegistry.class);
        operLogService = mock(OperLogService.class);
        // mock 事务管理器：getTransaction 返回 null（Mockito 默认），commit/rollback 空操作，
        // TransactionTemplate.execute 同步运行事务回调
        TransactionTemplate transactionTemplate = new TransactionTemplate(mock(PlatformTransactionManager.class));
        // Boot4 默认 Jackson 3（tools.jackson），importRoles JSON 解析器
        ObjectMapper objectMapper = new ObjectMapper();
        facade = new RbacManageService(roleService, permissionService, userRoleService,
                rolePermissionService, syncService, evictService, evictTaskRegistry,
                operLogService, transactionTemplate, objectMapper);
        // 无 Spring 上下文下 @Value 字段恒 0 → 显式注入阈值，保证「阈值内同步」用例有效
        ReflectionTestUtils.setField(facade, "evictAsyncThreshold", EVICT_THRESHOLD);
        UserContext.set(new UserContext(USER_ID, List.of("OPERATOR"), List.of(), 111L, DEVICE_ID));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /** 登录上下文：角色码快照 */
    private static UserContext ctx(List<String> roles) {
        return new UserContext(USER_ID, roles, List.of(), 111L, DEVICE_ID);
    }

    /** 普通角色（非内置） */
    private static UmsRole role(Long id) {
        UmsRole r = new UmsRole();
        r.setId(id);
        r.setRoleCode("MERCHANT");
        r.setIsBuiltIn(YesNoEnum.NO);
        r.setStatus(RoleStatusEnum.NORMAL);
        return r;
    }

    @Test
    void updateRoleStatusDisabledEvictsRoleUsersSynchronouslyWithinThreshold() {
        UmsRole r = role(10L);
        when(roleService.getById(10L)).thenReturn(r);
        when(userRoleService.countUserIdsByRoleId(10L)).thenReturn(5L);   // 5 ≤ 阈值 → 同步

        facade.updateRoleStatus(10L, RoleStatusEnum.DISABLED);

        assertEquals(RoleStatusEnum.DISABLED, r.getStatus(), "角色状态应被置为停用");
        // 事务回调内更新角色
        verify(roleService).updateById(r);
        // 阈值内同步执行 evict（角色维度）
        verify(evictService).evictRoleUsers(10L);
        verify(evictTaskRegistry, never()).submit(any(Supplier.class), anyString());
    }

    @Test
    void applyManageScopeSuperAdminAddsNoCondition() {
        UserContext.set(ctx(List.of("SUPER_ADMIN")));

        IWrapper<UmsRole> w = facade.applyManageScope(new DefaultQueryWrapper<>());

        assertTrue(w.getConditions().isEmpty(), "SUPER_ADMIN 不应追加管理面过滤条件");
    }

    @Test
    void applyManageScopeNormalAdminAddsNestedIsNullOrEq() {
        UserContext.set(ctx(List.of("OPERATOR")));

        IWrapper<UmsRole> w = facade.applyManageScope(new DefaultQueryWrapper<>());

        List<IQueryCondition> conditions = w.getConditions();
        assertEquals(1, conditions.size(), "普通管理员应追加一个 nested 过滤组");
        IQueryCondition nested = conditions.get(0);
        assertEquals("nested", nested.getOperator(), "过滤应为 nested（OR 组，IWrapper 约定）");
        List<IQueryCondition> children = nested.getChildren();
        assertEquals(2, children.size(), "nested 应含 isNull + eq 两条子条件");
        assertEquals("isNull", children.get(0).getOperator());
        assertEquals("create_user_id", children.get(0).getColumn());
        assertEquals("eq", children.get(1).getOperator());
        assertEquals("create_user_id", children.get(1).getColumn());
        assertEquals(USER_ID, children.get(1).getValue());
        assertTrue(children.get(1).isOr(), "eq 应经 or() 标记为 OR 拼接");
    }

    @Test
    void deleteBuiltInRoleThrowsConflict() {
        UmsRole builtIn = new UmsRole();
        builtIn.setId(10L);
        builtIn.setRoleCode("SUPER_ADMIN");
        builtIn.setIsBuiltIn(YesNoEnum.YES);
        when(roleService.getById(10L)).thenReturn(builtIn);

        BizException ex = assertThrows(BizException.class, () -> facade.deleteRole(10L));

        assertEquals(ResultCode.CONFLICT, ex.getResultCode(), "内置角色删除应抛资源冲突");
        verify(roleService, never()).deleteById(10L);
    }

    @Test
    void replaceRolePermissionsRevokesAndGrantsInsideTransaction() {
        List<Long> permissionIds = List.of(1L, 2L);

        facade.replaceRolePermissions(10L, permissionIds);

        // 事务回调内：清空 → 批量授权（grantUserId 取 UserContext）
        verify(rolePermissionService).revokeByRoleId(10L);
        verify(rolePermissionService).grantBatch(eq(10L), eq(permissionIds), eq(USER_ID));
        // 权限变化 → 角色维度 evict
        verify(evictService).evictRoleUsers(10L);
    }

    @Test
    void replaceRolePermissionsSuperAdminEmptyPermsThrowsConflict() {
        // SUPER_ADMIN 禁清空权限（保直通兜底）——空权限集覆盖应抛资源冲突
        UmsRole sa = new UmsRole();
        sa.setId(10L);
        sa.setRoleCode("SUPER_ADMIN");
        sa.setIsBuiltIn(YesNoEnum.YES);
        when(roleService.getById(10L)).thenReturn(sa);

        BizException ex = assertThrows(BizException.class, () -> facade.replaceRolePermissions(10L, List.of()));

        assertEquals(ResultCode.CONFLICT, ex.getResultCode(), "SUPER_ADMIN 清空权限应抛资源冲突");
        verify(rolePermissionService, never()).revokeByRoleId(10L);
    }

    @Test
    void toggleBuiltInPermissionStatusDisabledThrowsConflict() {
        // 内置资源禁停用（防止手工/误操作锁死注解扫描注册的内置权限点）
        UmsPermission p = new UmsPermission();
        p.setId(10L);
        p.setIsBuiltIn(YesNoEnum.YES);
        p.setStatus(RoleStatusEnum.NORMAL);
        when(permissionService.getById(10L)).thenReturn(p);

        BizException ex = assertThrows(BizException.class,
                () -> facade.updatePermissionStatus(10L, RoleStatusEnum.DISABLED));

        assertEquals(ResultCode.CONFLICT, ex.getResultCode(), "内置资源停用应抛资源冲突");
        verify(permissionService, never()).updateById(any());
    }

    @Test
    void assignRolesBatchBeginAfterEndThrowsBadRequest() {
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime begin = end.plusMinutes(5);   // begin > end → 参数错误

        BizException ex = assertThrows(BizException.class,
                () -> facade.assignRolesBatch(List.of(1L, 2L), 10L, begin, end));

        assertEquals(ResultCode.BAD_REQUEST, ex.getResultCode(), "begin 不早于 end 应抛请求参数错误");
        verify(userRoleService, never()).assignRole(any(Long.class), any(Long.class), any(Long.class));
    }

    @Test
    void evictAboveThresholdSubmitsAsyncTask() {
        UmsRole r = role(10L);
        when(roleService.getById(10L)).thenReturn(r);
        when(userRoleService.countUserIdsByRoleId(10L)).thenReturn(500L);   // 500 > 阈值 → 异步
        when(evictTaskRegistry.submit(any(Supplier.class), anyString())).thenReturn("evict_task_test");

        facade.updateRoleStatus(10L, RoleStatusEnum.DISABLED);

        // 超阈值走异步任务注册表，不直接同步踢人
        verify(evictTaskRegistry).submit(any(Supplier.class), eq("角色停用 roleId=10"));
        verify(evictService, never()).evictRoleUsers(10L);
    }

    @Test
    void replaceUserRolesCallsReplaceRolesInsideTransaction() {
        UserRoleAssignDTO dto = new UserRoleAssignDTO();
        dto.setRoleId(5L);

        facade.replaceUserRoles(USER_ID, List.of(dto));

        verify(userRoleService).replaceRoles(eq(USER_ID), anyList(), eq(USER_ID));
        // 用户维度 evict（受影响用户=该用户）
        verify(evictService).evictUsers(List.of(USER_ID));
    }
}

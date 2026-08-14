package com.sanye.strategy.infrastructure.security;

import com.sanye.strategy.common.base.DefaultQueryWrapper;
import com.sanye.strategy.common.base.IWrapper;
import com.sanye.strategy.domain.enums.DataScopeEnum;
import com.sanye.strategy.domain.user.entity.UmsRole;
import com.sanye.strategy.domain.user.repository.UmsRoleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <p>
 * 数据权限装配单测 — mock {@link UmsRoleService}（实时 DB 查 data_scope）
 * </p>
 * <p>
 * 断言以 {@link DefaultQueryWrapper#getConditions()} 的条件元数据为准（column/operator/value），
 * 不重建 wrapper SQL。覆盖 brief Step 1 六用例 + data_scope=3（未实现级）安全回落仅本人。
 * </p>
 */
class DataScopeBuilderTest {

    private static final String OWNER_COLUMN = "create_user_id";
    private static final Long USER_ID = 1L;

    private UmsRoleService roleService;
    private DataScopeBuilder builder;

    @BeforeEach
    void setUp() {
        roleService = mock(UmsRoleService.class);
        builder = new DataScopeBuilder(roleService);
        UserContext.clear();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /** 登录上下文：userId=1，角色/权限码快照，jti=111，device=dev1 */
    private static UserContext ctx(List<String> roles) {
        return new UserContext(USER_ID, roles, List.of(), 111L, "dev1");
    }

    private static UmsRole role(DataScopeEnum scope) {
        UmsRole r = new UmsRole();
        r.setDataScope(scope);
        return r;
    }

    /** 是否存在 eq(ownerColumn, value) 条件 */
    private static boolean hasEq(IWrapper<?> wrapper, String column, Object value) {
        return wrapper.getConditions().stream()
                .anyMatch(c -> "eq".equals(c.getOperator())
                        && column.equals(c.getColumn())
                        && value.equals(c.getValue()));
    }

    /** 是否存在作用于 ownerColumn 的 eq 条件（任一值） */
    private static boolean hasEqOn(IWrapper<?> wrapper, String column) {
        return wrapper.getConditions().stream()
                .anyMatch(c -> "eq".equals(c.getOperator()) && column.equals(c.getColumn()));
    }

    @Test
    void dataScopeAllAddsNoEq() {
        UserContext.set(ctx(List.of("MERCHANT")));
        when(roleService.list(any(IWrapper.class))).thenReturn(List.of(role(DataScopeEnum.ALL)));

        IWrapper<Object> w = builder.applyDataScope(new DefaultQueryWrapper<>(), OWNER_COLUMN);

        assertFalse(hasEqOn(w, OWNER_COLUMN), "data_scope=ALL 不应追加仅本人过滤");
        // 实时模型：仍须走一次 DB 查 data_scope
        verify(roleService, times(1)).list(any(IWrapper.class));
    }

    @Test
    void dataScopeSelfAddsEq() {
        UserContext.set(ctx(List.of("MERCHANT")));
        when(roleService.list(any(IWrapper.class))).thenReturn(List.of(role(DataScopeEnum.SELF)));

        IWrapper<Object> w = builder.applyDataScope(new DefaultQueryWrapper<>(), OWNER_COLUMN);

        assertTrue(hasEq(w, OWNER_COLUMN, USER_ID), "data_scope=SELF 应追加 eq(ownerColumn, userId)");
    }

    @Test
    void emptyRolesDefaultsToSelfEq() {
        UserContext.set(ctx(List.of()));

        IWrapper<Object> w = builder.applyDataScope(new DefaultQueryWrapper<>(), OWNER_COLUMN);

        assertTrue(hasEq(w, OWNER_COLUMN, USER_ID), "空角色应安全默认仅本人");
        verify(roleService, never()).list(any(IWrapper.class));
    }

    @Test
    void nullContextReturnsWrapperUnchanged() {
        UserContext.clear();
        // 预置既有条件，验证返回原 wrapper 不加任何新条件
        DefaultQueryWrapper<Object> pre = new DefaultQueryWrapper<>();
        pre.eq("status", 1);

        IWrapper<Object> w = builder.applyDataScope(pre, OWNER_COLUMN);

        assertFalse(hasEqOn(w, OWNER_COLUMN), "无上下文不应追加仅本人过滤");
        assertTrue(hasEq(w, "status", 1), "无上下文应保留原 wrapper 既有条件");
        verify(roleService, never()).list(any(IWrapper.class));
    }

    @Test
    void mixedAllAndSelfNoEq() {
        UserContext.set(ctx(List.of("MERCHANT", "OPERATOR")));
        when(roleService.list(any(IWrapper.class)))
                .thenReturn(List.of(role(DataScopeEnum.ALL), role(DataScopeEnum.SELF)));

        IWrapper<Object> w = builder.applyDataScope(new DefaultQueryWrapper<>(), OWNER_COLUMN);

        assertFalse(hasEqOn(w, OWNER_COLUMN), "任一角色 data_scope=ALL 优先，不应追加仅本人过滤");
    }

    @Test
    void superAdminBypassesWithoutDbLookup() {
        UserContext.set(ctx(List.of("SUPER_ADMIN")));

        IWrapper<Object> w = builder.applyDataScope(new DefaultQueryWrapper<>(), OWNER_COLUMN);

        assertFalse(hasEqOn(w, OWNER_COLUMN), "含 SUPER_ADMIN 不追加过滤");
        verify(roleService, never()).list(any(IWrapper.class));
    }

    @Test
    void unimplementedScopeDefaultsToSelfEq() {
        // data_scope=3（DEPT，部门表未建）未实现 → 安全回落仅本人，不越权
        UserContext.set(ctx(List.of("MERCHANT")));
        when(roleService.list(any(IWrapper.class))).thenReturn(List.of(role(DataScopeEnum.DEPT)));

        IWrapper<Object> w = builder.applyDataScope(new DefaultQueryWrapper<>(), OWNER_COLUMN);

        assertTrue(hasEq(w, OWNER_COLUMN, USER_ID), "未实现级 data_scope 应安全默认仅本人");
    }
}

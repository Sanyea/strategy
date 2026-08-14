package com.sanye.strategy.infrastructure.security;

import com.sanye.strategy.common.base.DefaultQueryWrapper;
import com.sanye.strategy.common.base.IWrapper;
import com.sanye.strategy.domain.enums.DataScopeEnum;
import com.sanye.strategy.domain.user.entity.UmsRole;
import com.sanye.strategy.domain.user.repository.UmsRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 数据权限装配（方案 C，本期 1/2 级）— 实时 DB 查角色 data_scope，即时生效
 * <p>与 RBAC 功能权限（JWT perms 快照）不同：本组件每次请求实时查询，data_scope 变更下请求即生效。
 * 管理面 create_user_id 过滤（谁创建看谁）与本组件是两套机制，禁止复用业务表。</p>
 */
@Component
@RequiredArgsConstructor
public class DataScopeBuilder {
    private static final String SUPER_ADMIN = "SUPER_ADMIN";
    private final UmsRoleService roleService;

    public <T> IWrapper<T> applyDataScope(IWrapper<T> wrapper, String ownerColumn) {
        UserContext ctx = UserContext.get();
        List<String> roleCodes = ctx == null ? List.of() : ctx.getRoleCodes();
        if (roleCodes.contains(SUPER_ADMIN)) { return wrapper; }
        if (!roleCodes.isEmpty()) {
            boolean anyAll = roleService.list(new DefaultQueryWrapper<UmsRole>()
                            .in("role_code", roleCodes))
                    .stream().anyMatch(r -> DataScopeEnum.ALL.equals(r.getDataScope()));
            if (anyAll) { return wrapper; }
        }
        Long userId = ctx == null ? null : ctx.getUserId();
        if (userId != null) { wrapper.eq(ownerColumn, userId); }
        return wrapper;
    }
}

package com.sanye.strategy.application.rbac;

import com.sanye.strategy.common.base.DefaultQueryWrapper;
import com.sanye.strategy.domain.rbac.repository.UmsRolePermissionService;
import com.sanye.strategy.domain.user.entity.UmsRole;
import com.sanye.strategy.domain.user.repository.UmsRoleService;
import com.sanye.strategy.domain.user.repository.UmsUserRoleService;
import com.sanye.strategy.infrastructure.security.PermissionCodeValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <p>
 * 授权查询服务 — 调试 + 当前用户查询（受 {@code rbac.debug-enabled} 开关控制）
 * </p>
 * <p>
 * 数据来源实时联表（{@code ums_user_role} → {@code ums_role} → {@code ums_role_permission}），
 * 与 {@code UserContext.getPermCodes()} 的 JWT 快照（零 DB）互为补充：快照用于请求内接口鉴权，
 * 本服务用于调试端「实际生效权限」排查（实时）。功能权限模型为 JWT 快照（变更经踢人/最长
 * 30min accessToken TTL 生效），数据权限模型为实时（data_scope 每次请求查库）——见 CLAUDE.md 备忘。
 * </p>
 *
 * @author 31372
 */
@Service
@RequiredArgsConstructor
public class RbacAuthzService {

    private static final String SUPER_ADMIN = "SUPER_ADMIN";

    /**
     * 超管通配权限标记：标识「拥有全部权限」。
     * 实时生效权限集（{@link #effectivePermissions}）与 JWT 快照（{@code AuthService.loadPermCodes}
     * perms claim）统一返回该哨兵，保证两模型生效权限语义一致。
     */
    public static final String WILDCARD = "*";

    private final UmsUserRoleService userRoleService;
    private final UmsRolePermissionService rolePermissionService;
    private final UmsRoleService roleService;

    /**
     * 用户实际生效权限码集（合并多角色去重；禁用/过期角色经联表已过滤）
     * <p>SUPER_ADMIN 返回 {@code ["*"]} 通配（等价鉴权管道直通语义）。</p>
     *
     * @param userId 用户ID
     * @return 权限码集合，无生效权限返回空集
     */
    public Set<String> effectivePermissions(Long userId) {
        List<String> roleCodes = userRoleService.getRoleCodesByUserId(userId);
        if (roleCodes.contains(SUPER_ADMIN)) {
            return Set.of(WILDCARD);
        }
        Set<String> codes = new LinkedHashSet<>();
        for (String roleCode : roleCodes) {
            UmsRole role = roleService.getOne(new DefaultQueryWrapper<UmsRole>().eq("role_code", roleCode));
            if (role != null) {
                codes.addAll(rolePermissionService.getPermissionCodesByRoleId(role.getId()));
            }
        }
        return codes;
    }

    /**
     * 用户是否拥有指定权限码
     * <p>SUPER_ADMIN 通配（{@code "*"}）命中任意权限码返回 true，与
     * {@code PermissionInterceptor} 的 SUPER_ADMIN 直通语义对齐。</p>
     *
     * @param userId         用户ID
     * @param permissionCode 三段式权限码（模块:资源:操作）
     * @return 是否拥有
     */
    public boolean checkPermission(Long userId, String permissionCode) {
        PermissionCodeValidator.validate(permissionCode);
        Set<String> perms = effectivePermissions(userId);
        return perms.contains(WILDCARD) || perms.contains(permissionCode);
    }
}

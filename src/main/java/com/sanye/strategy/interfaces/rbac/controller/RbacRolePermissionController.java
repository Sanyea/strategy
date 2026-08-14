package com.sanye.strategy.interfaces.rbac.controller;

import com.sanye.strategy.application.rbac.RbacManageService;
import com.sanye.strategy.common.response.R;
import com.sanye.strategy.common.util.BeanCopyUtils;
import com.sanye.strategy.domain.rbac.entity.UmsPermission;
import com.sanye.strategy.domain.rbac.repository.UmsPermissionService;
import com.sanye.strategy.domain.rbac.repository.UmsRolePermissionService;
import com.sanye.strategy.infrastructure.security.RequiresPermission;
import com.sanye.strategy.interfaces.rbac.dto.RolePermissionAssignDTO;
import com.sanye.strategy.interfaces.rbac.vo.PermissionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <p>
 * 角色-权限绑定端点 — 类级权限码 {@code system:role:assign}
 * </p>
 * <p>
 * 独立权限码与权限资源管理（{@code system:permission:manage}）分离，满足最小权限。
 * 覆盖/增量/回收任一写操作均触发该角色下在线用户 evict（门面编排，功能权限为 JWT 快照——
 * 变更经踢人后重登生效，最长滞后 30min accessToken TTL）。数据权限模型（data_scope）为实时，
 * 两模型差异见 CLAUDE.md 备忘。
 * </p>
 *
 * @author 31372
 */
@RestController
@RequestMapping("/rbac/roles")
@RequiredArgsConstructor
@RequiresPermission("system:role:assign")
@Tag(name = "rbac-role-permission", description = "角色权限绑定：覆盖/增量/回收/查询")
public class RbacRolePermissionController {

    private final RbacManageService rbacManageService;
    private final UmsRolePermissionService rolePermissionService;
    private final UmsPermissionService permissionService;

    /**
     * 覆盖绑定（勾选 UI，全量替换；变更自动踢该角色下用户）
     */
    @Operation(summary = "覆盖角色权限", description = "勾选 UI 全量替换；变更自动踢该角色下用户")
    @SecurityRequirement(name = "BearerAuth")
    @PutMapping("/{id}/permissions")
    public R<Void> replace(@PathVariable Long id, @Valid @RequestBody RolePermissionAssignDTO dto) {
        rbacManageService.replaceRolePermissions(id, dto.getPermissionIds());
        return R.ok();
    }

    /**
     * 增量绑定（INSERT IGNORE 静默去重；变更自动踢该角色下用户）
     */
    @Operation(summary = "增量绑定权限", description = "INSERT IGNORE 静默去重；变更自动踢该角色下用户")
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping("/{id}/permissions")
    public R<Void> grant(@PathVariable Long id, @Valid @RequestBody RolePermissionAssignDTO dto) {
        rbacManageService.grantRolePermissions(id, dto.getPermissionIds());
        return R.ok();
    }

    /**
     * 批量回收（逐条回收；变更自动踢该角色下用户）
     */
    @Operation(summary = "批量回收权限", description = "逐条回收；变更自动踢该角色下用户")
    @SecurityRequirement(name = "BearerAuth")
    @DeleteMapping("/{id}/permissions")
    public R<Void> revoke(@PathVariable Long id, @Valid @RequestBody RolePermissionAssignDTO dto) {
        rbacManageService.revokeRolePermissions(id, dto.getPermissionIds());
        return R.ok();
    }

    /**
     * 角色当前权限集（完整资源信息列表）
     */
    @Operation(summary = "角色权限集", description = "角色当前权限集（完整资源信息列表）")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/{id}/permissions")
    public R<List<PermissionVO>> permissions(@PathVariable Long id) {
        List<Long> ids = rolePermissionService.getPermissionIdsByRoleId(id);
        List<UmsPermission> list = ids.isEmpty() ? List.of() : permissionService.listByIds(ids);
        return R.ok(list.stream().map(p -> BeanCopyUtils.copy(p, PermissionVO.class)).toList());
    }
}

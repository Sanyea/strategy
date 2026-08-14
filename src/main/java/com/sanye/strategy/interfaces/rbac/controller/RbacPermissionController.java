package com.sanye.strategy.interfaces.rbac.controller;

import com.sanye.strategy.application.rbac.RbacManageService;
import com.sanye.strategy.application.rbac.SyncReport;
import com.sanye.strategy.common.base.DefaultQueryWrapper;
import com.sanye.strategy.common.exception.BizException;
import com.sanye.strategy.common.response.R;
import com.sanye.strategy.common.response.ResultCode;
import com.sanye.strategy.common.util.BeanCopyUtils;
import com.sanye.strategy.domain.enums.RoleStatusEnum;
import com.sanye.strategy.domain.rbac.entity.UmsPermission;
import com.sanye.strategy.domain.rbac.repository.UmsPermissionService;
import com.sanye.strategy.infrastructure.security.RequiresPermission;
import com.sanye.strategy.interfaces.rbac.dto.PermissionDTO;
import com.sanye.strategy.interfaces.rbac.dto.PermissionQueryDTO;
import com.sanye.strategy.interfaces.rbac.dto.StatusDTO;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 权限资源管理端点 — 类级权限码 {@code system:permission:manage}
 * </p>
 * <p>
 * 覆盖 spec「管理 API 面」RbacPermissionController 全部端点（树/新增/改/删/启停/同步）。
 * 功能权限模型为 JWT 快照（perms/roles 变更经自动踢人后重登生效，最长滞后 30min accessToken TTL）；
 * 权限启停/同步残留停用由门面自动反查绑定角色 → 用户 evict（对称：启用/复活也踢）。
 * 数据权限模型（data_scope）为实时，两模型差异见 CLAUDE.md 备忘。
 * </p>
 * <p>
 * 树端点支持可选过滤（permissionName/permissionType/permissionCode），命中节点保留祖先链，
 * 保证返回树结构完整。
 * </p>
 *
 * @author 31372
 */
@RestController
@RequestMapping("/rbac/permissions")
@RequiredArgsConstructor
@RequiresPermission("system:permission:manage")
@Tag(name = "rbac-permission", description = "权限资源管理：树/新增/改/删/启停/同步")
public class RbacPermissionController {

    private final RbacManageService rbacManageService;
    private final UmsPermissionService permissionService;

    /**
     * 目录/菜单/按钮/接口资源树（可选过滤；保留祖先链保证树完整）
     */
    @Operation(summary = "权限资源树", description = "目录/菜单/按钮/接口资源树（可选过滤；保留祖先链保证树完整）")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/tree")
    public R<List<PermissionVO>> tree(PermissionQueryDTO query) {
        List<UmsPermission> all = permissionService.list(new DefaultQueryWrapper<UmsPermission>()
                .orderByAsc("sort_order", "id"));
        List<UmsPermission> filtered = hasFilter(query) ? applyFilter(all, query) : all;
        return R.ok(buildTree(filtered));
    }

    /**
     * 新增权限资源
     */
    @Operation(summary = "新增权限资源", description = "创建目录/菜单/按钮/接口资源")
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping
    public R<Void> create(@Valid @RequestBody PermissionDTO dto) {
        rbacManageService.createPermission(dto);
        return R.ok();
    }

    /**
     * 修改权限资源（permissionCode 禁改、内置资源禁改，门面守卫）
     */
    @Operation(summary = "修改权限资源", description = "permissionCode 禁改、内置资源禁改，门面守卫")
    @SecurityRequirement(name = "BearerAuth")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody PermissionDTO dto) {
        rbacManageService.updatePermission(id, dto);
        return R.ok();
    }

    /**
     * 删除权限资源（有角色绑定禁删，先解绑）
     */
    @Operation(summary = "删除权限资源", description = "有角色绑定禁删，先解绑")
    @SecurityRequirement(name = "BearerAuth")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        rbacManageService.deletePermission(id);
        return R.ok();
    }

    /**
     * 权限启停（停用/启用对称：自动反查绑定角色 → 用户 evict，重登同步新快照）
     */
    @Operation(summary = "权限启停", description = "停用/启用对称：自动反查绑定角色 → 用户 evict，重登同步新快照")
    @SecurityRequirement(name = "BearerAuth")
    @PutMapping("/{id}/status")
    public R<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody StatusDTO dto) {
        RoleStatusEnum status = RoleStatusEnum.valueOf(dto.getStatus());
        if (status == null) {
            throw new BizException(ResultCode.BAD_REQUEST, "非法权限状态码: " + dto.getStatus());
        }
        rbacManageService.updatePermissionStatus(id, status);
        return R.ok();
    }

    /**
     * 手动权限同步（{@code ?dryRun=true} 仅预览差异不写库；默认执行——新增 + 复活 + 残留停用）
     */
    @Operation(summary = "手动权限同步", description = "dryRun=true 仅预览差异不写库；默认执行——新增 + 复活 + 残留停用")
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping("/sync")
    public R<SyncReport> sync(@RequestParam(defaultValue = "false") boolean dryRun) {
        return R.ok(rbacManageService.syncPermissions(dryRun));
    }

    // ==================== 私有辅助 ====================

    /**
     * 权限资源 → 树节点 VO
     */
    private PermissionVO toVO(UmsPermission p) {
        return BeanCopyUtils.copy(p, PermissionVO.class);
    }

    /**
     * 扁平列表 → 树（按 parentId 分组挂载 children，根节点为 parentId=0）
     */
    private List<PermissionVO> buildTree(List<UmsPermission> nodes) {
        List<PermissionVO> vos = nodes.stream().map(this::toVO).toList();
        Map<Long, List<PermissionVO>> byParent = vos.stream()
                .collect(Collectors.groupingBy(this::parentKey));
        // 子节点键为节点自身 id（children = parentId 等于本节点 id 的节点）；
        // 误用 parentKey(n)（自身 parentId）会取到同级兄弟+自身 → children 自引用 → 序列化死循环
        vos.forEach(n -> n.setChildren(byParent.getOrDefault(n.getId(), List.of())));
        return byParent.getOrDefault(0L, List.of());
    }

    /**
     * 是否携带过滤条件
     */
    private boolean hasFilter(PermissionQueryDTO query) {
        return query != null
                && ((query.getPermissionName() != null && !query.getPermissionName().isBlank())
                || query.getPermissionType() != null
                || (query.getPermissionCode() != null && !query.getPermissionCode().isBlank()));
    }

    /**
     * 应用过滤并保留命中节点祖先链（过滤目录时其下子菜单一并隐藏，但命中的叶子其父级必须保留）
     */
    private List<UmsPermission> applyFilter(List<UmsPermission> all, PermissionQueryDTO query) {
        Map<Long, UmsPermission> byId = all.stream()
                .filter(p -> p.getId() != null)
                .collect(Collectors.toMap(UmsPermission::getId, p -> p, (a, b) -> a));
        Set<Long> keepIds = new HashSet<>();
        for (UmsPermission p : all) {
            if (!matches(query, p)) {
                continue;
            }
            keepIds.add(p.getId());
            Long pid = p.getParentId();
            while (pid != null && pid != 0L && keepIds.add(pid)) {
                UmsPermission parent = byId.get(pid);
                if (parent == null) {
                    break;
                }
                pid = parent.getParentId();
            }
        }
        return all.stream().filter(p -> p.getId() != null && keepIds.contains(p.getId())).toList();
    }

    /**
     * 过滤条件匹配（Java 侧比对枚举，无 DB 绑定歧义）
     */
    private boolean matches(PermissionQueryDTO query, UmsPermission p) {
        if (query.getPermissionName() != null && !query.getPermissionName().isBlank()
                && (p.getPermissionName() == null || !p.getPermissionName().contains(query.getPermissionName()))) {
            return false;
        }
        if (query.getPermissionType() != null && !query.getPermissionType().equals(p.getPermissionType())) {
            return false;
        }
        if (query.getPermissionCode() != null && !query.getPermissionCode().isBlank()
                && !query.getPermissionCode().equals(p.getPermissionCode())) {
            return false;
        }
        return true;
    }

    /**
     * 树节点父键（null 视为根）
     */
    private long parentKey(PermissionVO vo) {
        return vo.getParentId() == null ? 0L : vo.getParentId();
    }
}

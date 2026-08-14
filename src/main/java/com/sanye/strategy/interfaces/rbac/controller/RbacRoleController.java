package com.sanye.strategy.interfaces.rbac.controller;

import com.sanye.strategy.application.rbac.RbacManageService;
import com.sanye.strategy.application.rbac.SyncReport;
import com.sanye.strategy.common.base.DefaultQueryWrapper;
import com.sanye.strategy.common.base.IWrapper;
import com.sanye.strategy.common.exception.BizException;
import com.sanye.strategy.common.model.BasePageDTO;
import com.sanye.strategy.common.model.IBasePage;
import com.sanye.strategy.common.response.R;
import com.sanye.strategy.common.response.ResultCode;
import com.sanye.strategy.common.util.BeanCopyUtils;
import com.sanye.strategy.domain.enums.RoleStatusEnum;
import com.sanye.strategy.domain.rbac.repository.UmsRolePermissionService;
import com.sanye.strategy.domain.user.entity.UmsRole;
import com.sanye.strategy.domain.user.repository.UmsRoleService;
import com.sanye.strategy.infrastructure.security.RequiresPermission;
import com.sanye.strategy.interfaces.rbac.dto.RoleCloneDTO;
import com.sanye.strategy.interfaces.rbac.dto.RoleDTO;
import com.sanye.strategy.interfaces.rbac.dto.RoleQueryDTO;
import com.sanye.strategy.interfaces.rbac.dto.StatusDTO;
import com.sanye.strategy.interfaces.rbac.vo.RoleVO;
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

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <p>
 * 角色管理端点 — 类级权限码 {@code system:role:manage}
 * </p>
 * <p>
 * 覆盖 spec「管理 API 面」RbacRoleController 全部端点（分页/详情/新增/改/删/启停/克隆/导入导出）。
 * 功能权限模型为 JWT 快照（perms/roles 变更经 RbacManageService 自动踢人后重登生效，最长滞后
 * 30min accessToken TTL）；分页查询带管理面过滤（实时，{@code create_user_id is null 或 = 当前用户}，
 * 谁创建看谁 + 系统数据放行，仅限 RBAC 角色分页，禁止复用业务表）。数据权限模型（data_scope）为实时，
 * 两模型差异见 CLAUDE.md 备忘。
 * </p>
 * <p>
 * 排序字段白名单（{@code sortField}）：role_code/role_name/sort_order/create_time，
 * 防任意列名注入 ORDER BY。
 * </p>
 *
 * @author 31372
 */
@RestController
@RequestMapping("/rbac/roles")
@RequiredArgsConstructor
@RequiresPermission("system:role:manage")
@Tag(name = "rbac-role", description = "角色管理：分页/详情/新增/改/删/启停/克隆/导出/导入")
public class RbacRoleController {

    /** 排序字段白名单（防 SQL 注入：任意 sortField 不入 ORDER BY） */
    private static final Set<String> SORT_FIELDS = Set.of("role_code", "role_name", "sort_order", "create_time");

    private final RbacManageService rbacManageService;
    private final UmsRoleService roleService;
    private final UmsRolePermissionService rolePermissionService;

    /**
     * 角色分页（管理面过滤：非 SUPER_ADMIN 仅本人创建 + 系统数据）
     */
    @Operation(summary = "角色分页", description = "管理面过滤：非 SUPER_ADMIN 仅本人创建 + 系统数据")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/page")
    public R<IBasePage<RoleVO>> page(RoleQueryDTO query) {
        long current = Math.max(1, query.getPage() == null ? 1 : query.getPage());
        // 上限 100：防单页拉全表（超大量角色列表前端走滚动/筛选）；保留下限 ≥1
        long size = Math.min(100, Math.max(1, query.getSize() == null ? 10 : query.getSize()));
        IBasePage<UmsRole> result = roleService.page(BasePageDTO.of(current, size), buildQuery(query));
        return R.ok(result.convert(this::toVO));
    }

    /**
     * 角色详情（含当前生效权限码列表）
     */
    @Operation(summary = "角色详情", description = "含当前生效权限码列表")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/{id}")
    public R<RoleVO> detail(@PathVariable Long id) {
        UmsRole role = roleService.getById(id);
        if (role == null) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
        RoleVO vo = toVO(role);
        vo.setPermissionCodes(rolePermissionService.getPermissionCodesByRoleId(id));
        return R.ok(vo);
    }

    /**
     * 新增角色
     */
    @Operation(summary = "新增角色", description = "创建新角色")
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping
    public R<Void> create(@Valid @RequestBody RoleDTO dto) {
        rbacManageService.createRole(dto);
        return R.ok();
    }

    /**
     * 修改角色（内置角色禁改 role_code，门面守卫）
     */
    @Operation(summary = "修改角色", description = "内置角色禁改 role_code，门面守卫")
    @SecurityRequirement(name = "BearerAuth")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody RoleDTO dto) {
        rbacManageService.updateRole(id, dto);
        return R.ok();
    }

    /**
     * 删除角色（内置禁删、有关联用户禁删，门面守卫）
     */
    @Operation(summary = "删除角色", description = "内置禁删、有关联用户禁删")
    @SecurityRequirement(name = "BearerAuth")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        rbacManageService.deleteRole(id);
        return R.ok();
    }

    /**
     * 角色启停（变更自动踢该角色下用户重登同步新快照；SUPER_ADMIN 禁停用）
     */
    @Operation(summary = "角色启停", description = "变更自动踢该角色下用户重登同步新快照；SUPER_ADMIN 禁停用")
    @SecurityRequirement(name = "BearerAuth")
    @PutMapping("/{id}/status")
    public R<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody StatusDTO dto) {
        RoleStatusEnum status = RoleStatusEnum.valueOf(dto.getStatus());
        if (status == null) {
            throw new BizException(ResultCode.BAD_REQUEST, "非法角色状态码: " + dto.getStatus());
        }
        rbacManageService.updateRoleStatus(id, status);
        return R.ok();
    }

    /**
     * 克隆角色（角色 + 全套权限绑定；role_code = 源 + _COPY_ + 序号，超长截断）
     */
    @Operation(summary = "克隆角色", description = "复制角色 + 全套权限绑定；role_code = 源 + _COPY_ + 序号")
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping("/clone")
    public R<Long> clone(@Valid @RequestBody RoleCloneDTO dto) {
        return R.ok(rbacManageService.cloneRole(dto.getSourceRoleId()));
    }

    /**
     * 角色 JSON 导出（角色字段 + 权限码列表）
     */
    @Operation(summary = "角色导出", description = "JSON 导出角色字段 + 权限码列表")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/export")
    public R<List<Map<String, Object>>> export() {
        return R.ok(rbacManageService.exportRoles());
    }

    /**
     * 角色 JSON 导入（按 role_code 匹配：已存在跳过 / overwrite 覆盖；未注册权限码忽略告警）
     *
     * @param body      角色导入 JSON 数组（{@code List<RoleImportItem>}）
     * @param overwrite true-覆盖已存在角色的权限绑定
     * @return 导入报告（added/revived/ignored）
     */
    @Operation(summary = "角色导入", description = "按 role_code 匹配：已存在跳过 / overwrite 覆盖；未注册权限码忽略告警")
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping("/import")
    public R<SyncReport> importRoles(@RequestBody String body,
                                     @RequestParam(defaultValue = "false") boolean overwrite) {
        return R.ok(rbacManageService.importRoles(body, overwrite));
    }

    // ==================== 私有辅助 ====================

    /**
     * 角色 → VO（分页/详情共用；permissionCodes 由详情端点另行填充）
     */
    private RoleVO toVO(UmsRole role) {
        return BeanCopyUtils.copy(role, RoleVO.class);
    }

    /**
     * 组装分页查询条件：条件过滤 + 排序 + 管理面过滤
     */
    private IWrapper<UmsRole> buildQuery(RoleQueryDTO query) {
        DefaultQueryWrapper<UmsRole> wrapper = new DefaultQueryWrapper<>();
        wrapper.eq("role_code", query.getRoleCode());
        if (query.getRoleName() != null && !query.getRoleName().isBlank()) {
            wrapper.like("role_name", query.getRoleName());
        }
        RoleStatusEnum status = RoleStatusEnum.valueOf(query.getStatus());
        wrapper.eq("status", status);
        applySort(wrapper, query.getSortField(), query.getSortOrder());
        return rbacManageService.applyManageScope(wrapper);
    }

    /**
     * 排序装配（白名单校验；未指定排序字段默认 sort_order 升序）
     */
    private void applySort(DefaultQueryWrapper<UmsRole> wrapper, String sortField, String sortOrder) {
        if (sortField == null || sortField.isBlank()) {
            wrapper.orderByAsc("sort_order");
            return;
        }
        if (!SORT_FIELDS.contains(sortField)) {
            throw new BizException(ResultCode.BAD_REQUEST, "非法排序字段: " + sortField);
        }
        boolean asc = !"desc".equalsIgnoreCase(sortOrder);
        wrapper.orderBy(asc, sortField);
    }
}

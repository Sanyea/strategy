package com.sanye.strategy.interfaces.rbac.controller;

import com.sanye.strategy.application.rbac.RbacManageService;
import com.sanye.strategy.common.exception.BizException;
import com.sanye.strategy.common.model.BasePageDTO;
import com.sanye.strategy.common.model.IBasePage;
import com.sanye.strategy.common.response.R;
import com.sanye.strategy.common.response.ResultCode;
import com.sanye.strategy.domain.user.entity.UmsRole;
import com.sanye.strategy.domain.user.entity.UmsUserRole;
import com.sanye.strategy.domain.user.repository.UmsRoleService;
import com.sanye.strategy.domain.user.repository.UmsUserRoleService;
import com.sanye.strategy.infrastructure.security.RequiresPermission;
import com.sanye.strategy.interfaces.rbac.dto.UserRoleAssignDTO;
import com.sanye.strategy.interfaces.rbac.dto.UserRoleBatchAssignDTO;
import com.sanye.strategy.interfaces.rbac.dto.UserRoleExpiringQueryDTO;
import com.sanye.strategy.interfaces.rbac.dto.UserRoleRenewDTO;
import com.sanye.strategy.interfaces.rbac.dto.UserRoleRenewSingleDTO;
import com.sanye.strategy.interfaces.rbac.vo.UserRoleVO;
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

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 用户-角色绑定端点 — 类级权限码 {@code system:user:role:manage}
 * </p>
 * <p>
 * 覆盖 spec「管理 API 面」RbacUserRoleController 全部端点（覆盖/批量授/解绑/续期/到期查询/批量续期/列表）。
 * 功能权限模型为 JWT 快照：覆盖/批量授/解绑变更经门面自动踢受影响用户，重登同步新快照（最长滞后
 * 30min accessToken TTL）；续期（renew/renewBatch）权限不变化，无需踢人。数据权限模型（data_scope）
 * 为实时，两模型差异见 CLAUDE.md 备忘。
 * </p>
 * <p>
 * spec「写操作约定」：覆盖绑定须 begin &lt; end 校验——门面 {@code replaceUserRoles} 未做，
 * 本端点按 {@code UserRoleAssignDTO} 逐条校验（begin/end 均非空且 begin 不早于 end → 400）。
 * 到期/即将到期列表仅经契约 {@code UmsUserRoleService.listExpiring} 直接读取（纯查询，无门面方法）。
 * </p>
 *
 * @author 31372
 */
@RestController
@RequestMapping("/rbac")
@RequiredArgsConstructor
@RequiresPermission("system:user:role:manage")
@Tag(name = "rbac-user-role", description = "用户角色绑定：覆盖/批量授/解绑/续期/到期列表/批量续期/列表")
public class RbacUserRoleController {

    /** 到期预警默认时间窗（天） */
    private static final int DEFAULT_EXPIRY_DAYS = 7;

    private final RbacManageService rbacManageService;
    private final UmsUserRoleService userRoleService;
    private final UmsRoleService roleService;

    /**
     * 单用户覆盖多角色（解旧绑新；逐条 begin &lt; end 校验；变更自动踢该用户）
     */
    @Operation(summary = "覆盖用户角色", description = "单用户覆盖多角色（解旧绑新；逐条 begin < end 校验；变更自动踢该用户）")
    @SecurityRequirement(name = "BearerAuth")
    @PutMapping("/users/{id}/roles")
    public R<Void> replace(@PathVariable Long id, @Valid @RequestBody List<UserRoleAssignDTO> assigns) {
        if (assigns != null) {
            for (UserRoleAssignDTO a : assigns) {
                validateTimeWindow(a.getBeginTime(), a.getEndTime());
            }
        }
        rbacManageService.replaceUserRoles(id, assigns);
        return R.ok();
    }

    /**
     * 批量用户授同一角色（begin/end 由门面校验；变更自动踢受影响用户）
     */
    @Operation(summary = "批量授角色", description = "批量用户授同一角色（带 begin/end；变更自动踢受影响用户）")
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping("/users/roles")
    public R<Void> assignBatch(@Valid @RequestBody UserRoleBatchAssignDTO dto) {
        rbacManageService.assignRolesBatch(dto.getUserIds(), dto.getRoleId(), dto.getBeginTime(), dto.getEndTime());
        return R.ok();
    }

    /**
     * 解绑用户某角色（变更自动踢该用户）
     */
    @Operation(summary = "解绑用户角色", description = "解绑用户某角色（变更自动踢该用户）")
    @SecurityRequirement(name = "BearerAuth")
    @DeleteMapping("/users/{id}/roles/{roleId}")
    public R<Void> unbind(@PathVariable Long id, @PathVariable Long roleId) {
        rbacManageService.removeUserRole(id, roleId);
        return R.ok();
    }

    /**
     * 单角色续期（改 end_time，权限不变化无需踢人）
     */
    @Operation(summary = "单角色续期", description = "改 end_time，权限不变化无需踢人")
    @SecurityRequirement(name = "BearerAuth")
    @PutMapping("/users/{id}/roles/{roleId}/renew")
    public R<Void> renew(@PathVariable Long id, @PathVariable Long roleId,
                         @Valid @RequestBody UserRoleRenewSingleDTO dto) {
        rbacManageService.renewUserRole(id, roleId, dto.getEndTime());
        return R.ok();
    }

    /**
     * 即将/已过期绑定分页（end_time &lt;= now + days；days 默认 7）
     */
    @Operation(summary = "到期角色分页", description = "即将/已过期绑定分页（end_time <= now + days；days 默认 7）")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/user-roles/expiring")
    public R<IBasePage<UserRoleVO>> expiring(UserRoleExpiringQueryDTO query) {
        long current = Math.max(1, query.getPage() == null ? 1 : query.getPage());
        long size = Math.max(1, query.getSize() == null ? 10 : query.getSize());
        int days = query.getDays() == null ? DEFAULT_EXPIRY_DAYS : query.getDays();
        int limit = (int) Math.min(size, Integer.MAX_VALUE - 1L);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime horizon = now.plusDays(days);
        long offset = (current - 1L) * size;
        List<UmsUserRole> rows = userRoleService.listExpiring(now, horizon, offset, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<UserRoleVO> vos = (hasMore ? rows.subList(0, limit) : rows).stream()
                .map(this::toUserRoleVO).toList();
        BasePageDTO<UserRoleVO> page = BasePageDTO.of(current, size);
        page.setRecords(vos);
        page.setTotal(offset + vos.size() + (hasMore ? 1 : 0));   // 契约无 count，total 为下界估算
        return R.ok(page);
    }

    /**
     * 批量续期（按绑定行 ID；权限不变化无需踢人）
     */
    @Operation(summary = "批量续期", description = "按绑定行 ID 批量延长 end_time；权限不变化无需踢人")
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping("/user-roles/renew")
    public R<Integer> renewBatch(@Valid @RequestBody UserRoleRenewDTO dto) {
        return R.ok(rbacManageService.renewBatch(dto.getBindIds(), dto.getEndTime()));
    }

    /**
     * 用户角色列表（当前生效绑定，含角色编码/名称装配）
     */
    @Operation(summary = "用户角色列表", description = "当前生效绑定，含角色编码/名称装配")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/users/{id}/roles")
    public R<List<UserRoleVO>> userRoles(@PathVariable Long id) {
        List<UserRoleVO> vos = userRoleService.listEffectiveByUserId(id).stream()
                .map(this::toUserRoleVO).toList();
        return R.ok(vos);
    }

    // ==================== 私有辅助 ====================

    /**
     * 绑定行 → VO（装配角色编码/名称；角色缺失不阻断）
     */
    private UserRoleVO toUserRoleVO(UmsUserRole ur) {
        UserRoleVO vo = new UserRoleVO();
        vo.setId(ur.getId());
        vo.setUserId(ur.getUserId());
        vo.setRoleId(ur.getRoleId());
        vo.setBeginTime(ur.getBeginTime());
        vo.setEndTime(ur.getEndTime());
        vo.setAssignerId(ur.getAssignerId());
        vo.setCreateTime(ur.getCreateTime());
        if (ur.getRoleId() != null) {
            UmsRole role = roleService.getById(ur.getRoleId());
            if (role != null) {
                vo.setRoleCode(role.getRoleCode());
                vo.setRoleName(role.getRoleName());
            }
        }
        return vo;
    }

    /**
     * 生效时段校验（begin/end 均提供时须 begin &lt; end）
     *
     * @param begin 生效开始时间（可空）
     * @param end   生效结束时间（可空）
     */
    private void validateTimeWindow(LocalDateTime begin, LocalDateTime end) {
        if (begin != null && end != null && !begin.isBefore(end)) {
            throw new BizException(ResultCode.BAD_REQUEST, "角色生效 begin 必须早于 end");
        }
    }
}

package com.sanye.strategy.interfaces.rbac.controller;

import com.sanye.strategy.application.rbac.EvictTaskRegistry;
import com.sanye.strategy.application.rbac.RbacAuthzService;
import com.sanye.strategy.application.rbac.RbacManageService;
import com.sanye.strategy.common.exception.BizException;
import com.sanye.strategy.common.response.R;
import com.sanye.strategy.common.response.ResultCode;
import com.sanye.strategy.infrastructure.security.RequiresPermission;
import com.sanye.strategy.infrastructure.security.UserContext;
import com.sanye.strategy.interfaces.rbac.vo.EvictTaskVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * <p>
 * 调试/主动失效端点 — 类级权限码 {@code system:rbac:debug:manage} + 全局开关 {@code rbac.debug-enabled}
 * </p>
 * <p>
 * 不用 {@code @ConditionalOnProperty}：开关关闭须返回 403（非 404，防暴露接口存在，spec 硬约束）
 * ——类常装配，每端点经 {@link #ensureEnabled()} 校验，关闭时 403 + 访问日志。
 * 注意：自动 evict 属管理写链路（{@code RbacManageService} 内部触发），不受本开关控制，
 * 仅人工调试接口受约束。
 * </p>
 * <p>
 * 功能权限模型为 JWT 快照（{@code effective-permissions} 为实时联表排查，与快照互补）；
 * 数据权限模型（data_scope）为实时，两模型差异见 CLAUDE.md 备忘。
 * </p>
 *
 * @author 31372
 */
@RestController
@RequestMapping("/rbac")
@RequiresPermission("system:rbac:debug:manage")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "rbac-debug", description = "调试与主动失效（受 rbac.debug-enabled 开关控制）")
public class RbacDebugController {

    private final RbacManageService rbacManageService;
    private final RbacAuthzService rbacAuthzService;
    private final EvictTaskRegistry evictTaskRegistry;

    /**
     * 调试开关（生产 false；关闭时人工调试接口 403）
     */
    @Value("${rbac.debug-enabled:false}")
    private boolean debugEnabled;

    /**
     * 调试开关校验：关闭时抛 403 + 访问日志（防暴露接口存在，spec 硬约束）
     */
    private void ensureEnabled() {
        if (!debugEnabled) {
            UserContext ctx = UserContext.get();
            log.warn("RBAC 调试接口被访问但 debug-enabled=false, operator={}", ctx == null ? "?" : ctx.getUserId());
            throw new BizException(ResultCode.FORBIDDEN, "调试接口未启用");
        }
    }

    /**
     * 用户实际生效权限（合并多角色去重；过滤禁用/过期——实时联表）
     */
    @Operation(summary = "用户生效权限", description = "合并多角色去重、过滤禁用/过期（实时联表排查）")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/users/{id}/effective-permissions")
    public R<Set<String>> effective(@PathVariable Long id) {
        ensureEnabled();
        return R.ok(rbacAuthzService.effectivePermissions(id));
    }

    /**
     * 指定用户校验权限（排查）
     */
    @Operation(summary = "校验用户权限", description = "指定用户校验权限（排查）")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/users/{id}/check")
    public R<Boolean> check(@PathVariable Long id, @RequestParam String permission) {
        ensureEnabled();
        return R.ok(rbacAuthzService.checkPermission(id, permission));
    }

    /**
     * 踢单用户（写 jti 黑名单，人工兜底）
     */
    @Operation(summary = "踢单用户", description = "写 jti 黑名单，人工兜底")
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping("/users/{id}/evict")
    public R<Integer> evictUser(@PathVariable Long id) {
        ensureEnabled();
        return R.ok(rbacManageService.evictUser(id));
    }

    /**
     * 按角色批量踢（{@code ?mode=sync} 小量同步 / {@code ?mode=async} 大量返回 taskId 后台执行）
     */
    @Operation(summary = "批量踢用户", description = "按角色批量踢：?mode=sync 同步 / ?mode=async 异步返回 taskId 后台执行")
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping("/evict-batch")
    public R<EvictTaskVO> evictBatch(@RequestParam Long roleId, @RequestParam(defaultValue = "async") String mode) {
        ensureEnabled();
        return R.ok(rbacManageService.evictBatch(roleId, mode));
    }

    /**
     * 异步批量踢任务进度查询
     */
    @Operation(summary = "批量踢任务进度", description = "异步批量踢任务进度查询")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/evict/tasks/{taskId}")
    public R<EvictTaskVO> task(@PathVariable String taskId) {
        ensureEnabled();
        return R.ok(evictTaskRegistry.get(taskId));
    }
}

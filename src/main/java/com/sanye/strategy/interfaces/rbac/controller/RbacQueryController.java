package com.sanye.strategy.interfaces.rbac.controller;

import com.sanye.strategy.application.rbac.RbacAuthzService;
import com.sanye.strategy.common.base.DefaultQueryWrapper;
import com.sanye.strategy.common.response.R;
import com.sanye.strategy.common.util.BeanCopyUtils;
import com.sanye.strategy.domain.enums.PermissionTypeEnum;
import com.sanye.strategy.domain.enums.RoleStatusEnum;
import com.sanye.strategy.domain.enums.YesNoEnum;
import com.sanye.strategy.domain.rbac.entity.UmsPermission;
import com.sanye.strategy.domain.rbac.repository.UmsPermissionService;
import com.sanye.strategy.infrastructure.security.NoPermissionRequired;
import com.sanye.strategy.infrastructure.security.UserContext;
import com.sanye.strategy.interfaces.rbac.vo.PermissionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 当前用户权限查询 — 类标 {@code @NoPermissionRequired}（仅需登录，无独立权限码）
 * </p>
 * <p>
 * 供前端菜单/按钮渲染：数据来源 {@code UserContext.getPermCodes()}（JWT perms claim 快照，
 * 零 DB 查询），与 {@code RbacAuthzService.effectivePermissions}（实时联表，调试用）互为补充。
 * 功能权限模型为 JWT 快照（权限变更经管理写操作自动踢人后重登生效，最长滞后 30min accessToken TTL）；
 * 数据权限模型（data_scope）为实时，两模型差异见 CLAUDE.md 备忘。
 * </p>
 * <p>
 * 菜单树过滤：仅目录/菜单按 perms 快照过滤（按钮/接口不入菜单树；命中保留 + 祖先链 +
 * 公共节点恒显 + 空目录剪枝）——纯功能权限 JWT 快照，与数据权限无关。
 * </p>
 *
 * @author 31372
 */
@RestController
@RequestMapping("/rbac/my")
@NoPermissionRequired
@RequiredArgsConstructor
@Tag(name = "rbac-query", description = "当前用户权限与菜单树查询（仅需登录）")
public class RbacQueryController {

    private static final String SUPER_ADMIN = "SUPER_ADMIN";

    private final UmsPermissionService permissionService;

    /**
     * 我的权限码集（合并多角色去重后的 JWT 快照）
     * <p>SUPER_ADMIN 返回 {@code ["*"]} 通配，与 {@code RbacAuthzService.effectivePermissions}
     * 实时语义一致；旧 token（perms 空快照）经角色直通兜底同样返回通配，不泄露空集。</p>
     */
    @Operation(summary = "我的权限码集", description = "合并多角色去重后的 JWT 快照；SUPER_ADMIN 返回通配 [*]")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/permissions")
    public R<List<String>> myPermissions() {
        UserContext ctx = UserContext.get();
        if (ctx == null) {
            return R.ok(List.of());
        }
        if (ctx.getRoleCodes().contains(SUPER_ADMIN)) {
            return R.ok(List.of(RbacAuthzService.WILDCARD));
        }
        return R.ok(ctx.getPermCodes());
    }

    /**
     * 我的目录/菜单树（前端渲染；仅目录/菜单按 perms 快照过滤，按钮/接口不入树）
     * <p>过滤语义：命中权限码的节点保留，且保留其祖先链保证树完整；无权限码的目录/菜单视为
     * 公共节点恒显；SUPER_ADMIN 通配（{@code *}）全量可见。空目录剪枝（仅作为祖先保留、
     * 其下子节点全被过滤时移除空壳）。纯功能权限 JWT 快照，与数据权限无关。</p>
     */
    @Operation(summary = "我的菜单树", description = "目录/菜单树（前端渲染；仅目录/菜单按 perms 快照过滤，按钮/接口不入树）")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/menu-tree")
    public R<List<PermissionVO>> myMenuTree() {
        UserContext ctx = UserContext.get();
        Set<String> perms = ctx == null ? Set.of() : new HashSet<>(ctx.getPermCodes());
        // SUPER_ADMIN：角色直通（含旧 token 空快照）或 perms 通配 "*"，全量可见
        boolean superAdmin = ctx != null
                && (ctx.getRoleCodes().contains(SUPER_ADMIN) || perms.contains(RbacAuthzService.WILDCARD));
        List<UmsPermission> visible = permissionService.list(new DefaultQueryWrapper<UmsPermission>()
                .eq("status", RoleStatusEnum.NORMAL.getCode())
                .eq("is_visible", YesNoEnum.YES.getCode())
                .in("permission_type", PermissionTypeEnum.DIRECTORY.getCode(),
                        // 菜单树仅目录/菜单，按钮/接口不入
                        PermissionTypeEnum.MENU.getCode())
                .orderByAsc("sort_order"));
        // 命中节点保留祖先链（父级必须可见，树结构完整）；无权限码公共节点恒显
        Map<Long, UmsPermission> byId = visible.stream()
                .filter(p -> p.getId() != null)
                .collect(Collectors.toMap(UmsPermission::getId, p -> p, (a, b) -> a));
        Set<Long> keepIds = new HashSet<>();
        for (UmsPermission p : visible) {
            if (!isGranted(p, perms, superAdmin)) {
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
        List<PermissionVO> kept = visible.stream()
                .filter(p -> p.getId() != null && keepIds.contains(p.getId()))
                .map(this::toTreeVO)
                .toList();
        Map<Long, List<PermissionVO>> byParent = kept.stream()
                .collect(Collectors.groupingBy(this::parentKey));
        // 子节点键为节点自身 id（children = parentId 等于本节点 id 的节点）；
        // 误用 parentKey(n)（自身 parentId）会取到同级兄弟+自身 → children 自引用 → 序列化死循环；
        // 恒以可变列表承载，供空目录剪枝 removeIf
        kept.forEach(n -> n.setChildren(new ArrayList<>(byParent.getOrDefault(n.getId(), List.of()))));
        List<PermissionVO> roots = new ArrayList<>(byParent.getOrDefault(0L, List.of()));
        pruneEmptyDirectories(roots);
        return R.ok(roots);
    }

    /**
     * 权限节点是否对当前用户可见（按 perms 快照 + 公共节点语义）
     *
     * @param p          权限资源
     * @param perms      当前用户权限码集（JWT 快照）
     * @param superAdmin 是否 SUPER_ADMIN（通配）
     * @return 是否可见
     */
    private boolean isGranted(UmsPermission p, Set<String> perms, boolean superAdmin) {
        // 按钮/接口资源不入菜单树（动作项/API 端点非导航项），SUPER_ADMIN 同样排除——放最前，
        // 避免 SUPER_ADMIN 短路让按钮/接口平铺进树
        PermissionTypeEnum type = p.getPermissionType();
        if (type == PermissionTypeEnum.INTERFACE || type == PermissionTypeEnum.BUTTON) {
            return false;
        }
        if (superAdmin) {
            return true;
        }
        String code = p.getPermissionCode();
        if (code == null || code.isBlank()) {
            // 无权限码：目录/菜单视为公共节点恒显
            return true;
        }
        return perms.contains(code);
    }

    /**
     * 剪枝空目录：目录（{@code DIRECTORY}）无任何可见子节点时移除，防止「仅作为祖先保留、
     * 其下子节点全被过滤」产生空壳容器；菜单无子节点仍保留（自身可导航，按钮为动作项）
     */
    private void pruneEmptyDirectories(List<PermissionVO> nodes) {
        nodes.removeIf(n -> {
            pruneEmptyDirectories(n.getChildren());
            return n.getPermissionType() == PermissionTypeEnum.DIRECTORY && n.getChildren().isEmpty();
        });
    }

    /**
     * 权限资源 → 树节点 VO（children 初始化为空列表，避免序列化 null）
     */
    private PermissionVO toTreeVO(UmsPermission p) {
        PermissionVO vo = BeanCopyUtils.copy(p, PermissionVO.class);
        vo.setChildren(new ArrayList<>());
        return vo;
    }

    /**
     * 树节点父键（null 视为根，防 groupingBy 空键 NPE）
     */
    private long parentKey(PermissionVO vo) {
        return vo.getParentId() == null ? 0L : vo.getParentId();
    }
}

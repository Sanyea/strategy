package com.sanye.strategy.interfaces.rbac.controller;

import com.sanye.strategy.application.rbac.RbacAuthzService;
import com.sanye.strategy.application.rbac.RbacManageService;
import com.sanye.strategy.common.response.R;
import com.sanye.strategy.domain.enums.PermissionTypeEnum;
import com.sanye.strategy.domain.enums.RoleStatusEnum;
import com.sanye.strategy.domain.enums.YesNoEnum;
import com.sanye.strategy.domain.rbac.entity.UmsPermission;
import com.sanye.strategy.domain.rbac.repository.UmsPermissionService;
import com.sanye.strategy.infrastructure.security.UserContext;
import com.sanye.strategy.interfaces.rbac.dto.PermissionQueryDTO;
import com.sanye.strategy.interfaces.rbac.vo.PermissionVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <p>
 * 权限树构建 + 当前用户权限查询回归测试 — 覆盖 {@code GET /rbac/my/menu-tree}、
 * {@code GET /rbac/my/permissions}、{@code GET /rbac/permissions/tree}
 * </p>
 * <p>
 * 历史缺陷 1：children 挂载误用 {@code parentKey(n)}（节点自身 parentId）作为分组键，导致每个节点
 * 的 children 恒为「同级兄弟 + 自身」——根节点 children 自引用 → Jackson 序列化无限递归，
 * 触达 StreamWriteConstraints 嵌套深度上限（500）抛 {@code HttpMessageNotWritableException}。
 * 正确键应为节点自身 {@code id}（children = 所有 parentId 等于本节点 id 的节点）。
 * </p>
 * <p>
 * 历史缺陷 2：SUPER_ADMIN perms claim 签发为空 → {@code myPermissions} 返回空集、菜单树按钮全被
 * 过滤。修复：签发为 {@code "*"} 通配（与 {@code RbacAuthzService.effectivePermissions} 对齐），
 * 查询接口角色直通兜底（旧 token 空快照也返回通配/全显按钮）。
 * </p>
 *
 * @author 31372
 */
class RbacPermissionTreeTest {

    private static final Long USER_ID = 1L;

    private UmsPermissionService permissionService;
    private RbacQueryController queryController;
    private RbacPermissionController permissionController;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        permissionService = mock(UmsPermissionService.class);
        queryController = new RbacQueryController(permissionService);
        permissionController = new RbacPermissionController(mock(RbacManageService.class), permissionService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void superAdminMenuTreeShowsAllMenusWithoutRecursion() {
        // SUPER_ADMIN：perms 快照为 "*" 通配，目录/菜单全量可见（按钮/接口不入树）
        UserContext.set(new UserContext(USER_ID, List.of("SUPER_ADMIN"), List.of(RbacAuthzService.WILDCARD), 1L, "dev1"));
        when(permissionService.list(any())).thenReturn(seedData());

        R<List<PermissionVO>> r = queryController.myMenuTree();

        List<PermissionVO> roots = r.getData();
        assertEquals(4, roots.size(), "根节点应为 parentId=0 的目录/菜单");
        // 直接复现线上 500：节点 children 自引用 → 序列化递归，应不抛嵌套深度异常
        assertDoesNotThrow(() -> objectMapper.writeValueAsString(r));
        // 结构断言：dashboard 目录挂载其子菜单；rbac 目录挂载 4 子菜单；home 无子节点
        assertEquals(List.of(3L), childIds(findById(roots, 2L)), "目录 dashboard 应挂载其子菜单");
        assertEquals(List.of(6L, 7L, 8L, 9L), childIds(findById(roots, 5L)), "目录 rbac 应挂载 4 个子菜单");
        assertEquals(0, findById(roots, 1L).getChildren().size(), "home 无子节点");
        // 按钮不入菜单树：roles 菜单无按钮子节点
        assertEquals(0, findById(findById(roots, 5L).getChildren(), 6L).getChildren().size(),
                "按钮不入菜单树");
    }

    @Test
    void normalUserMenuTreeFiltersByPerms() {
        // 普通用户：持有 rbac 目录 + 全部 rbac 子菜单权限码 →
        // 只返回命中节点及祖先链，dashboard/home/auth 全部过滤
        UserContext.set(new UserContext(USER_ID, List.of("OPERATOR"),
                List.of("rbac", "rbac:roles", "rbac:permissions", "rbac:role-permissions",
                        "rbac:user-roles", "rbac:roles:add"), 1L, "dev1"));
        when(permissionService.list(any())).thenReturn(seedData());

        R<List<PermissionVO>> r = queryController.myMenuTree();

        assertDoesNotThrow(() -> objectMapper.writeValueAsString(r));
        List<PermissionVO> roots = r.getData();
        assertEquals(List.of(5L), childIdsFlat(roots), "仅命中权限码的根节点应保留（其余目录/菜单过滤）");
        assertEquals(List.of(6L, 7L, 8L, 9L), childIds(findById(roots, 5L)),
                "目录 rbac 下挂载其命中权限码的子菜单");
        // 按钮不入菜单树：即使持有 rbac:roles:add 权限码也不返回按钮节点
        assertEquals(0, findById(findById(roots, 5L).getChildren(), 6L).getChildren().size(),
                "按钮不入菜单树，即使权限码命中");
    }

    @Test
    void menuTreeExcludesInterfacesForSuperAdmin() {
        // 接口资源（API 端点）不入菜单树——SUPER_ADMIN 通配短路不得让其平铺进树
        UserContext.set(new UserContext(USER_ID, List.of("SUPER_ADMIN"),
                List.of(RbacAuthzService.WILDCARD), 1L, "dev1"));
        when(permissionService.list(any())).thenReturn(List.of(
                perm(1L, 0L, "rbac", PermissionTypeEnum.DIRECTORY),
                perm(2L, 1L, "rbac:roles", PermissionTypeEnum.MENU),
                perm(3L, 2L, "GET:/api/rbac/roles", PermissionTypeEnum.INTERFACE),
                perm(4L, 0L, "system:permission:manage", PermissionTypeEnum.INTERFACE)));

        R<List<PermissionVO>> r = queryController.myMenuTree();

        assertEquals(List.of(1L), childIdsFlat(r.getData()), "仅 DIRECTORY/MENU 结构，接口节点排除");
        assertEquals(List.of(2L), childIds(findById(r.getData(), 1L)), "目录挂载子菜单");
        assertEquals(0, findById(findById(r.getData(), 1L).getChildren(), 2L).getChildren().size(),
                "接口子节点不挂载到菜单下");
        assertDoesNotThrow(() -> objectMapper.writeValueAsString(r));
    }

    @Test
    void normalUserWithoutPermsSeesOnlyPublicMenus() {
        // 普通用户无任何权限码：仅无码公共菜单可见；接口资源不入菜单树
        UserContext.set(new UserContext(USER_ID, List.of("OPERATOR"), List.of(), 1L, "dev1"));
        when(permissionService.list(any())).thenReturn(List.of(
                perm(1L, 0L, null, PermissionTypeEnum.MENU),
                perm(2L, 0L, "protected", PermissionTypeEnum.MENU),
                perm(3L, 0L, "GET:/api/internal", PermissionTypeEnum.INTERFACE)));

        R<List<PermissionVO>> r = queryController.myMenuTree();

        assertEquals(List.of(1L), childIdsFlat(r.getData()), "无码公共菜单恒显，受保护菜单与接口过滤");
    }

    @Test
    void myPermissionsSuperAdminReturnsWildcard() {
        // SUPER_ADMIN：返回 ["*"]，与实时 effectivePermissions 语义一致（空快照也经角色直通兜底）
        UserContext.set(new UserContext(USER_ID, List.of("SUPER_ADMIN"), List.of(), 1L, "dev1"));

        R<List<String>> r = queryController.myPermissions();

        assertEquals(List.of(RbacAuthzService.WILDCARD), r.getData(), "SUPER_ADMIN 应返回通配 [*]");
    }

    @Test
    void myPermissionsNormalUserReturnsClaimSnapshot() {
        UserContext.set(new UserContext(USER_ID, List.of("OPERATOR"), List.of("rbac:roles:view"), 1L, "dev1"));

        R<List<String>> r = queryController.myPermissions();

        assertEquals(List.of("rbac:roles:view"), r.getData(), "普通用户应返回 perms claim 快照");
    }

    @Test
    void treeEndpointBuildsCorrectHierarchyWithoutSelfReference() {
        when(permissionService.list(any())).thenReturn(seedData());

        R<List<PermissionVO>> r = permissionController.tree(new PermissionQueryDTO());

        List<PermissionVO> roots = r.getData();
        assertEquals(4, roots.size(), "管理树根节点应为 parentId=0");
        assertDoesNotThrow(() -> objectMapper.writeValueAsString(r));
        // 管理树不过滤按钮：roles 菜单应挂载其按钮 rbac:roles:add
        assertEquals(List.of(10L), childIds(findById(findById(roots, 5L).getChildren(), 6L)),
                "管理树不过滤按钮，roles 菜单应挂载其按钮");
    }

    private List<UmsPermission> seedData() {
        return List.of(
                perm(1L, 0L, "home", PermissionTypeEnum.MENU),
                perm(2L, 0L, "dashboard", PermissionTypeEnum.DIRECTORY),
                perm(3L, 2L, "dashboard:index", PermissionTypeEnum.MENU),
                perm(4L, 0L, "auth", PermissionTypeEnum.MENU),
                perm(5L, 0L, "rbac", PermissionTypeEnum.DIRECTORY),
                perm(6L, 5L, "rbac:roles", PermissionTypeEnum.MENU),
                perm(7L, 5L, "rbac:permissions", PermissionTypeEnum.MENU),
                perm(8L, 5L, "rbac:role-permissions", PermissionTypeEnum.MENU),
                perm(9L, 5L, "rbac:user-roles", PermissionTypeEnum.MENU),
                perm(10L, 6L, "rbac:roles:add", PermissionTypeEnum.BUTTON));
    }

    private static UmsPermission perm(Long id, Long parentId, String code, PermissionTypeEnum type) {
        UmsPermission p = new UmsPermission();
        p.setId(id);
        p.setParentId(parentId);
        p.setPermissionCode(code);
        p.setPermissionType(type);
        p.setStatus(RoleStatusEnum.NORMAL);
        p.setIsVisible(YesNoEnum.YES);
        return p;
    }

    private static PermissionVO findById(List<PermissionVO> nodes, Long id) {
        return nodes.stream().filter(n -> id.equals(n.getId())).findFirst().orElseThrow();
    }

    private static List<Long> childIds(PermissionVO node) {
        return node.getChildren().stream().map(PermissionVO::getId).toList();
    }

    private static List<Long> childIdsFlat(List<PermissionVO> nodes) {
        return nodes.stream().map(PermissionVO::getId).toList();
    }
}

package com.sanye.strategy.domain.rbac.repository;

import java.util.List;

/**
 * <p>
 * 角色-权限关联数据访问契约 — 自定义契约（不继承 {@code IService}）
 * </p>
 * <p>
 * 例外说明：{@code ums_role_permission} 为物理删除关系表，无 {@code deleted} 列，无法继承
 * {@code SimpleBaseEntity}/{@code SimpleBasePO}，故不套用 {@code MpBaseServiceImpl}
 * （详见 CLAUDE.md「物理删除关系表」约定）。直连 Mapper，暴露授权/回收/查码操作，
 * Task 5/7/11/12 依赖本契约（JWT perms claim、权限扫描同步、门面与 Controller）。
 * </p>
 *
 * @author 31372
 */
public interface UmsRolePermissionService {

    /**
     * 角色当前生效权限码（过滤停用/逻辑删除/空码）
     *
     * @param roleId 角色ID
     * @return 权限码列表，无生效权限返回空列表
     */
    List<String> getPermissionCodesByRoleId(Long roleId);

    /**
     * 角色当前权限ID集（克隆/导入/覆盖对比）
     *
     * @param roleId 角色ID
     * @return 权限ID列表，无绑定返回空列表
     */
    List<Long> getPermissionIdsByRoleId(Long roleId);

    /**
     * 权限被哪些角色绑定（停用/删除前反查 evict）
     *
     * @param permissionId 权限ID
     * @return 角色ID列表（去重），无绑定返回空列表
     */
    List<Long> getRoleIdsByPermissionId(Long permissionId);

    /**
     * 单条授权
     *
     * @param roleId      角色ID
     * @param permissionId 权限ID
     * @param grantUserId  授权人ID（可为 null）
     */
    void grant(Long roleId, Long permissionId, Long grantUserId);

    /**
     * 批量授权（INSERT IGNORE，uk_role_permission 防重）
     *
     * @param roleId       角色ID
     * @param permissionIds 权限ID列表
     * @param grantUserId   授权人ID（可为 null）
     */
    void grantBatch(Long roleId, List<Long> permissionIds, Long grantUserId);

    /**
     * 单条回收
     *
     * @param roleId       角色ID
     * @param permissionId 权限ID
     */
    void revoke(Long roleId, Long permissionId);

    /**
     * 清空角色全部权限
     *
     * @param roleId 角色ID
     */
    void revokeByRoleId(Long roleId);

    /**
     * 权限被角色引用数（停用/删除前校验）
     *
     * @param permissionId 权限ID
     * @return 引用该权限的角色数
     */
    int countByPermissionId(Long permissionId);
}

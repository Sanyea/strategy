package com.sanye.strategy.domain.user.repository;

import com.sanye.strategy.domain.user.entity.UmsUserRole;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 用户-角色关联数据访问契约 — 自定义契约（不继承 {@code IService}）
 * </p>
 * <p>
 * 例外说明：{@code ums_user_role} 为物理删除关系表，无 {@code deleted} 列，无法继承
 * {@code SimpleBaseEntity}/{@code SimpleBasePO}，故不套用 {@code MpBaseServiceImpl}
 * （详见 CLAUDE.md「物理删除关系表」约定）。认证链读写（生效角色码/授权）与 RBAC 管理
 * （覆盖绑定/解绑/续期/过期查询/角色在线用户）均在此追加。
 * </p>
 *
 * @author 31372
 */
public interface UmsUserRoleService {

    /**
     * 查询用户当前生效角色码（过滤停用/逻辑删除/时间窗外）
     *
     * @param userId 用户ID
     * @return 角色码列表，无生效角色返回空列表
     */
    List<String> getRoleCodesByUserId(Long userId);

    /**
     * 为用户绑定角色
     *
     * @param userId     用户ID
     * @param roleId     角色ID
     * @param assignerId 授权人ID（系统/管理员，可为 null）
     */
    void assignRole(Long userId, Long roleId, Long assignerId);

    /**
     * 为用户绑定角色（指定生效时间窗）
     *
     * @param userId     用户ID
     * @param roleId     角色ID
     * @param assignerId 授权人ID（系统/管理员，可为 null）
     * @param beginTime  生效开始时间（可为 null）
     * @param endTime    生效结束时间（可为 null）
     */
    void assignRole(Long userId, Long roleId, Long assignerId, LocalDateTime beginTime, LocalDateTime endTime);

    /**
     * 用户当前生效角色绑定行（时间窗内，联表过滤停用/逻辑删除角色）
     *
     * @param userId 用户ID
     * @return 生效绑定行列表
     */
    List<UmsUserRole> listEffectiveByUserId(Long userId);

    /**
     * 覆盖绑定：清空用户现有角色 → 批量写入新角色集（begin/end 可为 null）。
     * <p>由调用方门面事务编排（{@code RbacManageService} TransactionTemplate），本服务不管理事务。</p>
     *
     * @param userId     用户ID
     * @param newRoles   新角色绑定集（仅需 roleId/beginTime/endTime，userId 统一取参）
     * @param assignerId 授权人ID（可为 null）
     */
    void replaceRoles(Long userId, List<UmsUserRole> newRoles, Long assignerId);

    /**
     * 解绑用户某角色（物理删除）
     *
     * @param userId 用户ID
     * @param roleId 角色ID
     * @return 是否命中并删除
     */
    boolean removeUserRole(Long userId, Long roleId);

    /**
     * 续期：更新某绑定的 end_time，返回是否命中
     *
     * @param userId  用户ID
     * @param roleId  角色ID
     * @param endTime 新的结束时间
     * @return 是否命中绑定行
     */
    boolean renew(Long userId, Long roleId, LocalDateTime endTime);

    /**
     * 已过期绑定（end_time 非 null 且 &lt;= now），offset 分页（定时任务全量遍历防饿死）
     *
     * @param now    当前时间
     * @param offset 偏移
     * @param limit  每页行数
     * @return 已过期绑定行列表
     */
    List<UmsUserRole> listExpired(LocalDateTime now, long offset, int limit);

    /**
     * 即将/已过期绑定（end_time 非 null 且 &lt;= horizon），offset 分页（管理页 expiring 端点）
     *
     * @param now     当前时间
     * @param horizon 到期预警时间窗上界
     * @param offset  偏移
     * @param limit   每页行数
     * @return 即将/已过期绑定行列表
     */
    List<UmsUserRole> listExpiring(LocalDateTime now, LocalDateTime horizon, long offset, int limit);

    /**
     * 角色下活动会话用户ID（联 ums_user_login_device is_current=1，供 evict），offset 分页
     *
     * @param roleId 角色ID
     * @param offset 偏移
     * @param limit  每页行数
     * @return 去重用户ID列表
     */
    List<Long> listActiveUserIdsByRoleId(Long roleId, long offset, int limit);

    /**
     * 角色下用户数
     *
     * @param roleId 角色ID
     * @return 绑定该角色的用户数
     */
    long countUserIdsByRoleId(Long roleId);

    /**
     * 绑定行 ID 查绑定（批量续期反查 userId 用）
     *
     * @param bindId 绑定行ID
     * @return 绑定行，不存在返回 null
     */
    UmsUserRole getById(Long bindId);

    /**
     * 按用户+角色查绑定行（单角色续期 diff 前置：取旧 end_time 与绑定行主键做 target）
     *
     * @param userId 用户ID
     * @param roleId 角色ID
     * @return 绑定行，不存在返回 null
     */
    UmsUserRole findByUserIdAndRoleId(Long userId, Long roleId);

    /**
     * 按绑定行 ID 续期（批量续期接口用，返回是否命中）
     *
     * @param bindId  绑定行ID
     * @param endTime 新的结束时间
     * @return 是否命中绑定行
     */
    boolean renewById(Long bindId, LocalDateTime endTime);
}

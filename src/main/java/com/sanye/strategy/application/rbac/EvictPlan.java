package com.sanye.strategy.application.rbac;

import com.sanye.strategy.domain.user.repository.UmsUserRoleService;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * <p>
 * 自动 evict 计划 — 受影响用户集内聚
 * </p>
 * <p>
 * RBAC 写操作提交成功后按本计划触发踢人：角色维度（{@code roleId}）优先，走
 * {@code EvictService.evictRoleUsers} 全量 offset 分页；用户维度（{@code userIds}）走
 * {@code EvictService.evictUsers} 直接按用户集踢。触发侧不再走 collectUserIds
 * （会截断到前 500）——角色维度统一全量分页。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：自动 evict 的输入载体，携带触发来源（审计）与异步 taskId（可选）。</li>
 *   <li>优缺点：角色/用户双维度统一表述、阈值分流（小量同步/大批异步）集中决策；
 *       代价为角色维度需查库估算（count）后再决定同步/异步。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Data
@Builder
public class EvictPlan {

    /**
     * 角色维度优先
     */
    private Long roleId;

    /**
     * 用户维度
     */
    private List<Long> userIds;

    /**
     * 触发来源（审计）
     */
    private String sourceDesc;

    /**
     * 异步时有值（同步为 null）
     */
    private String evictTaskId;

    /**
     * 估算受影响用户规模（阈值分流同步/异步用）
     *
     * @param userRoleService 用户-角色数据访问契约
     * @return 角色维度=绑定用户数，用户维度=用户数，均缺失为 0
     */
    public long estimateTotal(UmsUserRoleService userRoleService) {
        return roleId != null ? userRoleService.countUserIdsByRoleId(roleId) : (userIds == null ? 0 : userIds.size());
    }
}

package com.sanye.strategy.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanye.strategy.infrastructure.persistence.po.UmsUserRolePO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 用户-角色关联 Mapper — 操作 {@link UmsUserRolePO}
 * </p>
 * <p>
 * 联表角色码/生效绑定/过期绑定/在线用户查询 + 覆盖绑定 + 续期见 XML
 * {@code src/main/resources/mapper/UmsUserRoleMapper.xml}。
 * </p>
 *
 * @author 31372
 */
public interface UmsUserRoleMapper extends BaseMapper<UmsUserRolePO> {

    /**
     * 查询用户当前生效角色码（联表 ums_role，过滤停用/逻辑删除/时间窗外）
     *
     * @param userId 用户ID
     * @return 角色码列表
     */
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);

    /**
     * 用户当前生效角色绑定行（联表 ums_role，过滤停用/逻辑删除角色，时间窗内）
     *
     * @param userId 用户ID
     * @return 生效绑定行列表
     */
    List<UmsUserRolePO> selectEffectiveByUserId(@Param("userId") Long userId);

    /**
     * 已过期绑定（end_time 非 null 且 &lt;= now），offset 分页
     *
     * @param now    当前时间
     * @param offset 偏移
     * @param limit  每页行数
     * @return 过期绑定行列表，按 end_time 升序
     */
    List<UmsUserRolePO> selectExpired(@Param("now") LocalDateTime now,
                                      @Param("offset") long offset,
                                      @Param("limit") int limit);

    /**
     * 即将/已过期绑定（end_time 非 null 且 &lt;= horizon），offset 分页
     *
     * @param now     当前时间（契约签名保留，SQL 当前未使用）
     * @param horizon 到期预警时间窗上界
     * @param offset  偏移
     * @param limit   每页行数
     * @return 即将/已过期绑定行列表，按 end_time 升序
     */
    List<UmsUserRolePO> selectExpiring(@Param("now") LocalDateTime now,
                                       @Param("horizon") LocalDateTime horizon,
                                       @Param("offset") long offset,
                                       @Param("limit") int limit);

    /**
     * 角色下活动会话用户ID（联 ums_user_login_device is_current=1 且未删除），offset 分页
     *
     * @param roleId 角色ID
     * @param offset 偏移
     * @param limit  每页行数
     * @return 去重用户ID列表
     */
    List<Long> selectActiveUserIdsByRoleId(@Param("roleId") Long roleId,
                                           @Param("offset") long offset,
                                           @Param("limit") int limit);

    /**
     * 角色下用户数（去重）
     *
     * @param roleId 角色ID
     * @return 绑定该角色的用户数
     */
    Long countUserIdsByRoleId(@Param("roleId") Long roleId);

    /**
     * 按绑定行 ID 续期（end_time + update_time）
     *
     * @param bindId  绑定行ID
     * @param endTime 新的结束时间
     * @return 受影响行数
     */
    int updateEndTimeById(@Param("bindId") Long bindId, @Param("endTime") LocalDateTime endTime);

    /**
     * 按用户+角色续期（end_time + update_time）
     *
     * @param userId  用户ID
     * @param roleId  角色ID
     * @param endTime 新的结束时间
     * @return 受影响行数
     */
    int updateEndTimeByUserRole(@Param("userId") Long userId,
                                @Param("roleId") Long roleId,
                                @Param("endTime") LocalDateTime endTime);

    /**
     * 按用户清空全部角色绑定（覆盖绑定第一步，物理删除）
     *
     * @param userId 用户ID
     * @return 删除行数
     */
    int deleteByUserId(@Param("userId") Long userId);

    /**
     * 按用户+角色解绑（物理删除）
     *
     * @param userId 用户ID
     * @param roleId 角色ID
     * @return 受影响行数
     */
    int deleteByUserAndRole(@Param("userId") Long userId, @Param("roleId") Long roleId);

    /**
     * 批量绑定（INSERT IGNORE，uk_user_role 防重）
     *
     * @param list 绑定行列表（id 由调用方预置雪花主键，userId/roleId/beginTime/endTime/assignerId 按行）
     * @return 实际插入行数
     */
    int insertIgnoreBatch(@Param("list") List<UmsUserRolePO> list);
}

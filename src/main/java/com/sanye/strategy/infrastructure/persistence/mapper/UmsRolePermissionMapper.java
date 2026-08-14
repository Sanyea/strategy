package com.sanye.strategy.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanye.strategy.infrastructure.persistence.po.UmsRolePermissionPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 角色-权限关联 Mapper — 操作 {@link UmsRolePermissionPO}
 * </p>
 * <p>
 * 联表权限码查询 + 批量授权 + 关联 ID 查询见 XML
 * {@code src/main/resources/mapper/UmsRolePermissionMapper.xml}。
 * </p>
 *
 * @author 31372
 */
public interface UmsRolePermissionMapper extends BaseMapper<UmsRolePermissionPO> {

    /**
     * 角色当前权限码列表（联表 ums_permission，过滤停用/逻辑删除/空码）
     *
     * @param roleId 角色ID
     * @return 权限码列表，按 sort_order 升序
     */
    List<String> selectPermissionCodesByRoleId(@Param("roleId") Long roleId);

    /**
     * 角色当前权限ID集（克隆/导入/覆盖对比）
     *
     * @param roleId 角色ID
     * @return 权限ID列表
     */
    List<Long> selectPermissionIdsByRoleId(@Param("roleId") Long roleId);

    /**
     * 权限被哪些角色绑定（停用/删除前反查 evict）
     *
     * @param permissionId 权限ID
     * @return 角色ID列表（去重）
     */
    List<Long> selectRoleIdsByPermissionId(@Param("permissionId") Long permissionId);

    /**
     * 批量授权（INSERT IGNORE，uk_role_permission 防重）
     *
     * @param list 授权行列表（id 由 MP 雪花填充，roleId/permissionId/grantUserId 必填）
     * @return 实际插入行数
     */
    int insertIgnoreBatch(@Param("list") List<UmsRolePermissionPO> list);
}

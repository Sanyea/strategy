package com.sanye.strategy.infrastructure.persistence.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanye.strategy.domain.rbac.repository.UmsRolePermissionService;
import com.sanye.strategy.infrastructure.persistence.mapper.UmsRolePermissionMapper;
import com.sanye.strategy.infrastructure.persistence.po.UmsRolePermissionPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <p>
 * 角色-权限关联 Service 实现 — 直连 Mapper（自定义契约，见 {@link UmsRolePermissionService} 例外说明）
 * </p>
 * <p>
 * 查角色权限码走 XML 联表（过滤停用/逻辑删除），批量授权走 XML {@code INSERT IGNORE}，
 * 回收经 {@code BaseMapper.delete}（Wrappers 物理删除）。
 * </p>
 *
 * @author 31372
 */
@Service
@RequiredArgsConstructor
public class UmsRolePermissionServiceImpl implements UmsRolePermissionService {

    private final UmsRolePermissionMapper rolePermissionMapper;

    @Override
    public List<String> getPermissionCodesByRoleId(Long roleId) {
        List<String> codes = rolePermissionMapper.selectPermissionCodesByRoleId(roleId);
        return codes == null ? Collections.emptyList() : codes;
    }

    @Override
    public List<Long> getPermissionIdsByRoleId(Long roleId) {
        List<Long> ids = rolePermissionMapper.selectPermissionIdsByRoleId(roleId);
        return ids == null ? Collections.emptyList() : ids;
    }

    @Override
    public List<Long> getRoleIdsByPermissionId(Long permissionId) {
        List<Long> ids = rolePermissionMapper.selectRoleIdsByPermissionId(permissionId);
        return ids == null ? Collections.emptyList() : ids;
    }

    @Override
    public void grant(Long roleId, Long permissionId, Long grantUserId) {
        UmsRolePermissionPO po = new UmsRolePermissionPO();
        po.setRoleId(roleId);
        po.setPermissionId(permissionId);
        po.setGrantUserId(grantUserId);
        rolePermissionMapper.insert(po);
    }

    @Override
    public void grantBatch(Long roleId, List<Long> permissionIds, Long grantUserId) {
        if (permissionIds == null || permissionIds.isEmpty()) {
            return;
        }
        List<UmsRolePermissionPO> list = new ArrayList<>(permissionIds.size());
        for (Long permissionId : permissionIds) {
            UmsRolePermissionPO po = new UmsRolePermissionPO();
            // 自定义 XML insert 不触发 MP ASSIGN_ID 自动生成，需预置雪花主键（id 列 NOT NULL）
            po.setId(IdWorker.getId());
            po.setRoleId(roleId);
            po.setPermissionId(permissionId);
            po.setGrantUserId(grantUserId);
            list.add(po);
        }
        rolePermissionMapper.insertIgnoreBatch(list);
    }

    @Override
    public void revoke(Long roleId, Long permissionId) {
        rolePermissionMapper.delete(Wrappers.<UmsRolePermissionPO>lambdaQuery()
                .eq(UmsRolePermissionPO::getRoleId, roleId)
                .eq(UmsRolePermissionPO::getPermissionId, permissionId));
    }

    @Override
    public void revokeByRoleId(Long roleId) {
        rolePermissionMapper.delete(Wrappers.<UmsRolePermissionPO>lambdaQuery()
                .eq(UmsRolePermissionPO::getRoleId, roleId));
    }

    @Override
    public int countByPermissionId(Long permissionId) {
        Long count = rolePermissionMapper.selectCount(Wrappers.<UmsRolePermissionPO>lambdaQuery()
                .eq(UmsRolePermissionPO::getPermissionId, permissionId));
        return count == null ? 0 : count.intValue();
    }
}

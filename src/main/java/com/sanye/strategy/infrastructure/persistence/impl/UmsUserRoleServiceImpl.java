package com.sanye.strategy.infrastructure.persistence.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.sanye.strategy.common.util.BeanCopyUtils;
import com.sanye.strategy.domain.user.entity.UmsUserRole;
import com.sanye.strategy.domain.user.repository.UmsUserRoleService;
import com.sanye.strategy.infrastructure.persistence.mapper.UmsUserRoleMapper;
import com.sanye.strategy.infrastructure.persistence.po.UmsUserRolePO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <p>
 * 用户-角色关联 Service 实现 — 直连 Mapper（自定义契约，见 {@link UmsUserRoleService} 例外说明）
 * </p>
 * <p>
 * 查生效角色码/生效绑定/过期绑定/在线用户走 XML 联表，覆盖绑定走 XML
 * {@code deleteByUserId} + {@code insertIgnoreBatch}，续期/解绑走 XML UPDATE/DELETE。
 * {@code replaceRoles} 在调用方门面事务内执行（{@code RbacManageService} TransactionTemplate），
 * 本服务不自行管理事务。
 * </p>
 *
 * @author 31372
 */
@Service
@RequiredArgsConstructor
public class UmsUserRoleServiceImpl implements UmsUserRoleService {

    private final UmsUserRoleMapper userRoleMapper;

    @Override
    public List<String> getRoleCodesByUserId(Long userId) {
        List<String> codes = userRoleMapper.selectRoleCodesByUserId(userId);
        return codes == null ? Collections.emptyList() : codes;
    }

    @Override
    public void assignRole(Long userId, Long roleId, Long assignerId) {
        assignRole(userId, roleId, assignerId, null, null);
    }

    @Override
    public void assignRole(Long userId, Long roleId, Long assignerId, LocalDateTime beginTime, LocalDateTime endTime) {
        UmsUserRolePO po = new UmsUserRolePO();
        po.setUserId(userId);
        po.setRoleId(roleId);
        po.setAssignerId(assignerId);
        po.setBeginTime(beginTime);
        po.setEndTime(endTime);
        userRoleMapper.insert(po);
    }

    @Override
    public List<UmsUserRole> listEffectiveByUserId(Long userId) {
        return BeanCopyUtils.copyList(userRoleMapper.selectEffectiveByUserId(userId), UmsUserRole.class);
    }

    @Override
    public void replaceRoles(Long userId, List<UmsUserRole> newRoles, Long assignerId) {
        userRoleMapper.deleteByUserId(userId);
        if (newRoles == null || newRoles.isEmpty()) {
            return;
        }
        List<UmsUserRolePO> list = new ArrayList<>(newRoles.size());
        for (UmsUserRole role : newRoles) {
            UmsUserRolePO po = new UmsUserRolePO();
            // 自定义 XML insert 不触发 MP ASSIGN_ID 自动生成，需预置雪花主键（id 列 NOT NULL）
            po.setId(IdWorker.getId());
            po.setUserId(userId);
            po.setRoleId(role.getRoleId());
            po.setBeginTime(role.getBeginTime());
            po.setEndTime(role.getEndTime());
            po.setAssignerId(assignerId);
            list.add(po);
        }
        userRoleMapper.insertIgnoreBatch(list);
    }

    @Override
    public boolean removeUserRole(Long userId, Long roleId) {
        return userRoleMapper.deleteByUserAndRole(userId, roleId) > 0;
    }

    @Override
    public boolean renew(Long userId, Long roleId, LocalDateTime endTime) {
        return userRoleMapper.updateEndTimeByUserRole(userId, roleId, endTime) > 0;
    }

    @Override
    public List<UmsUserRole> listExpired(LocalDateTime now, long offset, int limit) {
        return BeanCopyUtils.copyList(userRoleMapper.selectExpired(now, offset, limit), UmsUserRole.class);
    }

    @Override
    public List<UmsUserRole> listExpiring(LocalDateTime now, LocalDateTime horizon, long offset, int limit) {
        return BeanCopyUtils.copyList(userRoleMapper.selectExpiring(now, horizon, offset, limit), UmsUserRole.class);
    }

    @Override
    public List<Long> listActiveUserIdsByRoleId(Long roleId, long offset, int limit) {
        List<Long> ids = userRoleMapper.selectActiveUserIdsByRoleId(roleId, offset, limit);
        return ids == null ? Collections.emptyList() : ids;
    }

    @Override
    public long countUserIdsByRoleId(Long roleId) {
        Long count = userRoleMapper.countUserIdsByRoleId(roleId);
        return count == null ? 0L : count;
    }

    @Override
    public UmsUserRole getById(Long bindId) {
        return BeanCopyUtils.copy(userRoleMapper.selectById(bindId), UmsUserRole.class);
    }

    @Override
    public UmsUserRole findByUserIdAndRoleId(Long userId, Long roleId) {
        return BeanCopyUtils.copy(userRoleMapper.selectByUserRole(userId, roleId), UmsUserRole.class);
    }

    @Override
    public boolean renewById(Long bindId, LocalDateTime endTime) {
        return userRoleMapper.updateEndTimeById(bindId, endTime) > 0;
    }
}

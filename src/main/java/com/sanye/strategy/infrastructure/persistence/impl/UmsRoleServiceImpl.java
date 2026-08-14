package com.sanye.strategy.infrastructure.persistence.impl;

import com.sanye.strategy.common.base.MpBaseServiceImpl;
import com.sanye.strategy.common.util.BeanCopyUtils;
import com.sanye.strategy.domain.user.entity.UmsRole;
import com.sanye.strategy.domain.user.repository.UmsRoleService;
import com.sanye.strategy.infrastructure.persistence.mapper.UmsRoleMapper;
import com.sanye.strategy.infrastructure.persistence.po.UmsRolePO;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 角色 Service 实现 — 继承 {@link MpBaseServiceImpl}，桥接实体 {@link UmsRole} 与 PO {@link UmsRolePO}
 * </p>
 *
 * @author 31372
 */
@Service
public class UmsRoleServiceImpl extends MpBaseServiceImpl<UmsRolePO, UmsRoleMapper, UmsRole>
        implements UmsRoleService {

    @Override
    protected UmsRolePO toPO(UmsRole entity) {
        return BeanCopyUtils.copy(entity, UmsRolePO.class);
    }

    @Override
    protected UmsRole toEntity(UmsRolePO po) {
        return BeanCopyUtils.copy(po, UmsRole.class);
    }
}

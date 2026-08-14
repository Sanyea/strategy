package com.sanye.strategy.infrastructure.persistence.impl;

import com.sanye.strategy.common.base.MpBaseServiceImpl;
import com.sanye.strategy.common.util.BeanCopyUtils;
import com.sanye.strategy.domain.rbac.entity.UmsPermission;
import com.sanye.strategy.domain.rbac.repository.UmsPermissionService;
import com.sanye.strategy.infrastructure.persistence.mapper.UmsPermissionMapper;
import com.sanye.strategy.infrastructure.persistence.po.UmsPermissionPO;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 权限资源 Service 实现 — 继承 {@link MpBaseServiceImpl}，桥接实体 {@link UmsPermission} 与 PO {@link UmsPermissionPO}
 * </p>
 *
 * @author 31372
 */
@Service
public class UmsPermissionServiceImpl extends MpBaseServiceImpl<UmsPermissionPO, UmsPermissionMapper, UmsPermission>
        implements UmsPermissionService {

    @Override
    protected UmsPermissionPO toPO(UmsPermission entity) {
        return BeanCopyUtils.copy(entity, UmsPermissionPO.class);
    }

    @Override
    protected UmsPermission toEntity(UmsPermissionPO po) {
        return BeanCopyUtils.copy(po, UmsPermission.class);
    }
}

package com.sanye.strategy.infrastructure.persistence.impl;

import com.sanye.strategy.common.base.MpBaseServiceImpl;
import com.sanye.strategy.common.util.BeanCopyUtils;
import com.sanye.strategy.domain.user.entity.UmsUser;
import com.sanye.strategy.infrastructure.persistence.mapper.UmsUserMapper;
import com.sanye.strategy.infrastructure.persistence.po.UmsUserPO;
import com.sanye.strategy.domain.user.repository.UmsUserService;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 用户主表 Service 实现 — 继承 {@link MpBaseServiceImpl}，桥接实体 {@link UmsUser} 与 PO {@link UmsUserPO}
 * </p>
 *
 * @author 31372
 * @createDate 2026-08-05
 */
@Service
public class UmsUserServiceImpl extends MpBaseServiceImpl<UmsUserPO, UmsUserMapper, UmsUser>
        implements UmsUserService {

    @Override
    protected UmsUserPO toPO(UmsUser entity) {
        return BeanCopyUtils.copy(entity, UmsUserPO.class);
    }

    @Override
    protected UmsUser toEntity(UmsUserPO po) {
        return BeanCopyUtils.copy(po, UmsUser.class);
    }
}

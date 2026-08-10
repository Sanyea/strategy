package com.sanye.strategy.infrastructure.persistence.impl;

import com.sanye.strategy.common.base.MpBaseServiceImpl;
import com.sanye.strategy.common.util.BeanCopyUtils;
import com.sanye.strategy.domain.user.entity.UmsUserAuth;
import com.sanye.strategy.infrastructure.persistence.mapper.UmsUserAuthMapper;
import com.sanye.strategy.infrastructure.persistence.po.UmsUserAuthPO;
import com.sanye.strategy.domain.user.repository.UmsUserAuthService;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 第三方登录关联 Service 实现 — 继承 {@link MpBaseServiceImpl}，桥接实体 {@link UmsUserAuth} 与 PO {@link UmsUserAuthPO}
 * </p>
 *
 * @author 31372
 * @createDate 2026-08-05
 */
@Service
public class UmsUserAuthServiceImpl extends MpBaseServiceImpl<UmsUserAuthPO, UmsUserAuthMapper, UmsUserAuth>
        implements UmsUserAuthService {

    @Override
    protected UmsUserAuthPO toPO(UmsUserAuth entity) {
        return BeanCopyUtils.copy(entity, UmsUserAuthPO.class);
    }

    @Override
    protected UmsUserAuth toEntity(UmsUserAuthPO po) {
        return BeanCopyUtils.copy(po, UmsUserAuth.class);
    }
}

package com.sanye.strategy.service.impl;

import com.sanye.strategy.common.base.MpBaseServiceImpl;
import com.sanye.strategy.common.util.BeanCopyUtils;
import com.sanye.strategy.domain.UmsUserAccountSecurity;
import com.sanye.strategy.mapper.UmsUserAccountSecurityMapper;
import com.sanye.strategy.po.UmsUserAccountSecurityPO;
import com.sanye.strategy.service.UmsUserAccountSecurityService;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 用户账号安全 Service 实现 — 继承 {@link MpBaseServiceImpl}，桥接实体 {@link UmsUserAccountSecurity} 与 PO {@link UmsUserAccountSecurityPO}
 * </p>
 *
 * @author 31372
 * @createDate 2026-08-05
 */
@Service
public class UmsUserAccountSecurityServiceImpl extends MpBaseServiceImpl<UmsUserAccountSecurityPO, UmsUserAccountSecurityMapper, UmsUserAccountSecurity>
        implements UmsUserAccountSecurityService {

    @Override
    protected UmsUserAccountSecurityPO toPO(UmsUserAccountSecurity entity) {
        return BeanCopyUtils.copy(entity, UmsUserAccountSecurityPO.class);
    }

    @Override
    protected UmsUserAccountSecurity toEntity(UmsUserAccountSecurityPO po) {
        return BeanCopyUtils.copy(po, UmsUserAccountSecurity.class);
    }
}

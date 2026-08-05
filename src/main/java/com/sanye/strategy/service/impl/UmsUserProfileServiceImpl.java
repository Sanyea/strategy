package com.sanye.strategy.service.impl;

import com.sanye.strategy.common.base.MpBaseServiceImpl;
import com.sanye.strategy.common.util.BeanCopyUtils;
import com.sanye.strategy.domain.UmsUserProfile;
import com.sanye.strategy.mapper.UmsUserProfileMapper;
import com.sanye.strategy.po.UmsUserProfilePO;
import com.sanye.strategy.service.UmsUserProfileService;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 用户扩展信息 Service 实现 — 继承 {@link MpBaseServiceImpl}，桥接实体 {@link UmsUserProfile} 与 PO {@link UmsUserProfilePO}
 * </p>
 *
 * @author 31372
 * @createDate 2026-08-05
 */
@Service
public class UmsUserProfileServiceImpl extends MpBaseServiceImpl<UmsUserProfilePO, UmsUserProfileMapper, UmsUserProfile>
        implements UmsUserProfileService {

    @Override
    protected UmsUserProfilePO toPO(UmsUserProfile entity) {
        return BeanCopyUtils.copy(entity, UmsUserProfilePO.class);
    }

    @Override
    protected UmsUserProfile toEntity(UmsUserProfilePO po) {
        return BeanCopyUtils.copy(po, UmsUserProfile.class);
    }
}

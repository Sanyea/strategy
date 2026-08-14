package com.sanye.strategy.infrastructure.persistence.impl;

import com.sanye.strategy.common.base.MpBaseServiceImpl;
import com.sanye.strategy.common.util.BeanCopyUtils;
import com.sanye.strategy.domain.user.entity.UmsUserLoginDevice;
import com.sanye.strategy.infrastructure.persistence.mapper.UmsUserLoginDeviceMapper;
import com.sanye.strategy.infrastructure.persistence.po.UmsUserLoginDevicePO;
import com.sanye.strategy.domain.user.repository.UmsUserLoginDeviceService;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * <p>
 * 用户登录设备 Service 实现 — 继承 {@link MpBaseServiceImpl}，桥接实体 {@link UmsUserLoginDevice} 与 PO {@link UmsUserLoginDevicePO}
 * </p>
 *
 * @author 31372
 * @createDate 2026-08-05
 */
@Service
public class UmsUserLoginDeviceServiceImpl extends MpBaseServiceImpl<UmsUserLoginDevicePO, UmsUserLoginDeviceMapper, UmsUserLoginDevice>
        implements UmsUserLoginDeviceService {

    @Override
    protected UmsUserLoginDevicePO toPO(UmsUserLoginDevice entity) {
        return BeanCopyUtils.copy(entity, UmsUserLoginDevicePO.class);
    }

    @Override
    protected UmsUserLoginDevice toEntity(UmsUserLoginDevicePO po) {
        return BeanCopyUtils.copy(po, UmsUserLoginDevice.class);
    }

    @Override
    public List<UmsUserLoginDevice> listActiveSessionsByUserIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<UmsUserLoginDevicePO> pos = baseMapper.selectActiveSessionsByUserIds(userIds);
        return BeanCopyUtils.copyList(pos, UmsUserLoginDevice.class);
    }
}

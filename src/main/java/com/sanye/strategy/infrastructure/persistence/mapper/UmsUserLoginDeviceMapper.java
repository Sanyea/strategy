package com.sanye.strategy.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanye.strategy.infrastructure.persistence.po.UmsUserLoginDevicePO;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * <p>
 * 用户登录设备 Mapper — 操作 {@link UmsUserLoginDevicePO}
 * </p>
 * <p>
 * 继承 MP {@link BaseMapper}，标准 CRUD 免写 SQL；XML 结果映射见
 * {@code src/main/resources/mapper/UmsUserLoginDeviceMapper.xml}。
 * </p>
 *
 * @author 31372
 * @createDate 2026-08-05
 */
public interface UmsUserLoginDeviceMapper extends BaseMapper<UmsUserLoginDevicePO> {

    /**
     * 用户集活动会话（is_current=1 且未删除），供踢下线批量吊销
     *
     * @param userIds 用户ID集合
     * @return 活动会话行列表
     */
    List<UmsUserLoginDevicePO> selectActiveSessionsByUserIds(@Param("userIds") Collection<Long> userIds);
}

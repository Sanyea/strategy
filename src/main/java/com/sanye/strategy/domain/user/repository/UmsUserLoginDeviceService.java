package com.sanye.strategy.domain.user.repository;

import com.sanye.strategy.common.base.IService;
import com.sanye.strategy.domain.user.entity.UmsUserLoginDevice;

import java.util.Collection;
import java.util.List;

/**
 * <p>
 * 用户登录设备 Service 接口 — 继承自定义 DIP {@link IService}，操作领域实体 {@link UmsUserLoginDevice}
 * </p>
 *
 * @author 31372
 * @createDate 2026-08-05
 */
public interface UmsUserLoginDeviceService extends IService<UmsUserLoginDevice> {

    /**
     * 查询用户集的活动会话（is_current=1 且未删除）
     * <p>供踢下线（jti 黑名单批量吊销 accessToken）查询待吊销会话，
     * 由 {@code EvictService} 消费；不关心 refresh 会话，仅当前有效设备行。</p>
     *
     * @param userIds 用户ID集合
     * @return 活动会话列表，空/null 入参返回空列表
     */
    List<UmsUserLoginDevice> listActiveSessionsByUserIds(Collection<Long> userIds);
}

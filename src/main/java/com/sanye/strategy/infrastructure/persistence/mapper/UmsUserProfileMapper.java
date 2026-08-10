package com.sanye.strategy.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanye.strategy.infrastructure.persistence.po.UmsUserProfilePO;

/**
 * <p>
 * 用户扩展信息 Mapper — 操作 {@link UmsUserProfilePO}
 * </p>
 * <p>
 * 继承 MP {@link BaseMapper}，标准 CRUD 免写 SQL；XML 结果映射见
 * {@code src/main/resources/mapper/UmsUserProfileMapper.xml}。
 * </p>
 *
 * @author 31372
 * @createDate 2026-08-05
 */
public interface UmsUserProfileMapper extends BaseMapper<UmsUserProfilePO> {

}

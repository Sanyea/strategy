package com.sanye.strategy.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanye.strategy.infrastructure.persistence.po.UmsUserAuthPO;

/**
 * <p>
 * 第三方登录关联 Mapper — 操作 {@link UmsUserAuthPO}
 * </p>
 * <p>
 * 继承 MP {@link BaseMapper}，标准 CRUD 免写 SQL；XML 结果映射见
 * {@code src/main/resources/mapper/UmsUserAuthMapper.xml}。
 * </p>
 *
 * @author 31372
 * @createDate 2026-08-05
 */
public interface UmsUserAuthMapper extends BaseMapper<UmsUserAuthPO> {

}

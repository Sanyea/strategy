package com.sanye.strategy.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanye.strategy.infrastructure.persistence.po.UmsOperLogPO;

/**
 * <p>
 * 操作日志 Mapper — 操作 {@link UmsOperLogPO}
 * </p>
 * <p>
 * 仅插入场景（写审计留痕），继承 MP {@link BaseMapper} 即可；XML 结果映射见
 * {@code src/main/resources/mapper/UmsOperLogMapper.xml}。
 * </p>
 *
 * @author 31372
 */
public interface UmsOperLogMapper extends BaseMapper<UmsOperLogPO> {

}

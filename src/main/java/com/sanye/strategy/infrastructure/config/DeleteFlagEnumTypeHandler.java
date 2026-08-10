package com.sanye.strategy.infrastructure.config;

import com.sanye.strategy.common.base.DeleteFlagEnum;
import com.sanye.strategy.common.base.IPersistEnum;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * <p>
 * {@link DeleteFlagEnum} 持久化适配 — 约定式枚举映射的桥接 TypeHandler
 * </p>
 * <p>
 * 实体层 {@link DeleteFlagEnum} 已与 ORM 彻底解耦：不再依赖 MyBatis-Plus 的 {@code @EnumValue}，
 * 仅实现纯接口 {@link IPersistEnum}（位于 {@code common.base}，零框架依赖）。
 * 本处理器在持久化边界完成 枚举 ↔ 数据库值 双向转换：
 * <ul>
 *   <li>写：调用 {@code getPersistValue()} 取映射码写入列</li>
 *   <li>读：列值经 {@link DeleteFlagEnum#valueOf(Integer)} 还原枚举</li>
 * </ul>
 * 通过 {@code mybatis-plus.type-handlers-package} 扫描 + {@link MappedTypes} 注册到 MyBatis，
 * PO 层（{@code SimpleBasePO#getDeleted()}）字段声明无需任何改动。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：适配器（Adapter）。框架耦合的唯一出口，实体/PO 上层零 MP 注解。</li>
 *   <li>优缺点：实体纯 POJO，换 ORM 只改处理器；缺点：每个约定式枚举需一个映射（当前仅 DeleteFlagEnum）。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@MappedTypes(DeleteFlagEnum.class)
public class DeleteFlagEnumTypeHandler extends BaseTypeHandler<DeleteFlagEnum> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, DeleteFlagEnum parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setInt(i, parameter.getPersistValue());
    }

    @Override
    public DeleteFlagEnum getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return DeleteFlagEnum.valueOf(rs.getObject(columnName, Integer.class));
    }

    @Override
    public DeleteFlagEnum getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return DeleteFlagEnum.valueOf(rs.getObject(columnIndex, Integer.class));
    }

    @Override
    public DeleteFlagEnum getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return DeleteFlagEnum.valueOf(cs.getObject(columnIndex, Integer.class));
    }
}

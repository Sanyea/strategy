package com.sanye.strategy.common.config;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sanye.strategy.common.base.DeleteFlagEnum;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.TypeHandler;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <p>
 * {@link DeleteFlagEnumTypeHandler} 注册与转换验证
 * </p>
 * <p>
 * 验证两点：
 * <ul>
 *   <li>{@code mybatis-plus.type-handlers-package} 扫描 + {@code @MappedTypes} 是否将处理器
 *       注册到 MyBatis（实体层 DeleteFlagEnum 无 {@code @EnumValue}，PO 层字段声明未动）</li>
 *   <li>写库（{@code getPersistValue()} → 列值）与读库（列值 → 枚举）双向转换正确</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
class DeleteFlagEnumTypeHandlerTest {

    @Test
    void shouldRegisterHandlerForDeleteFlagEnumViaTypeHandlersPackage() throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(mock(DataSource.class));
        factory.setTypeHandlersPackage("com.sanye.strategy.common.config");

        SqlSessionFactory sqlSessionFactory = factory.getObject();
        TypeHandler<?> handler = sqlSessionFactory.getConfiguration()
                .getTypeHandlerRegistry()
                .getTypeHandler(DeleteFlagEnum.class, null);

        assertThat(handler).isInstanceOf(DeleteFlagEnumTypeHandler.class);
    }

    @Test
    void shouldWritePersistValueToStatement() throws Exception {
        DeleteFlagEnumTypeHandler handler = new DeleteFlagEnumTypeHandler();
        PreparedStatement ps = mock(PreparedStatement.class);

        handler.setNonNullParameter(ps, 1, DeleteFlagEnum.DELETED, null);

        verify(ps).setInt(1, 1);
    }

    @Test
    void shouldReadEnumFromResultSet() throws Exception {
        DeleteFlagEnumTypeHandler handler = new DeleteFlagEnumTypeHandler();
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("deleted", Integer.class)).thenReturn(0);

        DeleteFlagEnum deleted = handler.getNullableResult(rs, "deleted");

        assertThat(deleted).isEqualTo(DeleteFlagEnum.NOT_DELETED);
    }
}

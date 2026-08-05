package com.sanye.strategy.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * <p>
 * MyBatis-Plus 配置
 * </p>
 * <p>
 * 注册分页拦截器与自动填充处理器：
 * <ul>
 *   <li>{@link MybatisPlusInterceptor} + {@link PaginationInnerInterceptor} — 分页（LIMIT 改写 + COUNT），
 *       依赖 mybatis-plus-jsqlparser 构件（MP 3.5.9+ 起分页拦截器独立于此模块）</li>
 *   <li>{@link MetaObjectHandler} — 自动填充 PO 的 createTime/updateTime
 *       （对应 {@code BasePO} 的 {@code @TableField(fill)}）</li>
 * </ul>
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：框架装配层，只声明 Bean，不承载业务。</li>
 *   <li>优缺点：注册即用、解耦；缺点：自动填充仅对 PO 生效（实体为纯 POJO 不参与），
 *       createUserId/updateUserId 需用户上下文（Spring Security/拦截器）接入后补充。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * MyBatis-Plus 插件容器，注册分页拦截器
     *
     * @return 插件容器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 每页最大行数限制，防止恶意大 size 拖垮 DB
        pagination.setMaxLimit(500L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    /**
     * 自动填充处理器 — 插入/更新时填充时间字段
     * <p>
     * 当前填充 createTime/updateTime；createUserId/updateUserId 待用户上下文接入后补充。
     * </p>
     *
     * @return 填充处理器
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
                this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}

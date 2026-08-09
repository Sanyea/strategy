package com.sanye.strategy.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <p>
 * 编程式事务模板配置 — 事务能力单一收口
 * </p>
 * <p>
 * 桥接层（{@code MpBaseServiceImpl} 批量 {@code doInTransaction}）与门面聚合 Service
 * （跨表写编排，如注册/登录）共用同一 {@link TransactionTemplate} Bean。
 * 门面非 {@code AbstractBaseService} 子类、够不到其 {@code doInTransaction} 钩子，
 * 故经注入本 Bean 完成跨表事务；替换事务实现（JTA/Atomikos/Seata）只换
 * {@link PlatformTransactionManager} 即可。
 * </p>
 *
 * @author 31372
 */
@Configuration
public class TransactionConfig {

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return template;
    }
}

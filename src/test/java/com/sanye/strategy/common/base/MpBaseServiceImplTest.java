package com.sanye.strategy.common.base;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <p>
 * {@link MpBaseServiceImpl} 主键回填验证
 * </p>
 * <p>
 * 冒烟发现：桥接层 insert 走 {@code baseMapper.insert(toPO(entity))} 后未把 MP 生成的雪花主键
 * 回填到实体，业务侧 {@code entity.getId()} 仍为 null（注册 initSecurity/initProfile/createSession
 * 引用 user.id 时触发 user_id NOT NULL 违例）。此处锁定三种插入路径的回填行为。
 * </p>
 *
 * @author 31372
 */
class MpBaseServiceImplTest {

    /** 冒烟实体：仅 id */
    private static class TestEntity extends SimpleBaseEntity {
    }

    /** 冒烟 PO：仅 id */
    private static class TestPO extends SimpleBasePO {
    }

    private static class TestServiceImpl extends MpBaseServiceImpl<TestPO, BaseMapper<TestPO>, TestEntity> {

        @Override
        protected TestPO toPO(TestEntity entity) {
            TestPO po = new TestPO();
            po.setId(entity.getId());
            return po;
        }

        @Override
        protected TestEntity toEntity(TestPO po) {
            TestEntity entity = new TestEntity();
            entity.setId(po.getId());
            return entity;
        }
    }

    private TestServiceImpl newServiceAssigningIds(long baseId) {
        TestServiceImpl svc = new TestServiceImpl();
        BaseMapper<TestPO> mapper = mock(BaseMapper.class);
        svc.baseMapper = mapper;
        java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong(baseId);
        when(mapper.insert(any(TestPO.class))).thenAnswer(inv -> {
            TestPO po = inv.getArgument(0);
            po.setId(seq.getAndIncrement()); // 模拟 MP ASSIGN_ID 每次调用生成新主键
            return 1;
        });
        return svc;
    }

    @Test
    void shouldBackfillGeneratedIdOnInsert() {
        TestServiceImpl svc = newServiceAssigningIds(123L);
        TestEntity entity = new TestEntity();

        boolean ok = svc.doInsert(entity);

        assertThat(ok).isTrue();
        assertThat(entity.getId()).isEqualTo(123L);
    }

    @Test
    void shouldBackfillGeneratedIdOnSaveOrUpdateInsertBranch() {
        TestServiceImpl svc = newServiceAssigningIds(456L);
        TestEntity entity = new TestEntity();

        boolean ok = svc.doSaveOrUpdate(entity);

        assertThat(ok).isTrue();
        assertThat(entity.getId()).isEqualTo(456L);
    }

    @Test
    void shouldBackfillGeneratedIdOnBatchInsert() {
        TestServiceImpl svc = newServiceAssigningIds(700L);
        TestEntity e1 = new TestEntity();
        TestEntity e2 = new TestEntity();

        int affected = svc.doInsertBatch(List.of(e1, e2));

        assertThat(affected).isEqualTo(2);
        assertThat(e1.getId()).isEqualTo(700L);
        assertThat(e2.getId()).isEqualTo(701L);
    }
}

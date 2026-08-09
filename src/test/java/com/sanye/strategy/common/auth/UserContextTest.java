package com.sanye.strategy.common.auth;

import com.sanye.strategy.enums.UserTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p>
 * {@link UserContext} ThreadLocal 语义验证
 * </p>
 *
 * @author 31372
 */
class UserContextTest {

    @Test
    void shouldSetGetAndClear() {
        UserContext context = new UserContext(1L, UserTypeEnum.NORMAL_USER, 10L, "dev-1");

        UserContext.set(context);
        assertThat(UserContext.get()).isSameAs(context);
        assertThat(UserContext.get().getUserId()).isEqualTo(1L);
        assertThat(UserContext.get().getUserType()).isEqualTo(UserTypeEnum.NORMAL_USER);
        assertThat(UserContext.get().getJti()).isEqualTo(10L);
        assertThat(UserContext.get().getDeviceId()).isEqualTo("dev-1");

        UserContext.clear();
        assertThat(UserContext.get()).isNull();
    }

    @Test
    void shouldNotLeakAcrossThreads() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<UserContext> otherThread = new AtomicReference<>();

        Thread t = new Thread(() -> {
            UserContext.set(new UserContext(2L, UserTypeEnum.OPERATOR, 20L, "dev-2"));
            otherThread.set(UserContext.get());
            latch.countDown();
        });
        t.start();
        latch.await(5, TimeUnit.SECONDS);

        // 主线程无上下文，不受其他线程影响
        assertThat(UserContext.get()).isNull();
        assertThat(otherThread.get().getUserId()).isEqualTo(2L);
    }
}

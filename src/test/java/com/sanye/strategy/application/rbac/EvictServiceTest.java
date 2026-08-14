package com.sanye.strategy.application.rbac;

import com.sanye.strategy.domain.user.entity.UmsUserLoginDevice;
import com.sanye.strategy.domain.user.repository.UmsUserLoginDeviceService;
import com.sanye.strategy.domain.user.repository.UmsUserRoleService;
import com.sanye.strategy.infrastructure.redis.JtiBlacklistService;
import com.sanye.strategy.infrastructure.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <p>
 * 用户踢下线服务单测 — mock {@link UmsUserLoginDeviceService}/{@link UmsUserRoleService}/
 * {@link JtiBlacklistService}/{@link JwtUtil}
 * </p>
 * <p>
 * 覆盖：仅未过期会话 revoke、TTL=剩余 exp 秒（≥1，非 jwtUtil fallback）、空集合 0 调用、
 * expireTime null 回落 jwt TTL、evictRoleUsers offset 分页终止（brief 已知修复：
 * 无 offset 时同一批 500 反复返回、501+ 永不踢）、Redis 抖动重试 3 次后成功 /
 * 3 次仍失败上抛（调用方审计失败状态）、退避期间 InterruptedException 恢复中断标记。
 * </p>
 */
class EvictServiceTest {

    private UmsUserLoginDeviceService deviceService;
    private UmsUserRoleService userRoleService;
    private JtiBlacklistService jtiBlacklistService;
    private JwtUtil jwtUtil;
    private EvictService evictService;

    @BeforeEach
    void setUp() {
        deviceService = mock(UmsUserLoginDeviceService.class);
        userRoleService = mock(UmsUserRoleService.class);
        jtiBlacklistService = mock(JtiBlacklistService.class);
        jwtUtil = mock(JwtUtil.class);
        when(jwtUtil.getAccessTokenTtlSeconds()).thenReturn(9999L);
        evictService = new EvictService(deviceService, userRoleService, jtiBlacklistService, jwtUtil);
    }

    private static UmsUserLoginDevice session(Long id, LocalDateTime expireTime) {
        UmsUserLoginDevice s = new UmsUserLoginDevice();
        s.setId(id);
        s.setExpireTime(expireTime);
        return s;
    }

    private static List<Long> ids(long start, int count) {
        return LongStream.range(start, start + count).boxed().collect(Collectors.toList());
    }

    @Test
    void evictUsersRevokesOnlyActiveSessionWithRemainingTtl() {
        LocalDateTime now = LocalDateTime.now();
        UmsUserLoginDevice active = session(1L, now.plusSeconds(600));
        UmsUserLoginDevice expired = session(2L, now.minusSeconds(60));
        when(deviceService.listActiveSessionsByUserIds(anyCollection())).thenReturn(List.of(active, expired));

        int kicked = evictService.evictUsers(List.of(100L));

        assertEquals(1, kicked);
        ArgumentCaptor<Long> ttl = ArgumentCaptor.forClass(Long.class);
        verify(jtiBlacklistService, times(1)).revoke(eq(1L), ttl.capture());
        // TTL = 会话剩余 exp 秒（≤600 证明走会话 expireTime，非 jwtUtil fallback 9999），且 ≥1
        assertTrue(ttl.getValue() >= 1 && ttl.getValue() <= 600);
        verify(jtiBlacklistService, never()).revoke(eq(2L), anyLong());
    }

    @Test
    void evictUsersEmptyOrNullCollectionDoesNothing() {
        assertEquals(0, evictService.evictUsers(Collections.emptyList()));
        assertEquals(0, evictService.evictUsers(null));

        verify(deviceService, never()).listActiveSessionsByUserIds(anyCollection());
        verify(jtiBlacklistService, never()).revoke(anyLong(), anyLong());
    }

    @Test
    void evictUsersNullExpireTimeFallsBackToJwtTtl() {
        when(deviceService.listActiveSessionsByUserIds(anyCollection())).thenReturn(List.of(session(5L, null)));

        int kicked = evictService.evictUsers(List.of(100L));

        assertEquals(1, kicked);
        verify(jtiBlacklistService, times(1)).revoke(eq(5L), eq(9999L));
    }

    @Test
    void evictRoleUsersPaginatesWithOffsetUntilShortPage() {
        when(userRoleService.listActiveUserIdsByRoleId(eq(10L), eq(0L), eq(500))).thenReturn(ids(1L, 500));
        when(userRoleService.listActiveUserIdsByRoleId(eq(10L), eq(500L), eq(500))).thenReturn(ids(501L, 100));
        when(deviceService.listActiveSessionsByUserIds(anyCollection())).thenAnswer(inv -> {
            Collection<Long> userIds = inv.getArgument(0);
            return userIds.stream().map(uid -> session(uid, LocalDateTime.now().plusSeconds(600)))
                    .collect(Collectors.toList());
        });

        int kicked = evictService.evictRoleUsers(10L);

        // 500 + 100 全部未过期踢中；第二页短页后终止，不再请求第三页
        assertEquals(600, kicked);
        ArgumentCaptor<Long> offset = ArgumentCaptor.forClass(Long.class);
        verify(userRoleService, times(2)).listActiveUserIdsByRoleId(eq(10L), offset.capture(), eq(500));
        assertEquals(List.of(0L, 500L), offset.getAllValues());
    }

    @Test
    void revokeRetriesOnTransientFailureThenSucceeds() {
        when(deviceService.listActiveSessionsByUserIds(anyCollection()))
                .thenReturn(List.of(session(1L, LocalDateTime.now().plusSeconds(600))));
        // revoke 为 void：void 桩须用 doXxx().when()（when() 无法接收 void 表达式）
        doThrow(new RuntimeException("redis timeout"))
                .doThrow(new RuntimeException("redis timeout"))
                .doNothing()
                .when(jtiBlacklistService).revoke(anyLong(), anyLong());

        int kicked = evictService.evictUsers(List.of(100L));

        assertEquals(1, kicked);
        verify(jtiBlacklistService, times(3)).revoke(anyLong(), anyLong());
    }

    @Test
    void revokeThrowsWhenRetriesExhausted() {
        when(deviceService.listActiveSessionsByUserIds(anyCollection()))
                .thenReturn(List.of(session(1L, LocalDateTime.now().plusSeconds(600))));
        doThrow(new RuntimeException("redis down")).when(jtiBlacklistService).revoke(anyLong(), anyLong());

        assertThrows(RuntimeException.class, () -> evictService.evictUsers(List.of(100L)));
        verify(jtiBlacklistService, times(3)).revoke(anyLong(), anyLong());
    }

    @Test
    void interruptDuringBackoffRestoresInterruptFlag() {
        when(deviceService.listActiveSessionsByUserIds(anyCollection()))
                .thenReturn(List.of(session(1L, LocalDateTime.now().plusSeconds(600))));
        doThrow(new RuntimeException("redis down")).when(jtiBlacklistService).revoke(anyLong(), anyLong());
        Thread.currentThread().interrupt();
        try {
            // 预置中断标记 → 首次退避 Thread.sleep 立即抛 InterruptedException，
            // 代码须恢复中断标记并上抛原异常（不吞中断、不吞 Redis 失败）
            assertThrows(RuntimeException.class, () -> evictService.evictUsers(List.of(100L)));
            assertTrue(Thread.currentThread().isInterrupted(), "捕获 InterruptedException 后应恢复中断标记");
        } finally {
            Thread.interrupted(); // 清理测试线程中断标记，避免污染后续用例
        }
    }
}

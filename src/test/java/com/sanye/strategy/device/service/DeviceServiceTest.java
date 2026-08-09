package com.sanye.strategy.device.service;

import com.sanye.strategy.common.util.HashUtil;
import com.sanye.strategy.device.dto.DeviceInfo;
import com.sanye.strategy.domain.UmsUserLoginDevice;
import com.sanye.strategy.enums.DeviceTypeEnum;
import com.sanye.strategy.enums.YesNoEnum;
import com.sanye.strategy.service.UmsUserLoginDeviceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <p>
 * {@link DeviceService} 会话行操作验证
 * </p>
 *
 * @author 31372
 */
class DeviceServiceTest {

    private final UmsUserLoginDeviceService loginDeviceService = mock(UmsUserLoginDeviceService.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final DeviceService deviceService = new DeviceService(loginDeviceService, transactionTemplate);

    {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    @Test
    void shouldCreateSessionWithHashedRefreshToken() {
        DeviceInfo info = new DeviceInfo();
        info.setDeviceType(DeviceTypeEnum.PHONE.getCode());
        info.setDeviceOs("iOS");
        info.setDeviceId("device-1");

        UmsUserLoginDevice entity = deviceService.createSession(100L, info, "127.0.0.1", "raw-token", 14);

        assertThat(entity.getUserId()).isEqualTo(100L);
        assertThat(entity.getDeviceType()).isEqualTo(DeviceTypeEnum.PHONE);
        assertThat(entity.getDeviceId()).isEqualTo("device-1");
        assertThat(entity.getLoginIp()).isEqualTo("127.0.0.1");
        assertThat(entity.getIsCurrent()).isEqualTo(YesNoEnum.YES);
        assertThat(entity.getRefreshTokenHash()).isEqualTo(HashUtil.sha256Hex("raw-token"));
        verify(loginDeviceService).insert(entity);
    }

    @Test
    void shouldFindSessionByRefreshTokenHash() {
        UmsUserLoginDevice session = new UmsUserLoginDevice();
        when(loginDeviceService.getOne(any())).thenReturn(session);

        UmsUserLoginDevice found = deviceService.findByRefreshTokenHash("some-hash");

        assertThat(found).isSameAs(session);
    }

    @Test
    void shouldRotateRefreshTokenPartialUpdate() {
        UmsUserLoginDevice current = new UmsUserLoginDevice();
        current.setId(5L);
        current.setRefreshTokenHash(HashUtil.sha256Hex("old-token"));
        when(loginDeviceService.getOne(any())).thenReturn(current);

        boolean rotated = deviceService.rotateRefreshToken(5L, HashUtil.sha256Hex("old-token"), "new-token", 14);

        assertThat(rotated).isTrue();
        ArgumentCaptor<UmsUserLoginDevice> captor = ArgumentCaptor.forClass(UmsUserLoginDevice.class);
        verify(loginDeviceService).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(5L);
        assertThat(captor.getValue().getRefreshTokenHash()).isEqualTo(HashUtil.sha256Hex("new-token"));
        assertThat(captor.getValue().getExpireTime()).isNotNull();
    }

    @Test
    void shouldRejectRotationWhenHashMismatch() {
        // 并发双刷：库中哈希已是新值（旧哈希不匹配）→ 拒绝轮换，不写库
        UmsUserLoginDevice current = new UmsUserLoginDevice();
        current.setId(5L);
        current.setRefreshTokenHash(HashUtil.sha256Hex("already-rotated"));
        when(loginDeviceService.getOne(any())).thenReturn(current);

        boolean rotated = deviceService.rotateRefreshToken(5L, HashUtil.sha256Hex("old-token"), "new-token", 14);

        assertThat(rotated).isFalse();
        verify(loginDeviceService, never()).updateById(any());
    }

    @Test
    void shouldRejectRotationWhenSessionGone() {
        when(loginDeviceService.getOne(any())).thenReturn(null);

        boolean rotated = deviceService.rotateRefreshToken(5L, HashUtil.sha256Hex("old-token"), "new-token", 14);

        assertThat(rotated).isFalse();
        verify(loginDeviceService, never()).updateById(any());
    }

    @Test
    void shouldInvalidateSession() {
        deviceService.invalidateSession(5L);

        ArgumentCaptor<UmsUserLoginDevice> captor = ArgumentCaptor.forClass(UmsUserLoginDevice.class);
        verify(loginDeviceService).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(5L);
        assertThat(captor.getValue().getIsCurrent()).isEqualTo(YesNoEnum.NO);
    }
}

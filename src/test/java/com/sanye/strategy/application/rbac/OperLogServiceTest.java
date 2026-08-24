package com.sanye.strategy.application.rbac;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sanye.strategy.domain.enums.OperTypeEnum;
import com.sanye.strategy.infrastructure.persistence.mapper.UmsOperLogMapper;
import com.sanye.strategy.infrastructure.persistence.po.UmsOperLogPO;
import com.sanye.strategy.infrastructure.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OperLogService 单测 — trace_id 取自 MDC / operator_type 逻辑推导 / target 元数据 /
 * change_diff 透传 / audit.log 双写 / 写库失败降级不上抛
 */
class OperLogServiceTest {

    private UmsOperLogMapper mapper;
    private PlatformTransactionManager txManager;
    private OperLogService service;
    private ListAppender<ILoggingEvent> auditAppender;

    @BeforeEach
    void setUp() {
        mapper = mock(UmsOperLogMapper.class);
        txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new OperLogService(txManager, mapper);
        Logger auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        auditAppender = new ListAppender<>();
        auditAppender.start();
        auditLogger.addAppender(auditAppender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger("AUDIT")).detachAppender(auditAppender);
        MDC.clear();
        UserContext.clear();
    }

    private OperLogReq req() {
        return OperLogReq.builder().module("rbac").action("updateRole").desc("测试")
                .type(OperTypeEnum.UPDATE).success(true)
                .targetEntity("ums_role").targetId(9L)
                .changeDiff("[{\"field\":\"roleName\",\"old\":\"a\",\"new\":\"b\"}]")
                .build();
    }

    @Test
    void fillsTraceIdFromMdcAndTargetFromReq() {
        MDC.put("traceId", "trace-abc");
        UserContext.set(new UserContext(1L, List.of("SUPER_ADMIN"), List.of(), 2L, "dev-1"));
        service.record(req());
        ArgumentCaptor<UmsOperLogPO> captor = ArgumentCaptor.forClass(UmsOperLogPO.class);
        verify(mapper).insert(captor.capture());
        UmsOperLogPO po = captor.getValue();
        assertEquals("trace-abc", po.getTraceId());
        assertEquals("ums_role", po.getTargetEntity());
        assertEquals(9L, po.getTargetId());
        assertEquals(1, po.getOperatorType());
        assertEquals("[{\"field\":\"roleName\",\"old\":\"a\",\"new\":\"b\"}]", po.getChangeDiff());
    }

    @Test
    void operatorTypeIsSystemWithoutUserContext() {
        service.record(req());
        ArgumentCaptor<UmsOperLogPO> captor = ArgumentCaptor.forClass(UmsOperLogPO.class);
        verify(mapper).insert(captor.capture());
        assertEquals(2, captor.getValue().getOperatorType());
    }

    @Test
    void writesAuditJsonToAuditLogger() {
        MDC.put("traceId", "trace-abc");
        service.record(req());
        assertEquals(1, auditAppender.list.size());
        String auditJson = auditAppender.list.get(0).getFormattedMessage();
        assertTrue(auditJson.contains("\"traceId\":\"trace-abc\""));
        assertTrue(auditJson.contains("\"targetEntity\":\"ums_role\""));
        assertTrue(auditJson.contains("\"operatorType\":2"));
        assertTrue(auditJson.contains("\"changeDiff\":\"[{\\\"field\\\":\\\"roleName\\\",\\\"old\\\":\\\"a\\\",\\\"new\\\":\\\"b\\\"}]\""));
    }

    @Test
    void dbFailureDegradesWithoutThrowing() {
        when(mapper.insert(any(UmsOperLogPO.class))).thenThrow(new RuntimeException("db down"));
        service.record(req());
        // 不上抛即通过（降级语义）
    }
}
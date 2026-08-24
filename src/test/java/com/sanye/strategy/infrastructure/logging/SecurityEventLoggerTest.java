package com.sanye.strategy.infrastructure.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SecurityEventLogger 单测 — IP 分级策略（高威胁完整/普通掩码）+ 结构化 kv 输出
 */
class SecurityEventLoggerTest {

    private SecurityEventLogger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = new SecurityEventLogger();
        Logger securityLogger = (Logger) LoggerFactory.getLogger("SECURITY");
        appender = new ListAppender<>();
        appender.start();
        securityLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger("SECURITY")).detachAppender(appender);
    }

    private Optional<KeyValuePair> kv(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream().filter(p -> key.equals(p.key)).findFirst();
    }

    @Test
    void authzKeepsFullIp() {
        logger.log("authz", "user1", "10.1.2.3", "DENY", "越权访问 /rbac/roles");
        ILoggingEvent event = appender.list.get(0);
        assertEquals("10.1.2.3", kv(event, "ip").orElseThrow().value);
        assertEquals("authz", kv(event, "securityType").orElseThrow().value);
        assertEquals("DENY", kv(event, "result").orElseThrow().value);
    }

    @Test
    void accountAndAnomalyKeepFullIp() {
        logger.log("account", "user1", "10.1.2.3", "LOCKED", "密码错 5 次锁定");
        assertEquals("10.1.2.3", kv(appender.list.get(0), "ip").orElseThrow().value);
        logger.log("anomaly", "user1", "10.1.2.4", "RISK", "风控触发");
        assertEquals("10.1.2.4", kv(appender.list.get(1), "ip").orElseThrow().value);
    }

    @Test
    void authnMasksIpLastSegment() {
        logger.log("authn", "user1", "10.1.2.3", "SUCCESS", "登录成功");
        assertEquals("10.1.2.***", kv(appender.list.get(0), "ip").orElseThrow().value);
    }

    @Test
    void credentialMasksIpLastSegment() {
        logger.log("credential", "user1", "10.1.2.3", "SUCCESS", "改密");
        assertEquals("10.1.2.***", kv(appender.list.get(0), "ip").orElseThrow().value);
    }

    @Test
    void messageContainsAccount() {
        logger.log("authn", "user1", "10.1.2.3", "FAIL", "密码错误");
        assertTrue(appender.list.get(0).getFormattedMessage().contains("user1"));
    }
}
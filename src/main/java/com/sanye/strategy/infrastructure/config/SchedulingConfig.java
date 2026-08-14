package com.sanye.strategy.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 定时任务配置 — 启用 @Scheduled（集群部署须分布式锁，见 spec 待办） */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}

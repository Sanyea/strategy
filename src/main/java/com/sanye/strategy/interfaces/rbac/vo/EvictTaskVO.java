package com.sanye.strategy.interfaces.rbac.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * <p>
 * 异步批量踢任务状态 VO — 供前端/调用方查询批量踢执行进度
 * </p>
 * <p>
 * 状态流转：{@code PENDING → RUNNING → SUCCESS | FAILED}；{@code doneAt} 非 null 表示已结束。
 * 仅承载任务元数据与结果摘要，不含敏感信息（无用户/会话明细）。
 * </p>
 *
 * @author 31372
 */
@Data
@Schema(description = "异步批量踢任务状态")
public class EvictTaskVO {

    /**
     * 任务ID（evict_ + 12 位随机串）
     */
    @Schema(description = "任务 ID（evict_ + 12 位随机串）")
    private String taskId;

    /**
     * 任务来源描述（如"角色到期定时清理"）
     */
    @Schema(description = "任务来源描述（如角色到期定时清理）")
    private String sourceDesc;

    /**
     * 状态：PENDING / RUNNING / SUCCESS / FAILED
     * <p>volatile：worker 线程写、请求线程 {@code tasks.get(taskId)} 读，无同一把锁——volatile 建立 happens-before
     * 边，读方可见 worker 的最新状态，避免读到陈旧值/半初始化对象。</p>
     */
    @Schema(description = "任务状态 PENDING/RUNNING/SUCCESS/FAILED")
    private volatile String status;

    /**
     * 踢中会话数（成功时）
     */
    @Schema(description = "踢中会话数（成功时）")
    private volatile Integer kicked;

    /**
     * 失败原因（失败时）
     */
    @Schema(description = "失败原因（失败时）")
    private volatile String error;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 完成时间（成功/失败均回填；null=进行中/待执行）
     */
    @Schema(description = "完成时间（成功/失败均回填；null=进行中/待执行）")
    private volatile LocalDateTime doneAt;
}

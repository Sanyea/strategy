package com.sanye.strategy.application.rbac;

import com.sanye.strategy.domain.enums.OperTypeEnum;
import lombok.Builder;
import lombok.Data;

/**
 * <p>
 * 操作日志写入请求 — {@link OperLogService#record(OperLogReq)} 入参
 * </p>
 * <p>
 * 仅携带审计留痕所需最小字段集：模块/动作/说明/类型 + 请求方法/URI/UA + 结果状态。
 * 操作用户与操作 IP 不入参——用户经 {@code UserContext} 取，IP 经请求上下文取（见
 * {@code OperLogService} 说明），防止调用方伪造。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：审计写入的传输载体（无继承，@Data + builder）。</li>
 *   <li>优缺点：builder 链式构造便于业务侧快速组装；不承载 IP/userId 等推导字段，口径单一。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Data
@Builder
public class OperLogReq {

    /**
     * 操作模块
     */
    private String module;

    /**
     * 操作动作
     */
    private String action;

    /**
     * 操作说明
     */
    private String desc;

    /**
     * 操作类型
     */
    private OperTypeEnum type;

    /**
     * HTTP 方法
     */
    private String requestMethod;

    /**
     * 请求 URI
     */
    private String requestUri;

    /**
     * 浏览器 UA
     */
    private String userAgent;

    /**
     * 操作结果 true-成功 false-失败（落 status 1/0）
     */
    private boolean success;

    /**
     * 错误信息（失败时）
     */
    private String errorMsg;
}

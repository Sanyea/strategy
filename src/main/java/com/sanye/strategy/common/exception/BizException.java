package com.sanye.strategy.common.exception;

import com.sanye.strategy.common.response.ResultCode;

/**
 * <p>
 * 业务异常 — 携带统一状态码
 * </p>
 * <p>
 * 业务层校验失败、状态不允许等可控错误抛出此类，由
 * {@link GlobalExceptionHandler} 统一转换为 {@code R<T>} 响应。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：业务错误信号载体，将"可控业务失败"与"不可控系统异常"区分开。</li>
 *   <li>优缺点：状态码与默认提示语由 {@link ResultCode} 集中定义，调用方按码段处理；
 *       运行时异常避免逐层 throws 声明。缺点：需约定业务代码统一抛出、不直接 catch 吞掉。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 状态码 */
    private final ResultCode resultCode;

    /**
     * 业务异常（使用状态码默认提示语）
     *
     * @param resultCode 状态码
     */
    public BizException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.resultCode = resultCode;
    }

    /**
     * 业务异常（自定义提示语）
     *
     * @param resultCode 状态码
     * @param message    自定义提示信息
     */
    public BizException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

    public ResultCode getResultCode() {
        return resultCode;
    }
}

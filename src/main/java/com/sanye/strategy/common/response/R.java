package com.sanye.strategy.common.response;

import java.io.Serializable;
import java.time.Instant;

/**
 * <p>
 * 统一响应包装
 * </p>
 * <p>
 * 所有 Controller 接口返回值必须使用此类包装。
 * 状态码统一取 {@link ResultCode}，禁止散落魔法数字；
 * timestamp 使用 JDK8 Instant API 生成 ISO-8601 标准时间字符串。
 * </p>
 *
 * @param <T> 响应数据类型
 * @author 31372
 */
public class R<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 状态码 */
    private int code;

    /** 提示信息 */
    private String message;

    /** 响应数据 */
    private T data;

    /** 响应时间，ISO-8601 格式 */
    private String timestamp;

    private R(ResultCode resultCode, String message, T data) {
        this.code = resultCode.getCode();
        this.message = message;
        this.data = data;
        this.timestamp = Instant.now().toString();
    }

    /**
     * 成功响应（带数据）
     *
     * @param <T>  数据类型
     * @param data 响应数据
     * @return R 实例
     */
    public static <T> R<T> ok(T data) {
        return new R<>(ResultCode.SUCCESS, ResultCode.SUCCESS.getMessage(), data);
    }

    /**
     * 成功响应（无数据）
     *
     * @param <T> 数据类型
     * @return R 实例
     */
    public static <T> R<T> ok() {
        return new R<>(ResultCode.SUCCESS, ResultCode.SUCCESS.getMessage(), null);
    }

    /**
     * 失败响应（使用状态码默认提示语）
     *
     * @param <T>        数据类型
     * @param resultCode 状态码
     * @return R 实例
     */
    public static <T> R<T> fail(ResultCode resultCode) {
        return new R<>(resultCode, resultCode.getMessage(), null);
    }

    /**
     * 失败响应（自定义提示语）
     *
     * @param <T>        数据类型
     * @param resultCode 状态码
     * @param message    自定义提示信息
     * @return R 实例
     */
    public static <T> R<T> fail(ResultCode resultCode, String message) {
        return new R<>(resultCode, message, null);
    }

    /**
     * 失败响应（自定义提示语 + 数据载荷）
     *
     * @param <T>        数据类型
     * @param resultCode 状态码
     * @param message    自定义提示信息
     * @param data       响应数据（如 MFA 挑战凭证）
     * @return R 实例
     */
    public static <T> R<T> fail(ResultCode resultCode, String message, T data) {
        return new R<>(resultCode, message, data);
    }

    // ==================== Getter / Setter ====================

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
}

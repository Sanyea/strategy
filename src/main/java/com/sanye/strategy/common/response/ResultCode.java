package com.sanye.strategy.common.response;

/**
 * <p>
 * 统一响应状态码枚举
 * </p>
 * <p>
 * 状态码与 HTTP 语义对齐，便于网关/监控/前端按码段统一处理：
 * <ul>
 *   <li>2xx — 成功</li>
 *   <li>4xx — 客户端错误（参数、认证、权限、资源不存在等，调用方可修正）</li>
 *   <li>5xx — 服务端错误（内部异常、服务不可用，调用方不可修正）</li>
 * </ul>
 * 新增业务码时按码段在此扩展，禁止在业务代码里散落魔法数字。
 * </p>
 *
 * @author 31372
 */
public enum ResultCode {

    // ==================== 成功（2xx） ====================

    /** 操作成功 */
    SUCCESS(200, "操作成功"),

    // ==================== 客户端错误（4xx） ====================

    /** 请求参数错误（参数缺失、格式非法、校验失败） */
    BAD_REQUEST(400, "请求参数错误"),

    /** 未认证或登录已过期 */
    UNAUTHORIZED(401, "未认证或登录已过期"),

    /** 无权限访问 */
    FORBIDDEN(403, "无权限访问"),

    /** 资源不存在 */
    NOT_FOUND(404, "资源不存在"),

    /** 请求方法不支持 */
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),

    /** 资源冲突（如唯一键冲突、状态不允许该操作） */
    CONFLICT(409, "资源冲突"),

    /** 请求体超出大小限制（文件上传等） */
    PAYLOAD_TOO_LARGE(413, "文件大小超出限制"),

    /** 不支持的媒体类型 */
    UNSUPPORTED_MEDIA_TYPE(415, "不支持的媒体类型"),

    // ==================== 服务端错误（5xx） ====================

    /** 系统内部错误 */
    INTERNAL_ERROR(500, "系统繁忙，请稍后重试"),

    /** 服务暂不可用 */
    SERVICE_UNAVAILABLE(503, "服务暂不可用");

    /** 状态码 */
    private final int code;

    /** 默认提示信息 */
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    /**
     * 是否成功（2xx）
     *
     * @return true 表示成功
     */
    public boolean isSuccess() {
        return code >= 200 && code < 300;
    }

    /**
     * 是否客户端错误（4xx）
     *
     * @return true 表示调用方可修正的错误
     */
    public boolean isClientError() {
        return code >= 400 && code < 500;
    }

    /**
     * 是否服务端错误（5xx）
     *
     * @return true 表示调用方不可修正的错误
     */
    public boolean isServerError() {
        return code >= 500 && code < 600;
    }

    /**
     * 按状态码查找枚举
     *
     * @param code 状态码
     * @return 对应枚举，未匹配返回 null
     */
    public static ResultCode of(int code) {
        for (ResultCode resultCode : values()) {
            if (resultCode.code == code) {
                return resultCode;
            }
        }
        return null;
    }
}

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

    /** 会话已过期或无效（登录态失效） */
    TOKEN_EXPIRED(401, "登录已过期，请重新登录"),

    /** 设备已下线（被踢）——本批仅注册码，批4 踢设备流程使用 */
    DEVICE_KICKED(401, "账号已在其他设备登录，请重新登录"),

    /** MFA 挑战凭证失效（过期/已消费/跨设备复用）——verify GETDEL 消费失败 */
    MFA_CHALLENGE_EXPIRED(401, "二次验证凭证已失效，请重新登录"),

    /** 无权限访问 */
    FORBIDDEN(403, "无权限访问"),

    /** 账号锁定中（防爆破锁定） */
    ACCOUNT_LOCKED(403, "账号已锁定，请稍后再试"),

    /** 账号已冻结 */
    ACCOUNT_DISABLED(403, "账号已冻结，请联系管理员"),

    /** 需二次验证（MFA 未通过） */
    MFA_REQUIRED(403, "请完成二次验证"),

    /** 资源不存在 */
    NOT_FOUND(404, "资源不存在"),

    /** 请求方法不支持 */
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),

    /** 资源冲突（如唯一键冲突、状态不允许该操作） */
    CONFLICT(409, "资源冲突"),

    /** 账号已注销（终态） */
    ACCOUNT_DELETED(410, "账号已注销"),

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

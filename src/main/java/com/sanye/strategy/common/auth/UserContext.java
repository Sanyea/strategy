package com.sanye.strategy.common.auth;

import com.sanye.strategy.enums.UserTypeEnum;

/**
 * <p>
 * 当前登录用户上下文 — ThreadLocal 存储
 * </p>
 * <p>
 * 由 {@code TokenAuthInterceptor} 在认证通过后填充，业务层经 {@link #get()} 取操作人；
 * 请求结束时（拦截器 {@code afterCompletion}）必须 {@link #clear()}，防止线程池复用导致上下文泄漏。
 * 无上下文时 {@link #get()} 返回 null，调用方按未登录处理或落库 NULL（后台脚本场景）。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：请求级会话载体，跨 Controller/Service 传递当前用户。</li>
 *   <li>优缺点：免逐方法传参、免查询 DB；缺点：ThreadLocal 需严格配对清除，泄漏会串号——以拦截器收口清除。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
public class UserContext {

    private static final ThreadLocal<UserContext> HOLDER = new ThreadLocal<>();

    /** 用户ID */
    private final Long userId;

    /** 用户类型 */
    private final UserTypeEnum userType;

    /** 会话行 ID（jti，吊销黑名单键） */
    private final Long jti;

    /** 设备 ID */
    private final String deviceId;

    public UserContext(Long userId, UserTypeEnum userType, Long jti, String deviceId) {
        this.userId = userId;
        this.userType = userType;
        this.jti = jti;
        this.deviceId = deviceId;
    }

    public static void set(UserContext context) {
        HOLDER.set(context);
    }

    public static UserContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public Long getUserId() {
        return userId;
    }

    public UserTypeEnum getUserType() {
        return userType;
    }

    public Long getJti() {
        return jti;
    }

    public String getDeviceId() {
        return deviceId;
    }
}

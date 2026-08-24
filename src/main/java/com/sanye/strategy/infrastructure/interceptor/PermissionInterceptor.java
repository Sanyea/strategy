package com.sanye.strategy.infrastructure.interceptor;

import com.sanye.strategy.common.exception.BizException;
import com.sanye.strategy.common.response.ResultCode;
import com.sanye.strategy.common.util.IpUtils;
import com.sanye.strategy.infrastructure.logging.SecurityEventLogger;
import com.sanye.strategy.infrastructure.security.NoPermissionRequired;
import com.sanye.strategy.infrastructure.security.RequiresPermission;
import com.sanye.strategy.infrastructure.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 权限拦截器 — 责任链一环（认证之后）
 * <pre>
 * HandlerMethod? → @NoPermissionRequired? → @RequiresPermission? → SUPER_ADMIN? → perms claim 比对 → 放行/403
 * /rbac/** 无注解默认 403（防管理接口漏标注解）
 * </pre>
 * 零 DB 查询（perms 为 JWT 快照）。角色：Handler（认证后鉴权节点）；优点：接口级鉴权收口一处、无 DB 压力；
 * 缺点：perms 快照最长 30min 滞后，变更需踢人（RbacManageService 自动 evict 兜底）。
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private static final String RBAC_PATH_PREFIX = "/rbac/";
    private static final String SUPER_ADMIN = "SUPER_ADMIN";

    private final SecurityEventLogger securityEventLogger;

    public PermissionInterceptor(SecurityEventLogger securityEventLogger) {
        this.securityEventLogger = securityEventLogger;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) { return true; }
        if (method.getMethodAnnotation(NoPermissionRequired.class) != null
                || method.getBeanType().getAnnotation(NoPermissionRequired.class) != null) { return true; }

        RequiresPermission requires = method.getMethodAnnotation(RequiresPermission.class);
        if (requires == null) { requires = method.getBeanType().getAnnotation(RequiresPermission.class); }

        if (requires == null) {
            String uri = request.getRequestURI();
            if (uri != null && uri.startsWith(RBAC_PATH_PREFIX)) {
                securityEventLogger.log("authz", "anonymous", IpUtils.getClientIp(request), "DENY", "接口未配置权限点 uri=" + uri);
                throw new BizException(ResultCode.FORBIDDEN, "接口未配置权限点");
            }
            return true;
        }
        UserContext ctx = UserContext.get();
        if (ctx == null) {
            securityEventLogger.log("authz", "anonymous", IpUtils.getClientIp(request), "DENY", "无用户上下文访问受保护接口");
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        if (ctx.getRoleCodes().contains(SUPER_ADMIN)) { return true; }
        if (ctx.getPermCodes().contains(requires.value())) { return true; }
        securityEventLogger.log("authz", String.valueOf(ctx.getUserId()), IpUtils.getClientIp(request), "DENY", "权限不足 perm=" + requires.value());
        throw new BizException(ResultCode.FORBIDDEN);
    }
}

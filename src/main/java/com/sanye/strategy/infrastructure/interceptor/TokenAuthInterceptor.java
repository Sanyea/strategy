package com.sanye.strategy.infrastructure.interceptor;

import com.sanye.strategy.infrastructure.redis.JtiBlacklistService;
import com.sanye.strategy.infrastructure.security.JwtUtil;
import com.sanye.strategy.infrastructure.security.UserContext;
import com.sanye.strategy.common.exception.BizException;
import com.sanye.strategy.common.response.ResultCode;
import com.sanye.strategy.common.util.IpUtils;
import com.sanye.strategy.infrastructure.logging.SecurityEventLogger;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * 认证管道拦截器 — 责任链（Chain of Responsibility）一环
 * </p>
 * <p>
 * 白名单路径由 {@code WebMvcConfig} 注册排除，拦截器只处理需登录请求：
 * <pre>
 * Bearer accessToken → 验签 + type=ACCESS 校验 + jti 黑名单 EXISTS → 填充 UserContext → 放行
 * 任一失败 → 抛 {@link BizException}（401/403），由 {@code GlobalExceptionHandler} 转 R + HTTP 状态
 * </pre>
 * 不做逐请求 userStatus 查询（请求不打库）：冻结/注销在签发时把关，即时吊销走 jti 黑名单（秒级）。
 * </p>
 * <p>
 * 设计说明（责任链）：
 * <ul>
 *   <li>角色：Handler，链上单节（白名单放行→Token 解析→状态校验→UserContext 填充）；
 *       Client 为 Spring MVC 请求分派；处理器出口为 GlobalExceptionHandler。</li>
 *   <li>优点：认证与业务解耦，白名单集中维护，后续加日志/限流拦截器即挂新链节。</li>
 *   <li>缺点：对 Controller 参数/返回值不可见（需参数注入则补 ArgumentResolver，本期不做）；
 *       黑名单命中统一 {@code TOKEN_EXPIRED}，{@code DEVICE_KICKED} 由批4 踢设备流程细化。</li>
 * </ul>
 * </p>
 * <p>
 * UML 时序图：
 * <pre class="mermaid">
 * sequenceDiagram
 *     participant C as Client
 *     participant I as TokenAuthInterceptor
 *     participant J as JwtUtil
 *     participant B as JtiBlacklistService
 *     participant CT as Controller
 *     C->>I: HTTP 请求（Authorization: Bearer token）
 *     I->>J: parseToken(token)
 *     J-->>I: Claims
 *     I->>I: type=ACCESS 校验
 *     I->>B: isRevoked(jti)
 *     B-->>I: false
 *     I->>I: UserContext.set(userId, roles, perms, jti, deviceId)
 *     I-->>CT: true（放行）
 *     CT-->>I: 响应返回
 *     I->>I: afterCompletion → UserContext.clear()
 * </pre>
 * </p>
 *
 * @author 31372
 */
@Component
@RequiredArgsConstructor
public class TokenAuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String TYPE_ACCESS = "ACCESS";

    private final JwtUtil jwtUtil;
    private final JtiBlacklistService jtiBlacklistService;
    private final SecurityEventLogger securityEventLogger;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith(BEARER_PREFIX)) {
            securityEventLogger.log("authn", "anonymous", IpUtils.getClientIp(request), "FAIL", "缺失或非法 Authorization 头");
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        try {
            Claims claims = jwtUtil.parseToken(token);
            if (!TYPE_ACCESS.equals(claims.get("type", String.class))) {
                securityEventLogger.log("authn", "anonymous", IpUtils.getClientIp(request), "FAIL", "token 类型非 ACCESS");
                throw new BizException(ResultCode.UNAUTHORIZED);
            }
            // jti 按 RFC 7519 为 String（JwtUtil 以 String.valueOf(jti) 落串），还原会话行 ID
            Long jti = Long.valueOf(claims.get("jti", String.class));
            if (jtiBlacklistService.isRevoked(jti)) {
                securityEventLogger.log("authn", String.valueOf(jti), IpUtils.getClientIp(request), "FAIL", "jti 黑名单命中");
                throw new BizException(ResultCode.TOKEN_EXPIRED, "登录已失效，请重新登录");
            }
            Long userId = claims.get("userId", Number.class).longValue();
            List<String> roleCodes = parseRolesClaim(claims);
            List<String> permCodes = parsePermsClaim(claims);
            String deviceId = claims.get("deviceId", String.class);
            UserContext.set(new UserContext(userId, roleCodes, permCodes, jti, deviceId));
            return true;
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            // 验签失败 / 算法不符 / 过期 / 签名合法但 claim 缺失或类型不符（非数值 jti、缺 userId 等）
            // 统一按 401 收敛，不落 500
            securityEventLogger.log("authn", "anonymous", IpUtils.getClientIp(request), "FAIL", "token 校验失败");
            throw new BizException(ResultCode.UNAUTHORIZED, "登录已过期，请重新登录");
        }
    }

    /**
     * 解析 roles claim（角色码数组，快照）
     * <p>claim 缺失/非 List/元素非字符串一律防御为合法空角色（不抛异常，放行但无权），
     * 非法结构仅代表旧 token 或篡改，交由业务鉴权拒绝，而非认证层 500。</p>
     */
    @SuppressWarnings("unchecked")
    private List<String> parseRolesClaim(Claims claims) {
        Object roles = claims.get("roles");
        if (!(roles instanceof List)) {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        for (Object item : (List<Object>) roles) {
            if (item instanceof String s && !s.isBlank()) {
                codes.add(s);
            }
        }
        return codes;
    }

    /**
     * 解析 perms claim（权限码数组，快照）
     * <p>模式同 {@link #parseRolesClaim}：claim 缺失/非 List/元素非字符串一律防御为合法空列表
     * （不抛异常，放行但无权），非法结构仅代表旧 token 或篡改，交由接口鉴权拒绝，而非认证层 500。</p>
     */
    @SuppressWarnings("unchecked")
    private List<String> parsePermsClaim(Claims claims) {
        Object perms = claims.get("perms");
        if (!(perms instanceof List)) {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        for (Object item : (List<Object>) perms) {
            if (item instanceof String s && !s.isBlank()) {
                codes.add(s);
            }
        }
        return codes;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }
}

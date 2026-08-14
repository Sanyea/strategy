package com.sanye.strategy.infrastructure.interceptor;

import com.sanye.strategy.common.exception.BizException;
import com.sanye.strategy.infrastructure.security.NoPermissionRequired;
import com.sanye.strategy.infrastructure.security.RequiresPermission;
import com.sanye.strategy.infrastructure.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.springframework.web.method.HandlerMethod;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PermissionInterceptorTest {
    private PermissionInterceptor interceptor;

    @BeforeEach void setUp() {
        interceptor = new PermissionInterceptor();
        UserContext.clear();
    }
    @AfterEach void tearDown() { UserContext.clear(); }

    private UserContext ctx(List<String> roles, List<String> perms) {
        return new UserContext(1L, roles, perms, 111L, "dev1");
    }

    @Test void superAdminBypasses() {
        UserContext.set(ctx(List.of("SUPER_ADMIN"), List.of()));
        assertTrue(interceptor.preHandle(mockReq("/rbac/roles"), null, handlerWith("system:role:manage")));
    }
    @Test void permitsWhenClaimContainsCode() {
        UserContext.set(ctx(List.of("OPERATOR"), List.of("system:role:manage")));
        assertTrue(interceptor.preHandle(mockReq("/rbac/roles"), null, handlerWith("system:role:manage")));
    }
    @Test void forbidsWhenClaimMissingCode() {
        UserContext.set(ctx(List.of("OPERATOR"), List.of("system:permission:manage")));
        assertThrows(BizException.class,
                () -> interceptor.preHandle(mockReq("/rbac/roles"), null, handlerWith("system:role:manage")));
    }
    @Test void permitsWhenNoAnnotationOnNonRbacPath() {
        UserContext.set(ctx(List.of("OPERATOR"), List.of()));
        assertTrue(interceptor.preHandle(mockReq("/auth/logout"), null, handlerWithout()));
    }
    @Test void rbacPathWithoutAnnotationIsForbidden() {
        UserContext.set(ctx(List.of("OPERATOR"), List.of()));
        assertThrows(BizException.class,
                () -> interceptor.preHandle(mockReq("/rbac/roles"), null, handlerWithout()));
    }
    @Test void noPermissionRequiredSkipsEvenOnRbacPath() {
        UserContext.set(ctx(List.of("OPERATOR"), List.of()));
        assertTrue(interceptor.preHandle(mockReq("/rbac/my/permissions"), null, handlerNoPerm()));
    }

    private HttpServletRequest mockReq(String uri) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn(uri);
        return req;
    }
    private HandlerMethod handlerWith(String code) {
        RequiresPermission a = mock(RequiresPermission.class);
        when(a.value()).thenReturn(code);
        HandlerMethod h = mock(HandlerMethod.class);
        when(h.getMethodAnnotation(RequiresPermission.class)).thenReturn(a);
        // 已知 plan 缺陷修复：拦截器先查 NoPermissionRequired（含 getBeanType()），不 stub 将 NPE
        // getBeanType() 返回 Class<?>（Spring 7），Mockito thenReturn 无法匹配通配符捕获，改用 doReturn
        doReturn(PlainController.class).when(h).getBeanType();
        return h;
    }
    private HandlerMethod handlerWithout() {
        HandlerMethod h = mock(HandlerMethod.class);
        when(h.getMethodAnnotation(RequiresPermission.class)).thenReturn(null);
        when(h.getMethodAnnotation(NoPermissionRequired.class)).thenReturn(null);
        doReturn(PlainController.class).when(h).getBeanType();
        return h;
    }
    private HandlerMethod handlerNoPerm() {
        HandlerMethod h = mock(HandlerMethod.class);
        when(h.getMethodAnnotation(RequiresPermission.class)).thenReturn(null);
        when(h.getMethodAnnotation(NoPermissionRequired.class)).thenReturn(mock(NoPermissionRequired.class));
        return h;
    }
    static class PlainController {}   // 无任何注解：/rbac 无注解 → 403；非 /rbac → 放行
    @NoPermissionRequired static class WithoutController {}   // 类级豁免（另一用例）
}

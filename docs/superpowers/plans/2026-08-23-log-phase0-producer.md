# 日志系统阶段0（产生端）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地日志系统产生端：Micrometer Tracing 链路 ID + Logback 结构化 JSON 六类分文件 + 产生端脱敏 + 审计增强（trace_id/target/operator_type + change_diff 字段 diff + audit.log WORM 权威链产生端）+ 认证域安全事件埋点。

**Architecture:** 业务只写本地文件（Logback JSON），不直写 ES/MinIO；脱敏产生端完成（字段级 ValueMasker 主防线）；审计双写——`ums_oper_log` 视图副本（MySQL）+ `audit.log` 结构化文件（阶段1 经 Vector → MinIO Object Lock 成 WORM 权威链）；字段 diff 门面就地完成（`DiffUtils` 纯 POJO diff + 关联集 diff，`change_diff` 落 JSON 字符串）；安全事件经独立 logger（`SECURITY`）落 `security.log`，IP 分级策略产生端完成。

**Tech Stack:** Java 21、Spring Boot 4.1.0、Logback（Boot 管理版本 1.5.x）、`net.logstash.logback:logstash-logback-encoder:9.0`（Jackson 3，匹配 Boot 4 默认 Jackson）、`micrometer-tracing-bridge-brave`（Boot BOM 管理版本）、MyBatis-Plus 3.5.15、JUnit 5 + Mockito。

**规格依据:** `docs/superpowers/specs/2026-08-17-log-system-design.md`（第五/六/七章 + 第十章阶段0 + 附录字段 diff 范围）。
**范围决策:** 仅阶段0（产生端）；**字段 diff 复活（2026-08-23 用户决策，推翻评审处置决策 1）**——`ums_oper_log` 增 `change_diff` 列 + `DiffUtils` + RBAC 管理面 9 类 UPDATE + 权限集 GRANT/REVOKE 门面就地 diff 随阶段0 一并落地（本计划 Task 8/9）。中间件轨慢查询埋点、Vector 采集、ES/MinIO 落库为阶段1+，不在本计划。

> ⚠️ **状态（2026-08-24）**：本计划已扩充 diff 任务（Task 8 DiffUtils + Task 9 门面就地 diff），「待扩充不得执行」注记移除，可按本计划执行。范围含：产生端全量 + 审计字段 diff。

## Global Constraints

- **分支**：所有开发与提交仅在 `AI` 分支；commit 格式 `[类型] 描述`；禁止提交 dev/main。
- **依赖**：`logstash-logback-encoder` 钉死 **9.0**（Jackson 3 / `tools.jackson.*` 命名空间，匹配 Boot 4；8.x 是 Jackson 2 会冲突）；`micrometer-tracing-bridge-brave` 不写版本（Boot BOM 管理）。依赖解析失败即停，禁止编造坐标。
- **敏感信息**：dev 凭据不新增入库；本计划不引入任何新凭据配置。
- **注释规范**：单行注释在被注释语句上方另起一行；禁止行尾注释；禁止 `@Deprecated` API。
- **类 javadoc**：新增类必须含「角色 + 优缺点」设计说明（CLAUDE.md 核心约束 2）。
- **构建命令**：`& "D:\Tool\apache-maven-3.8.8\bin\mvn" clean package -DskipTests`（PowerShell 调用语法）；测试 `& "D:\Tool\apache-maven-3.8.8\bin\mvn" test`。
- **铁律**（规格 1.3）：业务只写本地文件，不阻塞、不直写 ES/MinIO；脱敏产生端为主，落盘即安全；错误 100% 全量。
- **反模式禁止**（规格第九章）：禁止把脱敏全部交给传输端；禁止安全事件全部 IP 掩码；禁止审计敏感字段清单与日志脱敏配置分离（本计划共用 `SensitiveFields` 单一源，`DiffUtils` 与 ValueMasker 共用同一份）。
- **diff 语义**（CLAUDE.md + 规格 7.1/7.5）：只报 new 非 null 且与 old 不同的字段；凭据类字段（`SensitiveFields.isCredential`）值永不出现、记 `{"field":X,"op":"changed"}` 占位；PII 类字段（`isPii`）掩码保统计；`change_diff` 存 JSON 字符串（规避 Boot4 Jackson 3 与 MP Jackson 2 冲突，规格 7.1）。

## 文件结构

| 操作 | 文件 | 职责 |
|------|------|------|
| Modify | `pom.xml` | + logstash-logback-encoder 9.0、micrometer-tracing-bridge-brave |
| Modify | `src/main/resources/application.yaml` | tracing 采样配置 + 日志目录属性 |
| Create | `src/main/resources/logback-spring.xml` | 6 类分文件 JSON appender + 脱敏装饰器 + 分类路由 |
| Create | `src/main/java/com/sanye/strategy/common/util/IpMaskUtils.java` | IP 末段掩码（IPv4/IPv6） |
| Create | `src/main/java/com/sanye/strategy/infrastructure/logging/SensitiveFields.java` | 敏感字段单一源（凭据后缀 + PII 字段名） |
| Create | `src/main/java/com/sanye/strategy/infrastructure/logging/CredentialValueMasker.java` | 凭据字段剔除（`{"field":X,"op":"changed"}` 占位）+ 静态 `placeholder` 供 DiffUtils 复用 |
| Create | `src/main/java/com/sanye/strategy/infrastructure/logging/PiiValueMasker.java` | PII 字段掩码（phone/email/idCard 保统计）+ 静态 `maskValue` 供 DiffUtils 复用 |
| Create | `src/main/java/com/sanye/strategy/infrastructure/logging/DiffUtils.java` | 审计字段 diff：纯 POJO diff + 关联集 diff + JSON 序列化（规格 7.1） |
| Create | `src/main/java/com/sanye/strategy/infrastructure/logging/SecurityEventLogger.java` | 安全事件结构化输出 + IP 分级策略 |
| Create | `src/main/java/com/sanye/strategy/infrastructure/logging/AccessLogFilter.java` | 接入访问轨：请求一行日志（IP 末段掩码） |
| Create | `src/main/java/com/sanye/strategy/infrastructure/config/LoggingBeanConfig.java` | AccessLogFilter 注册 Bean |
| Modify | `sql/oper_log.sql` | + trace_id / target_entity / target_id / operator_type / change_diff 五列 |
| Modify | `src/main/java/com/sanye/strategy/infrastructure/persistence/po/UmsOperLogPO.java` | + 五字段（含 changeDiff） |
| Modify | `src/main/resources/mapper/UmsOperLogMapper.xml` | resultMap + Base_Column_List 补五列 |
| Modify | `src/main/java/com/sanye/strategy/application/rbac/OperLogReq.java` | + targetEntity / targetId / changeDiff |
| Modify | `src/main/java/com/sanye/strategy/application/rbac/OperLogService.java` | trace_id/operator_type/target/change_diff 填充 + audit.log 双写 |
| Modify | `src/main/java/com/sanye/strategy/domain/user/repository/UmsUserRoleService.java` | + `findByUserIdAndRoleId` 契约（renewUserRole diff 前置） |
| Modify | `src/main/java/com/sanye/strategy/infrastructure/persistence/impl/UmsUserRoleServiceImpl.java` | + `findByUserIdAndRoleId` 实现 |
| Modify | `src/main/java/com/sanye/strategy/infrastructure/persistence/mapper/UmsUserRoleMapper.java` | + `selectByUserRole` 查询 |
| Modify | `src/main/resources/mapper/UmsUserRoleMapper.xml` | + `selectByUserRole` SQL |
| Modify | `src/main/java/com/sanye/strategy/application/rbac/RbacManageService.java` | 审计调用点补 target + 9 类 UPDATE 与 GRANT/REVOKE 就地 diff |
| Modify | `src/main/java/com/sanye/strategy/application/auth/AuthService.java` | 安全事件埋点（authn/account） |
| Modify | `src/main/java/com/sanye/strategy/infrastructure/interceptor/TokenAuthInterceptor.java` | token 校验失败安全事件（authn） |
| Modify | `src/main/java/com/sanye/strategy/infrastructure/interceptor/PermissionInterceptor.java` | 越权 403 安全事件（authz，完整 IP） |
| Modify | `CLAUDE.md` | 阶段0 状态行更新（收尾） |
| Test | `src/test/java/com/sanye/strategy/common/util/IpMaskUtilsTest.java` | 掩码规则 |
| Test | `src/test/java/com/sanye/strategy/infrastructure/logging/CredentialValueMaskerTest.java` | 凭据剔除 |
| Test | `src/test/java/com/sanye/strategy/infrastructure/logging/PiiValueMaskerTest.java` | PII 掩码 |
| Test | `src/test/java/com/sanye/strategy/infrastructure/logging/DiffUtilsTest.java` | POJO diff / 关联集 diff / JSON 序列化 |
| Test | `src/test/java/com/sanye/strategy/infrastructure/logging/SecurityEventLoggerTest.java` | IP 分级 + kv 结构 |
| Test | `src/test/java/com/sanye/strategy/application/rbac/OperLogServiceTest.java` | trace_id/operator_type/target/change_diff/降级 |
| Test | `src/test/java/com/sanye/strategy/application/rbac/RbacManageServiceTest.java` | diff 断言（updateRoleStatus/renewUserRole 等） |

依赖顺序：Task 1 → Task 2/3（并行）→ Task 4（依赖 2/3）→ Task 5 → Task 6 → Task 7（依赖 6）→ Task 8（依赖 3）→ Task 9（依赖 7/8）→ Task 10（依赖 9）→ Task 11（依赖 10）→ Task 12（收尾冒烟）。

---

### Task 1: 依赖接入（logstash-logback-encoder 9.0 + Micrometer Tracing Brave 桥）

**Files:**
- Modify: `pom.xml`（`</dependencies>` 前，springdoc 依赖块之后）

**Interfaces:**
- Produces: classpath 上存在 `net.logstash.logback.encoder.LogstashEncoder`、`net.logstash.logback.mask.MaskingJsonGeneratorDecorator`、`net.logstash.logback.mask.ValueMasker`（9.0 签名：`Object mask(tools.jackson.core.TokenStreamContext, Object)`）；Brave tracer 自动装配（MDC 注入 `traceId`/`spanId`）。

- [ ] **Step 1: 添加依赖**

在 `pom.xml` 的 springdoc 依赖块之后、`</dependencies>` 之前插入：

```xml
		<!-- 日志结构化：JSON 编码 + 字段级脱敏（9.0 = Jackson 3/tools.jackson，匹配 Boot 4 默认 Jackson；
		     勿用 8.x——Jackson 2 命名空间与 Boot 4 冲突） -->
		<dependency>
			<groupId>net.logstash.logback</groupId>
			<artifactId>logstash-logback-encoder</artifactId>
			<version>9.0</version>
		</dependency>

		<!-- 链路追踪：Micrometer Tracing Brave 桥（版本由 Boot BOM 管理）——traceId/spanId 自动入 MDC，
		     W3C traceparent 传播；无 Zipkin/OTLP 后端依赖（阶段0 只要 traceId 入日志） -->
		<dependency>
			<groupId>io.micrometer</groupId>
			<artifactId>micrometer-tracing-bridge-brave</artifactId>
		</dependency>
```

- [ ] **Step 2: 验证依赖解析与编译**

Run: `& "D:\Tool\apache-maven-3.8.8\bin\mvn" clean compile -q`
Expected: `BUILD SUCCESS`（退出码 0，无 ERROR）。
若 `micrometer-tracing-bridge-brave` 解析失败（Boot 4 BOM 移除该坐标），**停下**改用 `spring-boot-starter-opentelemetry`（Boot 4 官方 starter），并同步修订本计划后续 tracing 相关步骤——不得猜测其它坐标。

- [ ] **Step 3: Commit**

```powershell
git add pom.xml
git commit -m "[feat] 日志阶段0：接入 logstash-logback-encoder 9.0 与 Micrometer Tracing Brave 桥"
```

---

### Task 2: IP 末段掩码工具（IpMaskUtils）

**Files:**
- Create: `src/main/java/com/sanye/strategy/common/util/IpMaskUtils.java`
- Test: `src/test/java/com/sanye/strategy/common/util/IpMaskUtilsTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `IpMaskUtils.maskLastSegment(String ip): String` —— IPv4 末段替换 `***`（`192.168.1.***`）；IPv6 保留前 3 组 + `::***`（规格待决事项 4 的 interim 规则，定稿后只改本方法）；null/空白/无法解析原样返回。供 Task 4（AccessLogFilter）、Task 10（SecurityEventLogger 普通事件）使用。

- [ ] **Step 1: 写失败测试**

```java
package com.sanye.strategy.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * IpMaskUtils 单测 — IPv4 末段掩码 / IPv6 interim 规则 / 边界原样返回
 */
class IpMaskUtilsTest {

    @Test
    void masksIpv4LastSegment() {
        assertEquals("192.168.1.***", IpMaskUtils.maskLastSegment("192.168.1.100"));
    }

    @Test
    void masksIpv6KeepingFirstThreeGroups() {
        assertEquals("2001:db8:abcd::***",
                IpMaskUtils.maskLastSegment("2001:db8:abcd:1234::1"));
    }

    @Test
    void returnsNullOrBlankAsIs() {
        assertNull(IpMaskUtils.maskLastSegment(null));
        assertEquals("", IpMaskUtils.maskLastSegment(""));
        assertEquals("   ", IpMaskUtils.maskLastSegment("   "));
    }

    @Test
    void returnsUnparseableAsIs() {
        assertEquals("not-an-ip", IpMaskUtils.maskLastSegment("not-an-ip"));
        assertEquals("10.0.0", IpMaskUtils.maskLastSegment("10.0.0"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `& "D:\Tool\apache-maven-3.8.8\bin\mvn" test "-Dtest=IpMaskUtilsTest" -q`
Expected: 编译失败（`IpMaskUtils` 不存在）。

- [ ] **Step 3: 实现**

```java
package com.sanye.strategy.common.util;

/**
 * <p>
 * IP 掩码工具 — 产生端 IP 分级策略的「末段掩码」实现
 * </p>
 * <p>
 * 规格 6.3：普通登录事件与请求轨接入访问的 IP 末段掩码（保留网段抹主机位，兼顾统计与隐私）。
 * 高威胁安全事件不走本工具（完整 IP 保留，见 {@code SecurityEventLogger}）。
 * IPv6 掩码位数为规格待决事项 4，当前 interim 规则：保留前 3 组（/48）+ {@code ::***}，
 * 定稿后只改本类。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：纯函数工具，供 AccessLogFilter / SecurityEventLogger 产生端掩码。</li>
 *   <li>优缺点：无状态零依赖、规则收口一处；代价为不引入 IP 解析库，
 *       无法解析的串原样返回（宁可少掩不误删，兜底靠传输端 PII 正则，阶段1）。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
public final class IpMaskUtils {

    private static final String IPV4_MASK_SUFFIX = "***";
    private static final int IPV4_SEGMENT_COUNT = 4;
    private static final int IPV6_KEEP_GROUPS = 3;

    private IpMaskUtils() {
    }

    /**
     * 末段掩码：IPv4 {@code a.b.c.***}；IPv6 保留前 3 组 + {@code ::***}；
     * null/空白/无法解析原样返回
     *
     * @param ip 原始 IP
     * @return 掩码后 IP
     */
    public static String maskLastSegment(String ip) {
        if (ip == null || ip.isBlank()) {
            return ip;
        }
        if (ip.contains(":")) {
            return maskIpv6(ip);
        }
        return maskIpv4(ip);
    }

    private static String maskIpv4(String ip) {
        String[] segments = ip.split("\\.");
        if (segments.length != IPV4_SEGMENT_COUNT) {
            return ip;
        }
        for (String segment : segments) {
            if (!isDigits(segment)) {
                return ip;
            }
        }
        return segments[0] + "." + segments[1] + "." + segments[2] + "." + IPV4_MASK_SUFFIX;
    }

    private static String maskIpv6(String ip) {
        String[] groups = ip.split(":");
        if (groups.length < IPV6_KEEP_GROUPS + 1) {
            return ip;
        }
        return groups[0] + ":" + groups[1] + ":" + groups[2] + "::***";
    }

    private static boolean isDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `& "D:\Tool\apache-maven-3.8.8\bin\mvn" test "-Dtest=IpMaskUtilsTest" -q`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/sanye/strategy/common/util/IpMaskUtils.java src/test/java/com/sanye/strategy/common/util/IpMaskUtilsTest.java
git commit -m "[feat] 日志阶段0：IP 末段掩码工具（IPv4/IPv6 interim 规则）"
```

---

### Task 3: 敏感字段脱敏框架（单一源 + 凭据剔除 + PII 掩码）

**Files:**
- Create: `src/main/java/com/sanye/strategy/infrastructure/logging/SensitiveFields.java`
- Create: `src/main/java/com/sanye/strategy/infrastructure/logging/CredentialValueMasker.java`
- Create: `src/main/java/com/sanye/strategy/infrastructure/logging/PiiValueMasker.java`
- Test: `src/test/java/com/sanye/strategy/infrastructure/logging/CredentialValueMaskerTest.java`
- Test: `src/test/java/com/sanye/strategy/infrastructure/logging/PiiValueMaskerTest.java`

**Interfaces:**
- Consumes: Task 1 的 `net.logstash.logback.mask.ValueMasker`（9.0 签名 `Object mask(tools.jackson.core.TokenStreamContext context, Object value)`，返回 null=不掩码）。
- Produces:
  - `SensitiveFields.isCredential(String fieldName): boolean`、`SensitiveFields.isPii(String fieldName): boolean` —— 敏感字段**单一源**（规格 6.4：日志脱敏与审计 diff 共用，禁止另维护清单）；
  - `CredentialValueMasker.placeholder(String fieldName): String` —— 凭据变更占位串（实例 `mask` 与 Task 8 `DiffUtils` 共用）；
  - `PiiValueMasker.maskValue(String fieldName, String text): String` —— 按字段名掩码（非 PII 返回 null，畸形值返回原文；实例 `mask` 与 Task 8 `DiffUtils` 共用）；
  - `CredentialValueMasker` / `PiiValueMasker` —— logback XML 经 `<valueMasker>` 以无参构造实例化（Task 4 引用类全名）。

> ⚠️ 修正：原计划测试以 null context + 裸值调用 `mask`，但 ValueMasker 是**字段名驱动**（`context.getCurrentName()`），null context 下字段名未知、必然不掩码。测试改用 Mockito mock `TokenStreamContext` 提供字段名，见 Step 1/2。若 9.0 实际方法名非 `getCurrentName()`，以 jar 内 API 为准。

- [ ] **Step 1: 写失败测试（CredentialValueMaskerTest）**

```java
package com.sanye.strategy.infrastructure.logging;

import org.junit.jupiter.api.Test;
import tools.jackson.core.TokenStreamContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CredentialValueMasker 单测 — 凭据字段值剔除（changed 占位），非凭据字段放行
 * <p>ValueMasker 字段名经 {@link TokenStreamContext#getCurrentName()} 注入（字段名驱动），
 * 测试用 Mockito mock context 提供字段名；null context（字段名未知）放行。</p>
 */
class CredentialValueMaskerTest {

    private final CredentialValueMasker masker = new CredentialValueMasker();

    private static TokenStreamContext ctx(String fieldName) {
        TokenStreamContext ctx = mock(TokenStreamContext.class);
        when(ctx.getCurrentName()).thenReturn(fieldName);
        return ctx;
    }

    @Test
    void masksCredentialFieldsWithChangedPlaceholder() {
        assertEquals("{\"field\":\"password\",\"op\":\"changed\"}",
                masker.mask(ctx("password"), "P@ssw0rd123"));
        assertEquals("{\"field\":\"refreshTokenHash\",\"op\":\"changed\"}",
                masker.mask(ctx("refreshTokenHash"), "abc123hash"));
        assertEquals("{\"field\":\"mfaSecret\",\"op\":\"changed\"}",
                masker.mask(ctx("mfaSecret"), "JBSWY3DPEHPK3PXP"));
        assertEquals("{\"field\":\"salt\",\"op\":\"changed\"}",
                masker.mask(ctx("salt"), "random-salt"));
    }

    @Test
    void ignoresNonCredentialFields() {
        assertNull(masker.mask(ctx("username"), "user1"));
        assertNull(masker.mask(ctx("roleName"), "运营"));
    }

    @Test
    void ignoresNullValue() {
        assertNull(masker.mask(ctx("password"), null));
    }

    @Test
    void ignoresUnknownFieldNameWithoutContext() {
        assertNull(masker.mask(null, "P@ssw0rd123"));
    }
}
```

- [ ] **Step 2: 写失败测试（PiiValueMaskerTest）**

```java
package com.sanye.strategy.infrastructure.logging;

import org.junit.jupiter.api.Test;
import tools.jackson.core.TokenStreamContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PiiValueMasker 单测 — phone/email/idCard 部分掩码保统计；非 PII 字段与畸形值放行
 * <p>同 CredentialValueMaskerTest：mock context 提供字段名（字段名驱动）。</p>
 */
class PiiValueMaskerTest {

    private final PiiValueMasker masker = new PiiValueMasker();

    private static TokenStreamContext ctx(String fieldName) {
        TokenStreamContext ctx = mock(TokenStreamContext.class);
        when(ctx.getCurrentName()).thenReturn(fieldName);
        return ctx;
    }

    @Test
    void masksPhoneMiddleSegments() {
        assertEquals("138****5678", masker.mask(ctx("phone"), "13812345678"));
    }

    @Test
    void masksEmailKeepingPrefixAndDomain() {
        assertEquals("ab***@example.com", masker.mask(ctx("email"), "abcdef@example.com"));
        // 短前缀（<2 位可保留时）保留全部前缀位
        assertEquals("ab***@x.cn", masker.mask(ctx("email"), "ab@x.cn"));
    }

    @Test
    void masksIdCardKeepingHeadAndTail() {
        assertEquals("110101********1234", masker.mask(ctx("idCard"), "110101199003071234"));
    }

    @Test
    void passesThroughNonPiiFields() {
        assertNull(masker.mask(ctx("username"), "13812345678"));
        assertNull(masker.mask(null, "13812345678"));
    }

    @Test
    void passesThroughMalformedValues() {
        // 畸形值宁可不掩（保可用性），兜底靠传输端 PII 正则（阶段1）
        assertEquals("123", masker.mask(ctx("phone"), "123"));
        assertEquals("short", masker.mask(ctx("email"), "short"));
        assertEquals("no-at-sign", masker.mask(ctx("email"), "no-at-sign"));
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `& "D:\Tool\apache-maven-3.8.8\bin\mvn" test "-Dtest=CredentialValueMaskerTest,PiiValueMaskerTest" -q`
Expected: 编译失败（三个类不存在）。

- [ ] **Step 4: 实现 SensitiveFields（单一源）**

```java
package com.sanye.strategy.infrastructure.logging;

import java.util.Locale;
import java.util.Set;

/**
 * <p>
 * 敏感字段单一源 — 凭据类后缀 + PII 类字段名（规格 6.2/6.4）
 * </p>
 * <p>
 * 日志脱敏（CredentialValueMasker/PiiValueMasker）、审计字段 diff（DiffUtils）共用本清单，
 * 修改一处同时生效，禁止在别处另维护敏感字段清单（规格第九章反模式）。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：受控词表（常量类），脱敏判定唯一依据。</li>
 *   <li>优缺点：后缀匹配覆盖 passwordHash/refreshTokenHash 等组合命名；
 *       代价为误伤面（如业务字段恰以 token 结尾）——误伤时经白名单例外处理（规格 6.4），不改后缀规则。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
public final class SensitiveFields {

    /**
     * 凭据类字段后缀（值剔除语义：password|secret|token|hash|salt，规格 6.2）
     */
    private static final String[] CREDENTIAL_SUFFIXES =
            {"password", "secret", "token", "hash", "salt"};

    /**
     * PII 类字段名（掩码语义：部分显示保统计，规格 6.2）
     */
    private static final Set<String> PII_FIELDS = Set.of("phone", "email", "idcard");

    private SensitiveFields() {
    }

    /**
     * 是否凭据类字段（字段名小写后按后缀匹配）
     *
     * @param fieldName JSON 字段名
     * @return true=凭据类（值剔除）
     */
    public static boolean isCredential(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        String lower = fieldName.toLowerCase(Locale.ROOT);
        for (String suffix : CREDENTIAL_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否 PII 类字段（字段名小写后精确匹配）
     *
     * @param fieldName JSON 字段名
     * @return true=PII 类（部分掩码）
     */
    public static boolean isPii(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        return PII_FIELDS.contains(fieldName.toLowerCase(Locale.ROOT));
    }
}
```

- [ ] **Step 5: 实现 CredentialValueMasker（含静态 placeholder 供 DiffUtils 复用）**

```java
package com.sanye.strategy.infrastructure.logging;

import net.logstash.logback.mask.ValueMasker;
import tools.jackson.core.TokenStreamContext;

/**
 * <p>
 * 凭据字段值剔除器 — logstash-logback-encoder {@link ValueMasker} 实现（规格 6.2/7.5）
 * </p>
 * <p>
 * 凭据字段（{@link SensitiveFields#isCredential}）的值永不出现在日志：
 * 替换为 {@link #placeholder(String)} 占位串——审计「发生」而非「内容」。
 * 由 logback-spring.xml 的 {@code <valueMasker>} 以无参构造实例化；
 * 静态 {@code placeholder} 供 Task 8 {@code DiffUtils} 复用（规格 6.4 单一源）。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：产生端脱敏第一道防线（字段级排除）的凭据分支 + 审计 diff 凭据占位出口。</li>
 *   <li>优缺点：序列化期拦截、落盘即安全；代价为占位串是 String 而非 JSON 对象
 *       （ES 侧按文本检索可接受，换取 ValueMasker 简单返回）。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
public class CredentialValueMasker implements ValueMasker {

    @Override
    public Object mask(TokenStreamContext context, Object value) {
        if (value == null) {
            return null;
        }
        String fieldName = context == null ? null : context.getCurrentName();
        if (!SensitiveFields.isCredential(fieldName)) {
            return null;
        }
        return placeholder(fieldName);
    }

    /**
     * 凭据变更占位串（ValueMasker 与审计 diff 共用，规格 6.4 单一源）
     *
     * @param fieldName 字段名
     * @return {@code {"field":X,"op":"changed"}} 占位串
     */
    public static String placeholder(String fieldName) {
        return "{\"field\":\"" + fieldName + "\",\"op\":\"changed\"}";
    }
}
```

- [ ] **Step 6: 实现 PiiValueMasker（含静态 maskValue 供 DiffUtils 复用）**

```java
package com.sanye.strategy.infrastructure.logging;

import net.logstash.logback.mask.ValueMasker;
import tools.jackson.core.TokenStreamContext;

import java.util.Locale;

/**
 * <p>
 * PII 字段掩码器 — logstash-logback-encoder {@link ValueMasker} 实现（规格 6.2）
 * </p>
 * <p>
 * phone/email/idCard 部分显示保统计：手机号掩中段（138****5678）、邮箱保首 2 位与域名、
 * 身份证保前 6 后 4。畸形值（长度/格式不符）原样放行——宁可不掩不误删，
 * 兜底靠传输端 PII 正则（阶段1，规格 6.1 第二道）。
 * 静态 {@code maskValue} 供 Task 8 {@code DiffUtils} 复用（规格 6.4 单一源）。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：产生端脱敏第一道防线的 PII 分支 + 审计 diff PII 掩码出口。</li>
 *   <li>优缺点：保统计可用性（按网段/域名聚合不受影响）；代价为规则仅覆盖标准形态。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
public class PiiValueMasker implements ValueMasker {

    private static final int PHONE_LENGTH = 11;
    private static final int ID_CARD_LENGTH = 18;
    private static final int EMAIL_MIN_PREFIX_LENGTH = 3;

    @Override
    public Object mask(TokenStreamContext context, Object value) {
        if (!(value instanceof CharSequence chars)) {
            return null;
        }
        String fieldName = context == null ? null : context.getCurrentName();
        return maskValue(fieldName, chars.toString());
    }

    /**
     * 按字段名掩码 PII 值（ValueMasker 与审计 diff 共用，规格 6.4 单一源）；
     * 非 PII 字段返回 null（原样放行），畸形值返回原文（宁可不掩不误删）
     *
     * @param fieldName 字段名
     * @param text      原始文本
     * @return 掩码后文本；非 PII 字段返回 null
     */
    public static String maskValue(String fieldName, String text) {
        if (!SensitiveFields.isPii(fieldName)) {
            return null;
        }
        String lower = fieldName.toLowerCase(Locale.ROOT);
        if ("phone".equals(lower)) {
            return maskPhone(text);
        }
        if ("email".equals(lower)) {
            return maskEmail(text);
        }
        return maskIdCard(text);
    }

    private static String maskPhone(String phone) {
        if (phone.length() != PHONE_LENGTH) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at < 2) {
            return email;
        }
        int keep = Math.min(2, at);
        return email.substring(0, keep) + "***" + email.substring(at);
    }

    private static String maskIdCard(String idCard) {
        if (idCard.length() != ID_CARD_LENGTH) {
            return idCard;
        }
        return idCard.substring(0, 6) + "********" + idCard.substring(14);
    }
}
```

- [ ] **Step 7: 运行确认通过**

Run: `& "D:\Tool\apache-maven-3.8.8\bin\mvn" test "-Dtest=CredentialValueMaskerTest,PiiValueMaskerTest" -q`
Expected: 两个测试类全部 PASS（若 `TokenStreamContext.getCurrentName()` 编译失败，9.0 实际方法名以 jar 内 API 为准，改用等价的路径取末段方法，并在测试注释中记录）。

- [ ] **Step 8: Commit**

```powershell
git add src/main/java/com/sanye/strategy/infrastructure/logging/ src/test/java/com/sanye/strategy/infrastructure/logging/CredentialValueMaskerTest.java src/test/java/com/sanye/strategy/infrastructure/logging/PiiValueMaskerTest.java
git commit -m "[feat] 日志阶段0：敏感字段脱敏框架（单一源 + 凭据剔除 + PII 掩码 + 静态助手）"
```

---

### Task 4: logback-spring.xml（6 类分文件 + 脱敏装饰器 + 接入访问过滤器）

**Files:**
- Create: `src/main/resources/logback-spring.xml`
- Create: `src/main/java/com/sanye/strategy/infrastructure/logging/AccessLogFilter.java`
- Create: `src/main/java/com/sanye/strategy/infrastructure/config/LoggingBeanConfig.java`

**Interfaces:**
- Consumes: Task 3 的 `CredentialValueMasker` / `PiiValueMasker`（XML 以类全名引用）；Task 2 的 `IpMaskUtils.maskLastSegment`。
- Produces: 日志文件（`${LOG_PATH}` 下）：`business.log`（业务轨）、`error.log`（错误，全量）、`security.log`（事件轨，logger 名 `SECURITY`）、`audit.log`（事件轨 WORM 源，logger 名 `AUDIT`）、`access.log`（请求轨，logger 名 `ACCESS`）、`middleware.log`（中间件轨，logger 名 `MIDDLEWARE`，本任务仅建 appender/logger，慢查询埋点后续阶段）。Task 7 的 OperLogService 将用 `LoggerFactory.getLogger("AUDIT")`，Task 10 用 `SECURITY`。

- [ ] **Step 1: 创建 logback-spring.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!--
  日志阶段0 产生端配置（规格 docs/superpowers/specs/2026-08-17-log-system-design.md）：
  - 结构化 JSON（logstash-logback-encoder 9.0）+ 分类分文件（6 类）+ 错误独立
  - 脱敏产生端：MaskingJsonGeneratorDecorator 挂 CredentialValueMasker（凭据剔除）+ PiiValueMasker（PII 掩码）
  - 逻辑三轨·物理一：request-*（ACCESS）/ business-*（root+MIDDLEWARE+ERROR）/ event-*（SECURITY+AUDIT）
  - 业务只写本地文件：AsyncAppender 非阻塞（neverBlock），磁盘滚动清理（maxHistory+totalSizeCap）
-->
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
    <springProperty scope="context" name="APP_NAME" source="spring.application.name" defaultValue="strategy"/>
    <springProperty scope="context" name="LOG_PATH" source="logging.file.path" defaultValue="./logs"/>

    <!-- 分类文件 appender 模板：JSON 编码 + 双 ValueMasker 脱敏 + 按天滚动 + 总量上限 -->
    <appender name="FILE_BUSINESS" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_PATH}/business.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_PATH}/business.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
            <maxFileSize>200MB</maxFileSize>
            <maxHistory>15</maxHistory>
            <totalSizeCap>5GB</totalSizeCap>
        </rollingPolicy>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"app":"${APP_NAME}","category":"business"}</customFields>
            <jsonGeneratorDecorator class="net.logstash.logback.mask.MaskingJsonGeneratorDecorator">
                <valueMasker class="com.sanye.strategy.infrastructure.logging.CredentialValueMasker"/>
                <valueMasker class="com.sanye.strategy.infrastructure.logging.PiiValueMasker"/>
            </jsonGeneratorDecorator>
        </encoder>
    </appender>

    <appender name="FILE_ERROR" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_PATH}/error.log</file>
        <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
            <level>WARN</level>
        </filter>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_PATH}/error.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
            <maxFileSize>200MB</maxFileSize>
            <maxHistory>30</maxHistory>
            <totalSizeCap>5GB</totalSizeCap>
        </rollingPolicy>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"app":"${APP_NAME}","category":"error"}</customFields>
            <jsonGeneratorDecorator class="net.logstash.logback.mask.MaskingJsonGeneratorDecorator">
                <valueMasker class="com.sanye.strategy.infrastructure.logging.CredentialValueMasker"/>
                <valueMasker class="com.sanye.strategy.infrastructure.logging.PiiValueMasker"/>
            </jsonGeneratorDecorator>
        </encoder>
    </appender>

    <appender name="FILE_SECURITY" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_PATH}/security.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_PATH}/security.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
            <maxFileSize>200MB</maxFileSize>
            <maxHistory>30</maxHistory>
            <totalSizeCap>2GB</totalSizeCap>
        </rollingPolicy>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"app":"${APP_NAME}","category":"security"}</customFields>
            <!-- 事件轨：禁碰结构化敏感字段（规格 6.1/第九章）——不挂 ValueMasker，
                 IP 分级等脱敏由产生端代码（SecurityEventLogger）完成 -->
        </encoder>
    </appender>

    <appender name="FILE_AUDIT" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_PATH}/audit.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_PATH}/audit.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
            <maxFileSize>200MB</maxFileSize>
            <maxHistory>30</maxHistory>
            <totalSizeCap>2GB</totalSizeCap>
        </rollingPolicy>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"app":"${APP_NAME}","category":"audit"}</customFields>
            <!-- 事件轨：同 FILE_SECURITY，不挂 ValueMasker -->
        </encoder>
    </appender>

    <appender name="FILE_ACCESS" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_PATH}/access.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_PATH}/access.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
            <maxFileSize>200MB</maxFileSize>
            <maxHistory>7</maxHistory>
            <totalSizeCap>5GB</totalSizeCap>
        </rollingPolicy>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"app":"${APP_NAME}","category":"access"}</customFields>
            <jsonGeneratorDecorator class="net.logstash.logback.mask.MaskingJsonGeneratorDecorator">
                <valueMasker class="com.sanye.strategy.infrastructure.logging.CredentialValueMasker"/>
                <valueMasker class="com.sanye.strategy.infrastructure.logging.PiiValueMasker"/>
            </jsonGeneratorDecorator>
        </encoder>
    </appender>

    <appender name="FILE_MIDDLEWARE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_PATH}/middleware.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_PATH}/middleware.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
            <maxFileSize>200MB</maxFileSize>
            <maxHistory>7</maxHistory>
            <totalSizeCap>2GB</totalSizeCap>
        </rollingPolicy>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"app":"${APP_NAME}","category":"middleware"}</customFields>
            <jsonGeneratorDecorator class="net.logstash.logback.mask.MaskingJsonGeneratorDecorator">
                <valueMasker class="com.sanye.strategy.infrastructure.logging.CredentialValueMasker"/>
                <valueMasker class="com.sanye.strategy.infrastructure.logging.PiiValueMasker"/>
            </jsonGeneratorDecorator>
        </encoder>
    </appender>

    <!-- 控制台（dev 可读性；JSON 同文件口径） -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"app":"${APP_NAME}"}</customFields>
            <jsonGeneratorDecorator class="net.logstash.logback.mask.MaskingJsonGeneratorDecorator">
                <valueMasker class="com.sanye.strategy.infrastructure.logging.CredentialValueMasker"/>
                <valueMasker class="com.sanye.strategy.infrastructure.logging.PiiValueMasker"/>
            </jsonGeneratorDecorator>
        </encoder>
    </appender>

    <!-- 异步包装：业务永不阻塞（neverBlock=true，队列满丢弃并计数——日志至少留本地文件由同步 file appender 兜底的是事件轨；
         请求/业务轨接受极端压力下丢弃，规格 1.3「业务永不阻塞」优先） -->
    <appender name="ASYNC_BUSINESS" class="ch.qos.logback.classic.AsyncAppender">
        <queueSize>4096</queueSize>
        <discardingThreshold>0</discardingThreshold>
        <neverBlock>true</neverBlock>
        <appender-ref ref="FILE_BUSINESS"/>
    </appender>
    <appender name="ASYNC_ERROR" class="ch.qos.logback.classic.AsyncAppender">
        <queueSize>2048</queueSize>
        <discardingThreshold>0</discardingThreshold>
        <neverBlock>true</neverBlock>
        <appender-ref ref="FILE_ERROR"/>
    </appender>
    <!-- 事件轨同步写（低频高价值，不丢） -->
    <appender name="ASYNC_SECURITY" class="ch.qos.logback.classic.AsyncAppender">
        <queueSize>1024</queueSize>
        <discardingThreshold>0</discardingThreshold>
        <neverBlock>false</neverBlock>
        <appender-ref ref="FILE_SECURITY"/>
    </appender>
    <appender name="ASYNC_AUDIT" class="ch.qos.logback.classic.AsyncAppender">
        <queueSize>1024</queueSize>
        <discardingThreshold>0</discardingThreshold>
        <neverBlock>false</neverBlock>
        <appender-ref ref="FILE_AUDIT"/>
    </appender>
    <appender name="ASYNC_ACCESS" class="ch.qos.logback.classic.AsyncAppender">
        <queueSize>4096</queueSize>
        <discardingThreshold>0</discardingThreshold>
        <neverBlock>true</neverBlock>
        <appender-ref ref="FILE_ACCESS"/>
    </appender>
    <appender name="ASYNC_MIDDLEWARE" class="ch.qos.logback.classic.AsyncAppender">
        <queueSize>2048</queueSize>
        <discardingThreshold>0</discardingThreshold>
        <neverBlock>true</neverBlock>
        <appender-ref ref="FILE_MIDDLEWARE"/>
    </appender>

    <!-- 分类路由：logger 名 → 轨文件（additivity=false 防双写） -->
    <logger name="SECURITY" level="INFO" additivity="false">
        <appender-ref ref="ASYNC_SECURITY"/>
    </logger>
    <logger name="AUDIT" level="INFO" additivity="false">
        <appender-ref ref="ASYNC_AUDIT"/>
    </logger>
    <logger name="ACCESS" level="INFO" additivity="false">
        <appender-ref ref="ASYNC_ACCESS"/>
    </logger>
    <logger name="MIDDLEWARE" level="INFO" additivity="false">
        <appender-ref ref="ASYNC_MIDDLEWARE"/>
    </logger>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="ASYNC_BUSINESS"/>
        <appender-ref ref="ASYNC_ERROR"/>
    </root>
</configuration>
```

- [ ] **Step 2: 创建 AccessLogFilter（请求轨）**

```java
package com.sanye.strategy.infrastructure.logging;

import com.sanye.strategy.common.util.IpMaskUtils;
import com.sanye.strategy.common.util.IpUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValue;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * <p>
 * 接入访问日志过滤器 — 请求轨（category=access）产生端
 * </p>
 * <p>
 * 每请求完成后经 {@code ACCESS} logger 输出一行结构化日志（method/uri/status/耗时/掩码 IP），
 * 由 logback-spring.xml 路由至 access.log。IP 按规格 6.3「请求轨接入访问」末段掩码（产生端完成）。
 * 不记录请求体/响应体（防大报文，规格 5.1；审计所需参数另走 ums_oper_log）。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：Servlet 过滤器（OncePerRequestFilter），请求轨唯一产生端。</li>
 *   <li>优缺点：全路径覆盖（含白名单）；代价为与 Actuator/Swagger 请求也会记录
 *       （量大时阶段1 经 Vector 采样，规格 4.1 接入访问可采样）。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("ACCESS");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            ACCESS_LOG.atInfo()
                    .addKeyValue(KeyValue.of("method", request.getMethod()))
                    .addKeyValue(KeyValue.of("uri", request.getRequestURI()))
                    .addKeyValue(KeyValue.of("status", response.getStatus()))
                    .addKeyValue(KeyValue.of("costMs", System.currentTimeMillis() - start))
                    .addKeyValue(KeyValue.of("ip", IpMaskUtils.maskLastSegment(IpUtils.getClientIp(request))))
                    .log("http access");
        }
    }
}
```

- [ ] **Step 3: 创建 LoggingBeanConfig（过滤器注册）**

```java
package com.sanye.strategy.infrastructure.config;

import com.sanye.strategy.infrastructure.logging.AccessLogFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * <p>
 * 日志产生端 Bean 配置 — 注册 {@link AccessLogFilter}（请求轨）
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：日志相关 Spring Bean 收口（后续阶段如需慢请求埋点 Bean 亦加在此）。</li>
 *   <li>优缺点：显式 FilterRegistrationBean 可控顺序与 URL 映射；代价为多一个配置类。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Configuration
public class LoggingBeanConfig {

    /**
     * 注册接入访问日志过滤器（最高优先级，覆盖全部请求含异常路径）
     */
    @Bean
    public FilterRegistrationBean<AccessLogFilter> accessLogFilterRegistration() {
        FilterRegistrationBean<AccessLogFilter> registration =
                new FilterRegistrationBean<>(new AccessLogFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Integer.MIN_VALUE + 10);
        registration.setName("accessLogFilter");
        return registration;
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `& "D:\Tool\apache-maven-3.8.8\bin\mvn" clean compile -q`
Expected: `BUILD SUCCESS`。

- [ ] **Step 5: Commit**

```powershell
git add src/main/resources/logback-spring.xml src/main/java/com/sanye/strategy/infrastructure/logging/AccessLogFilter.java src/main/java/com/sanye/strategy/infrastructure/config/LoggingBeanConfig.java
git commit -m "[feat] 日志阶段0：logback 结构化 JSON 六类分文件 + 脱敏装饰器 + 接入访问过滤器"
```

---

### Task 5: Micrometer Tracing 接入（traceId 入 MDC）

**Files:**
- Modify: `src/main/resources/application.yaml`（文件末尾追加）

**Interfaces:**
- Consumes: Task 1 的 brave 桥依赖；Boot Actuator tracing 自动装配。
- Produces: 每个 HTTP 请求日志自动携带 MDC `traceId`/`spanId`（LogstashEncoder 默认输出全部 MDC 字段）；`management.tracing.propagation.type=W3C` 对应规格 2.2「W3C traceparent 传播」。Task 7 OperLogService 经 `org.slf4j.MDC.get("traceId")` 取用。

- [ ] **Step 1: 追加 tracing 配置**

在 `application.yaml` 末尾追加：

```yaml

management:
  tracing:
    sampling:
      # 阶段0 全采样（dev 排障优先；生产接入采集端后按需下调，规格 4.1 接入访问可采样）
      probability: 1.0
    propagation:
      # W3C traceparent 传播（规格 2.2）
      type: w3c

logging:
  file:
    # logback-spring.xml 的 LOG_PATH 来源（k8s 部署时经环境变量 LOGGING_FILE_PATH 覆盖为 hostPath 挂载目录）
    path: ./logs
```

- [ ] **Step 2: 启动冒烟验证 traceId**

Run: `& "D:\Tool\apache-maven-3.8.8\bin\mvn" spring-boot:run`（非阻塞启动，等待 `Started StrategyApplication`）
另开终端请求：`Invoke-RestMethod -Uri "http://localhost:8080/actuator/health"`
Expected: 控制台 JSON 日志中每条请求相关日志含 `"traceId":"<32位十六进制>"` 字段；`logs/business.log` 文件生成且同含 traceId。
验证后停止应用。

- [ ] **Step 3: Commit**

```powershell
git add src/main/resources/application.yaml
git commit -m "[feat] 日志阶段0：Micrometer Tracing 全采样接入（traceId 入 MDC，W3C 传播）"
```

---

### Task 6: 审计表 DDL + PO + Mapper XML（trace_id/target/operator_type/change_diff 五列）

**Files:**
- Modify: `sql/oper_log.sql`（建表语句内追加五列）
- Modify: `src/main/java/com/sanye/strategy/infrastructure/persistence/po/UmsOperLogPO.java`
- Modify: `src/main/resources/mapper/UmsOperLogMapper.xml`

**Interfaces:**
- Consumes: 无
- Produces: `ums_oper_log` 新增列 `trace_id VARCHAR(64)` / `target_entity VARCHAR(64)` / `target_id BIGINT UNSIGNED` / `operator_type TINYINT UNSIGNED`（1-人工用户 2-系统任务，规格 7.4）/ `change_diff TEXT`（JSON 字符串，规格 7.1）；`UmsOperLogPO` 对应字段 `traceId`/`targetEntity`/`targetId`/`operatorType`/`changeDiff`（Task 7 填充，Task 9 产生 diff）。

- [ ] **Step 1: 修改建表 DDL**

在 `sql/oper_log.sql` 的 `ums_oper_log` 建表语句中，`error_msg` 行之后、`oper_time` 行之前插入五列：

```sql
    `trace_id`       VARCHAR(64)      DEFAULT '' COMMENT '链路追踪ID（MDC traceId，关联 ES 请求链路）',
    `target_entity`  VARCHAR(64)      DEFAULT '' COMMENT '操作对象实体/表名（如 ums_role）',
    `target_id`      BIGINT UNSIGNED  DEFAULT NULL COMMENT '操作对象主键ID',
    `operator_type`  TINYINT UNSIGNED DEFAULT 2 COMMENT '操作者类型：1-人工用户（有 UserContext）2-系统任务（无 UserContext）',
    `change_diff`    TEXT             COMMENT '字段变更 diff（JSON 数组字符串，规格 7.1；凭据剔除/PII 掩码已产生端完成）',
```

并在 `KEY idx_oper_time` 之后追加索引行：

```sql
    KEY `idx_trace_id` (`trace_id`),
```

> 注：建表语句带 `IF NOT EXISTS`，对已存在的 dev 库不生效——dev 库须手工执行等价 ALTER（Step 4）。

- [ ] **Step 2: PO 追加五字段**

在 `UmsOperLogPO.java` 的 `errorMsg` 字段之后、`operTime` 字段之前插入：

```java
    /**
     * 链路追踪ID（MDC traceId）
     */
    private String traceId;

    /**
     * 操作对象实体/表名
     */
    private String targetEntity;

    /**
     * 操作对象主键ID
     */
    private Long targetId;

    /**
     * 操作者类型：1-人工用户 2-系统任务
     */
    private Integer operatorType;

    /**
     * 字段变更 diff（JSON 数组字符串，规格 7.1；仅 INSERT，不参与脱敏——凭据剔除/PII 掩码已产生端完成）
     */
    private String changeDiff;
```

- [ ] **Step 3: Mapper XML 补映射**

`UmsOperLogMapper.xml` 的 `<resultMap>` 中 `error_msg` 行之后插入：

```xml
        <result property="traceId" column="trace_id" />
        <result property="targetEntity" column="target_entity" />
        <result property="targetId" column="target_id" />
        <result property="operatorType" column="operator_type" />
        <result property="changeDiff" column="change_diff" />
```

`Base_Column_List` 替换为：

```xml
    <sql id="Base_Column_List">
        id,user_id,username,oper_module,oper_action,oper_desc,
        oper_type,request_method,request_uri,request_params,request_body,
        response_code,response_msg,cost_time,oper_ip,user_agent,
        status,error_msg,trace_id,target_entity,target_id,operator_type,change_diff,
        oper_time,create_time
    </sql>
```

- [ ] **Step 4: dev 库手工 ALTER 并验证**

对 dev MySQL（`192.168.109.131:30306`，库 `sys_strategy`，root/`mysql123456`）执行（MySQL 8.0 的 `ADD COLUMN` 不支持 `IF NOT EXISTS`，重复执行报 Duplicate column 属预期，逐条执行跳过已存在列）：

```sql
ALTER TABLE ums_oper_log ADD COLUMN trace_id      VARCHAR(64)      DEFAULT '' COMMENT '链路追踪ID';
ALTER TABLE ums_oper_log ADD COLUMN target_entity VARCHAR(64)      DEFAULT '' COMMENT '操作对象实体/表名';
ALTER TABLE ums_oper_log ADD COLUMN target_id     BIGINT UNSIGNED  DEFAULT NULL COMMENT '操作对象主键ID';
ALTER TABLE ums_oper_log ADD COLUMN operator_type TINYINT UNSIGNED DEFAULT 2 COMMENT '操作者类型：1-人工 2-系统';
ALTER TABLE ums_oper_log ADD COLUMN change_diff   TEXT             COMMENT '字段变更 diff（JSON 字符串）';
ALTER TABLE ums_oper_log ADD INDEX idx_trace_id (trace_id);
```

验证：`DESC ums_oper_log;` Expected: 五个新列存在。

- [ ] **Step 5: 编译验证**

Run: `& "D:\Tool\apache-maven-3.8.8\bin\mvn" clean compile -q`
Expected: `BUILD SUCCESS`。

- [ ] **Step 6: Commit**

```powershell
git add sql/oper_log.sql src/main/java/com/sanye/strategy/infrastructure/persistence/po/UmsOperLogPO.java src/main/resources/mapper/UmsOperLogMapper.xml
git commit -m "[feat] 日志阶段0：ums_oper_log 增补 trace_id/target/operator_type/change_diff 五列"
```

---

### Task 7: OperLogService 增强（trace_id/operator_type/target/change_diff 填充 + audit.log 双写）

**Files:**
- Modify: `src/main/java/com/sanye/strategy/application/rbac/OperLogReq.java`
- Modify: `src/main/java/com/sanye/strategy/application/rbac/OperLogService.java`
- Test: `src/test/java/com/sanye/strategy/application/rbac/OperLogServiceTest.java`

**Interfaces:**
- Consumes: Task 4 的 `AUDIT` logger（logback 路由 audit.log）；Task 6 的 PO 五字段；`org.slf4j.MDC.get("traceId")`（Task 5 注入）；`UserContext`（现有）。
- Produces: `OperLogReq` 新增字段 `targetEntity: String` / `targetId: Long` / `changeDiff: String`（Task 9 各调用点填充）；`OperLogService.record` 行为——填充 traceId（MDC，不信任调用方传，规格 7.3）、operatorType（UserContext 有值→1 空→2，规格 7.4 逻辑推导不手传）、target 元数据、change_diff；DB 写入后经 `AUDIT` logger 输出结构化 JSON（含 changeDiff，WORM 权威链产生端，规格 7.6）。

- [ ] **Step 1: 写失败测试**

```java
package com.sanye.strategy.application.rbac;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sanye.strategy.domain.enums.OperTypeEnum;
import com.sanye.strategy.infrastructure.persistence.mapper.UmsOperLogMapper;
import com.sanye.strategy.infrastructure.persistence.po.UmsOperLogPO;
import com.sanye.strategy.infrastructure.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OperLogService 单测 — trace_id 取自 MDC / operator_type 逻辑推导 / target 元数据 /
 * change_diff 透传 / audit.log 双写 / 写库失败降级不上抛
 */
class OperLogServiceTest {

    private UmsOperLogMapper mapper;
    private PlatformTransactionManager txManager;
    private OperLogService service;
    private ListAppender<ILoggingEvent> auditAppender;

    @BeforeEach
    void setUp() {
        mapper = mock(UmsOperLogMapper.class);
        txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new OperLogService(txManager, mapper);
        Logger auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        auditAppender = new ListAppender<>();
        auditAppender.start();
        auditLogger.addAppender(auditAppender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger("AUDIT")).detachAppender(auditAppender);
        MDC.clear();
        UserContext.clear();
    }

    private OperLogReq req() {
        return OperLogReq.builder().module("rbac").action("updateRole").desc("测试")
                .type(OperTypeEnum.UPDATE).success(true)
                .targetEntity("ums_role").targetId(9L)
                .changeDiff("[{\"field\":\"roleName\",\"old\":\"a\",\"new\":\"b\"}]")
                .build();
    }

    @Test
    void fillsTraceIdFromMdcAndTargetFromReq() {
        MDC.put("traceId", "trace-abc");
        UserContext.set(new UserContext(1L, List.of("SUPER_ADMIN"), List.of(), 2L, "dev-1"));
        service.record(req());
        ArgumentCaptor<UmsOperLogPO> captor = ArgumentCaptor.forClass(UmsOperLogPO.class);
        verify(mapper).insert(captor.capture());
        UmsOperLogPO po = captor.getValue();
        assertEquals("trace-abc", po.getTraceId());
        assertEquals("ums_role", po.getTargetEntity());
        assertEquals(9L, po.getTargetId());
        assertEquals(1, po.getOperatorType());
        assertEquals("[{\"field\":\"roleName\",\"old\":\"a\",\"new\":\"b\"}]", po.getChangeDiff());
    }

    @Test
    void operatorTypeIsSystemWithoutUserContext() {
        service.record(req());
        ArgumentCaptor<UmsOperLogPO> captor = ArgumentCaptor.forClass(UmsOperLogPO.class);
        verify(mapper).insert(captor.capture());
        assertEquals(2, captor.getValue().getOperatorType());
    }

    @Test
    void writesAuditJsonToAuditLogger() {
        MDC.put("traceId", "trace-abc");
        service.record(req());
        assertEquals(1, auditAppender.list.size());
        String auditJson = auditAppender.list.get(0).getFormattedMessage();
        assertTrue(auditJson.contains("\"traceId\":\"trace-abc\""));
        assertTrue(auditJson.contains("\"targetEntity\":\"ums_role\""));
        assertTrue(auditJson.contains("\"operatorType\":2"));
        assertTrue(auditJson.contains("\"changeDiff\":\"[{\\\"field\\\":\\\"roleName\\\",\\\"old\\\":\\\"a\\\",\\\"new\\\":\\\"b\\\"}]\""));
    }

    @Test
    void dbFailureDegradesWithoutThrowing() {
        when(mapper.insert(any())).thenThrow(new RuntimeException("db down"));
        service.record(req());
        // 不上抛即通过（降级语义）
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `& "D:\Tool\apache-maven-3.8.8\bin\mvn" test "-Dtest=OperLogServiceTest" -q`
Expected: 编译失败（`OperLogReq.targetEntity/targetId/changeDiff` 不存在）或断言失败。

- [ ] **Step 3: OperLogReq 追加 target + changeDiff 字段**

在 `OperLogReq.java` 的 `errorMsg` 字段之后追加：

```java
    /**
     * 操作对象实体/表名（如 ums_role；target 元数据，规格 7.2）
     */
    private String targetEntity;

    /**
     * 操作对象主键ID（规格 7.2）
     */
    private Long targetId;

    /**
     * 字段变更 diff（JSON 数组字符串，规格 7.1；由门面经 DiffUtils 生成，凭据剔除/PII 掩码已产生端完成）
     */
    private String changeDiff;
```

- [ ] **Step 4: 改造 OperLogService**

`OperLogService.java` 改造点（类上新增 import 与 AUDIT logger，`record` 方法体重写）：

新增 import：

```java
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
```

类体顶部新增常量：

```java
    /**
     * 审计权威链 logger — logback 路由 audit.log（阶段1 经 Vector → MinIO Object Lock WORM，规格 7.6）
     */
    private static final org.slf4j.Logger AUDIT_LOG = org.slf4j.LoggerFactory.getLogger("AUDIT");

    /**
     * 审计 JSON 序列化器 — Boot4 默认 Jackson 3（tools.jackson）
     */
    private static final ObjectMapper AUDIT_MAPPER = new ObjectMapper();
```

`record` 方法体替换为：

```java
    public void record(OperLogReq req) {
        try {
            TransactionTemplate tpl = new TransactionTemplate(txManager);
            tpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tpl.executeWithoutResult(status -> {
                UserContext ctx = UserContext.get();
                // traceId 从 MDC 取（不信任调用方传，规格 7.3）；operatorType 逻辑推导（规格 7.4）
                String traceId = MDC.get("traceId");
                int operatorType = ctx == null ? 2 : 1;
                UmsOperLogPO po = new UmsOperLogPO();
                po.setUserId(ctx == null ? null : ctx.getUserId());
                po.setUsername(ctx == null ? null : String.valueOf(ctx.getUserId()));
                po.setOperModule(req.getModule());
                po.setOperAction(req.getAction());
                po.setOperDesc(req.getDesc());
                po.setOperType(req.getType() == null ? OperTypeEnum.OTHER.getCode() : req.getType().getCode());
                po.setRequestMethod(req.getRequestMethod());
                po.setRequestUri(req.getRequestUri());
                po.setOperIp(resolveClientIp());
                po.setUserAgent(req.getUserAgent());
                po.setStatus(req.isSuccess() ? 1 : 0);
                po.setErrorMsg(req.getErrorMsg());
                po.setTraceId(traceId);
                po.setTargetEntity(req.getTargetEntity());
                po.setTargetId(req.getTargetId());
                po.setOperatorType(operatorType);
                po.setChangeDiff(req.getChangeDiff());
                po.setOperTime(LocalDateTime.now());
                operLogMapper.insert(po);
                writeAuditFile(po);
            });
        } catch (Exception e) {
            log.error("审计日志写入失败，降级不影响主流程", e);
        }
    }

    /**
     * 审计权威链双写：结构化 JSON 落 audit.log（视图副本之外的 WORM 源，规格 7.6）；
     * 文件写失败仅记 error，不影响 DB 副本已落库的事实
     */
    private void writeAuditFile(UmsOperLogPO po) {
        try {
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("auditId", po.getId());
            audit.put("traceId", po.getTraceId());
            audit.put("userId", po.getUserId());
            audit.put("operatorType", po.getOperatorType());
            audit.put("module", po.getOperModule());
            audit.put("action", po.getOperAction());
            audit.put("targetEntity", po.getTargetEntity());
            audit.put("targetId", po.getTargetId());
            audit.put("operType", po.getOperType());
            audit.put("ip", po.getOperIp());
            audit.put("status", po.getStatus());
            audit.put("changeDiff", po.getChangeDiff());
            audit.put("desc", po.getOperDesc());
            audit.put("errorMsg", po.getErrorMsg());
            audit.put("operTime", po.getOperTime() == null ? null : po.getOperTime().toString());
            AUDIT_LOG.info(AUDIT_MAPPER.writeValueAsString(audit));
        } catch (Exception e) {
            log.error("审计文件双写失败（DB 副本已落库）", e);
        }
    }
```

> 注：`po.getId()` 依赖 MP 插入主键回填——`UmsOperLogMapper.insert` 走 `@TableId(ASSIGN_ID)`，插入后 PO 主键已生成（雪花在 SQL 执行前赋值），`auditId` 与 DB 行一致，供 MySQL 视图副本与 WORM 权威链对账。

- [ ] **Step 5: 运行确认通过**

Run: `& "D:\Tool\apache-maven-3.8.8\bin\mvn" test "-Dtest=OperLogServiceTest" -q`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 6: 全量回归**

Run: `& "D:\Tool\apache-maven-3.8.8\bin\mvn" test -q`
Expected: 全部 PASS（既有测试不受影响）。

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/sanye/strategy/application/rbac/OperLogReq.java src/main/java/com/sanye/strategy/application/rbac/OperLogService.java src/test/java/com/sanye/strategy/application/rbac/OperLogServiceTest.java
git commit -m "[feat] 日志阶段0：审计增强（trace_id/operator_type/target/change_diff 填充 + audit.log WORM 源双写）"
```

---

### Task 8: DiffUtils 审计字段 diff 工具 + 绑定行查询契约

**Files:**
- Create: `src/main/java/com/sanye/strategy/infrastructure/logging/DiffUtils.java`
- Test: `src/test/java/com/sanye/strategy/infrastructure/logging/DiffUtilsTest.java`
- Modify: `src/main/java/com/sanye/strategy/domain/user/repository/UmsUserRoleService.java`（+ `findByUserIdAndRoleId` 契约）
- Modify: `src/main/java/com/sanye/strategy/infrastructure/persistence/mapper/UmsUserRoleMapper.java`（+ `selectByUserRole`）
- Modify: `src/main/resources/mapper/UmsUserRoleMapper.xml`（+ `selectByUserRole` SQL）
- Modify: `src/main/java/com/sanye/strategy/infrastructure/persistence/impl/UmsUserRoleServiceImpl.java`（+ 实现）

**Interfaces:**
- Consumes: Task 3 的 `SensitiveFields.isCredential/isPii`、`CredentialValueMasker.placeholder`、`PiiValueMasker.maskValue`；Boot4 默认 Jackson 3（`tools.jackson.databind.ObjectMapper`）。
- Produces:
  - `DiffUtils.diffBean(Object oldValue, Object newValue): List<Map<String,Object>>` —— 纯 POJO 反射 diff：忽略 id/审计字段（id/createTime/updateTime/createUserId/updateUserId/deleted），new null 或与 old 相同不报；凭据类字段记 `{"field":X,"op":"changed"}`，PII 类字段掩码保统计；枚举值经 `getCode()` 落码值（规格 7.1/7.5）；
  - `DiffUtils.diffIdSet(String field, Set<Long> before, Set<Long> after): List<Map<String,Object>>` —— 关联集 diff（物理删除关联表无行快照）：added/removed 记 `{"field":X,"op":"add"|"remove","ids":[...]}`；
  - `DiffUtils.toChangeDiffJson(List<Map<String,Object>> entries): String` —— diff 条目 → JSON 数组字符串（空/null 返回 null，DB 落默认值）；Jackson 3 序列化异常为非受检，无需 try/catch；
  - `UmsUserRoleService.findByUserIdAndRoleId(Long userId, Long roleId): UmsUserRole` —— 绑定行查询（renewUserRole 续期 diff 前置取旧 end_time/target bindId）。

- [ ] **Step 1: 写失败测试**

```java
package com.sanye.strategy.infrastructure.logging;

import com.sanye.strategy.domain.enums.RoleStatusEnum;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DiffUtils 单测 — POJO diff（标量/枚举/凭据剔除/PII 掩码/审计字段忽略）+ 关联集 diff + JSON 序列化
 * <p>测试用 record 承载样例字段（反射读私有 final 组件字段）。</p>
 */
class DiffUtilsTest {

    /** 测试 POJO：id/roleName/status/password/phone/createTime 覆盖忽略/标量/枚举/凭据/PII 全形态 */
    record Sample(Long id, String roleName, RoleStatusEnum status,
                  String password, String phone, String createTime) {
    }

    private static Sample sample(Long id, String roleName, RoleStatusEnum status,
                                 String password, String phone, String createTime) {
        return new Sample(id, roleName, status, password, phone, createTime);
    }

    @Test
    void reportsOnlyChangedScalarAndEnumFields() {
        Sample oldS = sample(1L, "运营", RoleStatusEnum.NORMAL, "secret", "13812345678", "2026-01-01");
        Sample newS = sample(1L, "运营专员", RoleStatusEnum.DISABLED, "secret", "13812345678", "2026-01-01");

        List<Map<String, Object>> diff = DiffUtils.diffBean(oldS, newS);

        assertEquals(2, diff.size());
        assertEquals("roleName", diff.get(0).get("field"));
        assertEquals("运营", diff.get(0).get("old"));
        assertEquals("运营专员", diff.get(0).get("new"));
        assertEquals("status", diff.get(1).get("field"));
        assertEquals("1", diff.get(1).get("old"));   // getCode() 落码值
        assertEquals("0", diff.get(1).get("new"));
    }

    @Test
    void ignoresNewNullAndUnchangedFields() {
        Sample oldS = sample(1L, "运营", RoleStatusEnum.NORMAL, "secret", "13812345678", "2026-01-01");
        // password new=null → 部分更新未涉及不报；其余字段未变 → 空 diff
        Sample newS = sample(1L, "运营", RoleStatusEnum.NORMAL, null, "13812345678", "2026-01-01");

        assertTrue(DiffUtils.diffBean(oldS, newS).isEmpty());
    }

    @Test
    void ignoresIdAndAuditFields() {
        Sample oldS = sample(1L, "运营", RoleStatusEnum.NORMAL, "secret", "13812345678", "2026-01-01");
        // id 变 + createTime 变 → 均在忽略清单，不报
        Sample newS = sample(2L, "运营", RoleStatusEnum.NORMAL, "secret", "13812345678", "2026-01-02");

        assertTrue(DiffUtils.diffBean(oldS, newS).isEmpty());
    }

    @Test
    void credentialFieldEmitsChangedPlaceholder() {
        Sample oldS = sample(1L, "运营", RoleStatusEnum.NORMAL, "old-secret", "13812345678", "2026-01-01");
        Sample newS = sample(1L, "运营", RoleStatusEnum.NORMAL, "new-secret", "13812345678", "2026-01-01");

        List<Map<String, Object>> diff = DiffUtils.diffBean(oldS, newS);

        assertEquals(1, diff.size());
        assertEquals("password", diff.get(0).get("field"));
        assertEquals("changed", diff.get(0).get("op"));
        assertNull(diff.get(0).get("old"));   // 凭据值永不出现（规格 7.5）
    }

    @Test
    void piiFieldMasksOldAndNew() {
        Sample oldS = sample(1L, "运营", RoleStatusEnum.NORMAL, "secret", "13812345678", "2026-01-01");
        Sample newS = sample(1L, "运营", RoleStatusEnum.NORMAL, "secret", "13987654321", "2026-01-01");

        List<Map<String, Object>> diff = DiffUtils.diffBean(oldS, newS);

        assertEquals(1, diff.size());
        assertEquals("phone", diff.get(0).get("field"));
        assertEquals("138****5678", diff.get(0).get("old"));
        assertEquals("139****4321", diff.get(0).get("new"));
    }

    @Test
    void diffIdSetAddsAndRemoves() {
        List<Map<String, Object>> diff = DiffUtils.diffIdSet("roleIds", Set.of(1L, 2L), Set.of(2L, 3L));

        assertEquals(2, diff.size());
        assertEquals("add", diff.get(0).get("op"));
        assertEquals(List.of(3L), diff.get(0).get("ids"));
        assertEquals("remove", diff.get(1).get("op"));
        assertEquals(List.of(1L), diff.get(1).get("ids"));
    }

    @Test
    void toChangeDiffJsonSerializesArray() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("field", "roleName");
        entry.put("old", "a");
        entry.put("new", "b");
        List<Map<String, Object>> entries = List.of(entry);

        assertEquals("[{\"field\":\"roleName\",\"old\":\"a\",\"new\":\"b\"}]", DiffUtils.toChangeDiffJson(entries));
    }

    @Test
    void emptyDiffReturnsNullJson() {
        assertNull(DiffUtils.toChangeDiffJson(List.of()));
        assertNull(DiffUtils.toChangeDiffJson(null));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `& "D:\Tool\apache-maven-3.8.8\bin\mvn" test "-Dtest=DiffUtilsTest" -q`
Expected: 编译失败（`DiffUtils` 不存在）。

- [ ] **Step 3: 实现 DiffUtils**

```java
package com.sanye.strategy.infrastructure.logging;

import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * <p>
 * 审计字段 diff 工具 — 纯 POJO 反射 diff + 关联集 diff + JSON 序列化（规格 7.1/7.5）
 * </p>
 * <p>
 * 门面就地 diff：{@code getById} 已有旧值，变更前拍快照（{@code BeanCopyUtils.copy}），
 * 变更后调用 {@link #diffBean} 产出「只报 new 非 null 且与 old 不同字段」的结构化条目，
 * 经 {@link #toChangeDiffJson} 序列化落 {@code ums_oper_log.change_diff}。
 * 敏感字段沿用 {@link SensitiveFields} 单一源（规格 6.4）：凭据类记
 * {@code {"field":X,"op":"changed"}} 变更事实（规格 7.5 值永不出现）、PII 类掩码保统计。
 * 关联集 diff（{@link #diffIdSet}）供物理删除关联表（无行快照）变更前后集合比对。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：审计字段 diff 的纯函数工具（无框架依赖，静态方法），RBAC 门面与后续业务域共用。</li>
 *   <li>优缺点：零状态零依赖、规则收口一处；代价为反射性能开销（审计低频可接受）、
 *       复杂嵌套对象不支持（值以 {@code toString} 归一，嵌套结构由调用方预展平）。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
public final class DiffUtils {

    /**
     * diff JSON 序列化器 — Boot4 默认 Jackson 3（tools.jackson）；序列化异常为非受检，无需 try/catch
     */
    private static final ObjectMapper DIFF_MAPPER = new ObjectMapper();

    /**
     * 忽略字段：主键 + 审计字段（规格 7.1）
     */
    private static final Set<String> IGNORED_FIELDS =
            Set.of("id", "createTime", "updateTime", "createUserId", "updateUserId", "deleted");

    private DiffUtils() {
    }

    /**
     * 纯 POJO 字段 diff：反射遍历（含继承链），只报 new 非 null 且与 old 不同的字段
     * <p>凭据类字段值永不出现（changed 占位）；PII 类字段掩码保统计；枚举经 {@code getCode()} 落码值。</p>
     *
     * @param oldValue 变更前快照（非 null）
     * @param newValue 变更后实体（非 null）
     * @return diff 条目列表（无变更返回空列表）
     */
    public static List<Map<String, Object>> diffBean(Object oldValue, Object newValue) {
        List<Map<String, Object>> entries = new ArrayList<>();
        if (oldValue == null || newValue == null) {
            return entries;
        }
        for (Field field : fieldsInChain(newValue.getClass())) {
            if (Modifier.isStatic(field.getModifiers()) || IGNORED_FIELDS.contains(field.getName())) {
                continue;
            }
            Object oldField;
            Object newField;
            try {
                field.setAccessible(true);
                oldField = field.get(oldValue);
                newField = field.get(newValue);
            } catch (IllegalAccessException e) {
                // 反射不可达字段跳过（防御），不影响审计主流程
                continue;
            }
            if (newField == null) {
                continue;   // new null = 部分更新未涉及，不报（规格 7.1）
            }
            if (SensitiveFields.isCredential(field.getName())) {
                // 凭据类：值永不出现，只记变更事实（规格 7.5）
                if (!Objects.equals(oldField, newField)) {
                    entries.add(credentialEntry(field.getName()));
                }
                continue;
            }
            String oldText = normalize(oldField);
            String newText = normalize(newField);
            if (SensitiveFields.isPii(field.getName())) {
                // PII 类：掩码保统计（规格 6.2/7.5）
                String maskedOld = PiiValueMasker.maskValue(field.getName(), oldText);
                String maskedNew = PiiValueMasker.maskValue(field.getName(), newText);
                oldText = maskedOld != null ? maskedOld : oldText;
                newText = maskedNew != null ? maskedNew : newText;
            }
            if (Objects.equals(oldText, newText)) {
                continue;   // 未变化不报
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("field", field.getName());
            entry.put("old", oldText);
            entry.put("new", newText);
            entries.add(entry);
        }
        return entries;
    }

    /**
     * 关联集 diff（物理删除关联表无行快照）：before vs after → added/removed
     *
     * @param field  集合字段标签（如 roleIds/permissionIds）
     * @param before 变更前 ID 集合
     * @param after  变更后 ID 集合
     * @return {@code {"field":X,"op":"add"|"remove","ids":[...]}} 条目列表（无变化返回空列表）
     */
    public static List<Map<String, Object>> diffIdSet(String field, Set<Long> before, Set<Long> after) {
        List<Map<String, Object>> entries = new ArrayList<>();
        Set<Long> beforeSafe = before == null ? Set.of() : before;
        Set<Long> afterSafe = after == null ? Set.of() : after;
        List<Long> added = new ArrayList<>(afterSafe);
        added.removeAll(beforeSafe);
        List<Long> removed = new ArrayList<>(beforeSafe);
        removed.removeAll(afterSafe);
        if (!added.isEmpty()) {
            entries.add(idSetEntry(field, "add", added));
        }
        if (!removed.isEmpty()) {
            entries.add(idSetEntry(field, "remove", removed));
        }
        return entries;
    }

    /**
     * diff 条目 → JSON 数组字符串（change_diff 落库形态，规格 7.1）；
     * 空/null 条目返回 null（DB 落默认值）
     *
     * @param entries diff 条目列表
     * @return JSON 数组字符串；无变更返回 null
     */
    public static String toChangeDiffJson(List<Map<String, Object>> entries) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        return DIFF_MAPPER.writeValueAsString(entries);
    }

    private static Map<String, Object> credentialEntry(String fieldName) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("field", fieldName);
        entry.put("op", "changed");
        return entry;
    }

    private static Map<String, Object> idSetEntry(String field, String op, List<Long> ids) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("field", field);
        entry.put("op", op);
        entry.put("ids", ids);
        return entry;
    }

    private static List<Field> fieldsInChain(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                fields.add(f);
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    private static String normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Enum<?> enumValue) {
            // 业务枚举统一取 getCode() 落码值（DataScopeEnum/RoleStatusEnum/PermissionTypeEnum 等）
            try {
                Method getCode = value.getClass().getMethod("getCode");
                Object code = getCode.invoke(value);
                return code == null ? null : String.valueOf(code);
            } catch (ReflectiveOperationException e) {
                return enumValue.name();
            }
        }
        return value.toString();
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `& "D:\Tool\apache-maven-3.8.8\bin\mvn" test "-Dtest=DiffUtilsTest" -q`
Expected: `Tests run: 8, Failures: 0, Errors: 0`

- [ ] **Step 5: 新增绑定行查询契约（renewUserRole 续期 diff 前置）**

`UmsUserRoleService.java`（domain/user/repository）在 `getById` 方法之后追加：

```java
    /**
     * 按用户+角色查绑定行（单角色续期 diff 前置：取旧 end_time 与绑定行主键做 target）
     *
     * @param userId 用户ID
     * @param roleId 角色ID
     * @return 绑定行，不存在返回 null
     */
    UmsUserRole findByUserIdAndRoleId(Long userId, Long roleId);
```

`UmsUserRoleMapper.java`（infrastructure/persistence/mapper）在 `updateEndTimeByUserRole` 方法之前追加：

```java
    /**
     * 按用户+角色查绑定行（单角色续期 diff 前置）
     *
     * @param userId 用户ID
     * @param roleId 角色ID
     * @return 绑定行，不存在返回 null
     */
    UmsUserRolePO selectByUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);
```

`UmsUserRoleMapper.xml`（resources/mapper）在 `selectEffectiveByUserId` 之后追加：

```xml
    <select id="selectByUserRole" resultType="com.sanye.strategy.infrastructure.persistence.po.UmsUserRolePO">
        SELECT * FROM ums_user_role WHERE user_id = #{userId} AND role_id = #{roleId} LIMIT 1
    </select>
```

`UmsUserRoleServiceImpl.java`（infrastructure/persistence/impl）在 `getById` 方法之后追加：

```java
    @Override
    public UmsUserRole findByUserIdAndRoleId(Long userId, Long roleId) {
        return BeanCopyUtils.copy(userRoleMapper.selectByUserRole(userId, roleId), UmsUserRole.class);
    }
```

- [ ] **Step 6: 编译 + 全量回归**

Run: `& "D:\Tool\apache-maven-3.8.8\bin\mvn" test -q`
Expected: 全部 PASS。

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/sanye/strategy/infrastructure/logging/DiffUtils.java src/test/java/com/sanye/strategy/infrastructure/logging/DiffUtilsTest.java src/main/java/com/sanye/strategy/domain/user/repository/UmsUserRoleService.java src/main/java/com/sanye/strategy/infrastructure/persistence/mapper/UmsUserRoleMapper.java src/main/resources/mapper/UmsUserRoleMapper.xml src/main/java/com/sanye/strategy/infrastructure/persistence/impl/UmsUserRoleServiceImpl.java
git commit -m "[feat] 日志阶段0：DiffUtils 审计字段 diff（纯 POJO + 关联集 + JSON 序列化）+ 绑定行查询契约"
```

---

### Task 9: RbacManageService target + diff 填充（9 类 UPDATE + 权限集 GRANT/REVOKE）

**Files:**
- Modify: `src/main/java/com/sanye/strategy/application/rbac/RbacManageService.java`
- Modify: `src/test/java/com/sanye/strategy/application/rbac/RbacManageServiceTest.java`

**Interfaces:**
- Consumes: Task 7 的 `OperLogReq.targetEntity/targetId/changeDiff`；Task 8 的 `DiffUtils.diffBean/diffIdSet/toChangeDiffJson` + `UmsUserRoleService.findByUserIdAndRoleId`。
- Produces: 全部 RBAC 审计调用点携带 target 元数据（规格 7.2）+ 变更字段 diff（`changeDiff` JSON）。范围（规格附录）：9 类 UPDATE（updateRole/updateRoleStatus/updatePermission/updatePermissionStatus/renewUserRole/renewBatch/replaceUserRoles/assignRolesBatch/removeUserRole）逐字段或关联集 diff；权限集 GRANT/REVOKE（replaceRolePermissions/grantRolePermissions/revokeRolePermissions/importRoles 覆盖分支）记 added/removed 权限 id；CREATE/DELETE 无 diff 仅补 target；evict/定时扫描不 diff。

> ⚠️ 偏差校正：规格附录 renewUserRole target 为绑定行 id（bindId），原计划 Task 8 误写 userId——本任务以 bindId 为准（diff 前置经 `findByUserIdAndRoleId` 取绑定行）；`assignRolesBatch` 审计行 target_id 取 roleId（批操作单一目标=所授角色），diff 逐用户记 added。

- [ ] **Step 1: 扩展 auditLog 辅助方法（5 参数 + changeDiff）**

`RbacManageService.java` 顶部新增 import：

```java
import com.sanye.strategy.infrastructure.logging.DiffUtils;
```

`auditLog` 方法替换为：

```java
    /**
     * 构造操作审计请求（成功态；module 固定 rbac，携带 target 元数据与字段 diff——规格 7.2）
     *
     * @param action       操作动作
     * @param desc         操作说明
     * @param type         操作类型
     * @param targetEntity 操作对象实体/表名
     * @param targetId     操作对象主键ID
     * @param diff         字段 diff 条目（无变更传空列表/null，经 DiffUtils 序列化落 change_diff）
     * @return 审计请求
     */
    private OperLogReq auditLog(String action, String desc, OperTypeEnum type,
                                String targetEntity, Long targetId, List<Map<String, Object>> diff) {
        return OperLogReq.builder().module("rbac").action(action).desc(desc).type(type)
                .targetEntity(targetEntity).targetId(targetId)
                .changeDiff(DiffUtils.toChangeDiffJson(diff))
                .success(true).build();
    }
```

- [ ] **Step 2: manageWrite 失败分支保留 target（不含 diff）**

`manageWrite` 的 catch 分支重建 req 处补两行（失败操作无 diff——变更未生效，规格附录 CREATE/DELETE 之外仅成功态 diff）：

```java
                operLogService.record(OperLogReq.builder()
                        .module(logReq.getModule()).action(logReq.getAction())
                        .desc(logReq.getDesc()).type(logReq.getType())
                        .targetEntity(logReq.getTargetEntity()).targetId(logReq.getTargetId())
                        .success(false).errorMsg(e.getMessage()).build());
```

- [ ] **Step 3: 角色/权限 UPDATE 就地 diff（变更前拍快照 → 变更 → diffBean）**

`updateRole` 方法体中 `BeanCopyUtils.copy(dto, role, ...)` 之前加快照、manageWrite 传 diff：

```java
        UmsRole old = BeanCopyUtils.copy(role, UmsRole.class);   // diff 前置：就地 diff 旧值快照
        String originalRoleCode = role.getRoleCode();
        BeanCopyUtils.copy(dto, role, "id", "isBuiltIn", "deleted", "createTime", "updateTime");
        if (dto.getDataScope() != null) {
            DataScopeEnum scope = DataScopeEnum.valueOf(dto.getDataScope());
            if (scope == null) {
                log.warn("非法 dataScope 码值={}，角色 {} 保持原数据范围", dto.getDataScope(), id);
            } else {
                role.setDataScope(scope);
            }
        }
        List<Map<String, Object>> diff = DiffUtils.diffBean(old, role);
        boolean roleCodeChanged = originalRoleCode != null && !originalRoleCode.equals(role.getRoleCode());
        EvictPlan plan = roleCodeChanged
                ? EvictPlan.builder().roleId(id).sourceDesc("角色改名 roleId=" + id).build()
                : null;
        manageWrite(() -> {
            roleService.updateById(role);
            return null;
        }, plan,
                auditLog("updateRole", "修改角色 " + id, OperTypeEnum.UPDATE, "ums_role", id, diff));
```

`updateRoleStatus` 方法体中 `role.setStatus(status)` 之前加快照、manageWrite 传 diff：

```java
        UmsRole old = BeanCopyUtils.copy(role, UmsRole.class);
        role.setStatus(status);
        List<Map<String, Object>> diff = DiffUtils.diffBean(old, role);
        EvictPlan plan = EvictPlan.builder().roleId(id)
                .sourceDesc("角色" + (RoleStatusEnum.NORMAL.equals(status) ? "启用" : "停用") + " roleId=" + id).build();
        manageWrite(() -> {
            roleService.updateById(role);
            return null;
        }, plan,
                auditLog("updateRoleStatus", "角色 " + id + " 状态→" + status.getCode(), OperTypeEnum.UPDATE, "ums_role", id, diff));
```

`updatePermission` 方法体中 `BeanCopyUtils.copy(dto, p, ...)` 之前加快照、manageWrite 传 diff：

```java
        UmsPermission old = BeanCopyUtils.copy(p, UmsPermission.class);
        BeanCopyUtils.copy(dto, p, "id", "permissionCode", "isBuiltIn", "deleted", "createTime", "updateTime");
        List<Map<String, Object>> diff = DiffUtils.diffBean(old, p);
        manageWrite(() -> {
            permissionService.updateById(p);
            return null;
        }, null,
                auditLog("updatePermission", "修改权限 " + id, OperTypeEnum.UPDATE, "ums_permission", id, diff));
```

`updatePermissionStatus` 方法体中 `p.setStatus(status)` 之前加快照、manageWrite 传 diff：

```java
        UmsPermission old = BeanCopyUtils.copy(p, UmsPermission.class);
        p.setStatus(status);
        List<Map<String, Object>> diff = DiffUtils.diffBean(old, p);
        List<Long> roleIds = rolePermissionService.getRoleIdsByPermissionId(id);
        List<Long> userIds = collectActiveUserIds(roleIds);   // 停用/启用对称：都踢（重登同步新快照）
        EvictPlan plan = userIds.isEmpty() ? null
                : EvictPlan.builder().userIds(userIds)
                        .sourceDesc("权限" + (RoleStatusEnum.NORMAL.equals(status) ? "启用" : "停用") + " permId=" + id).build();
        manageWrite(() -> {
            permissionService.updateById(p);
            return null;
        }, plan,
                auditLog("updatePermissionStatus", "权限 " + id + " 状态→" + status.getCode(), OperTypeEnum.UPDATE, "ums_permission", id, diff));
```

- [ ] **Step 4: 用户-角色 UPDATE 就地 diff（续期/覆盖/批量授/解绑）**

`renewUserRole` 方法整体替换（findByUserIdAndRoleId 取旧 end_time 与绑定行 id）：

```java
    public boolean renewUserRole(Long userId, Long roleId, LocalDateTime endTime) {
        // 续费原地变更，权限不变化无需踢人，仅审计；diff 前置查绑定行取旧 end_time
        UmsUserRole bind = userRoleService.findByUserIdAndRoleId(userId, roleId);
        List<Map<String, Object>> diff = new ArrayList<>();
        if (bind != null) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("field", "endTime");
            entry.put("old", bind.getEndTime() == null ? null : bind.getEndTime().toString());
            entry.put("new", endTime == null ? null : endTime.toString());
            diff.add(entry);
        }
        return manageWrite(() -> userRoleService.renew(userId, roleId, endTime), null,
                auditLog("renewUserRole", "续期 userId=" + userId + " roleId=" + roleId + " end=" + endTime,
                        OperTypeEnum.UPDATE, "ums_user_role", bind == null ? null : bind.getId(), diff));
    }
```

`renewBatch` 方法整体替换（每条绑定 diff，bindId 区分；批量逐条等保要变更内容）：

```java
    public int renewBatch(List<Long> bindIds, LocalDateTime endTime) {
        // 续费原地变更，权限不变化无需踢人，仅审计；批量逐条 diff（bindId 区分，规格附录）
        List<Map<String, Object>> diff = new ArrayList<>();
        for (Long bindId : bindIds) {
            UmsUserRole bind = userRoleService.getById(bindId);
            if (bind == null) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("field", "endTime");
            entry.put("bindId", bindId);
            entry.put("old", bind.getEndTime() == null ? null : bind.getEndTime().toString());
            entry.put("new", endTime == null ? null : endTime.toString());
            diff.add(entry);
        }
        return manageWrite(() -> {
            int n = 0;
            for (Long bindId : bindIds) {
                if (userRoleService.renewById(bindId, endTime)) {
                    n++;
                }
            }
            return n;
        }, null, auditLog("renewBatch", "批量续期 " + bindIds.size() + " 条绑定",
                OperTypeEnum.UPDATE, "ums_user_role", null, diff));
    }
```

`replaceUserRoles` 方法整体替换（关联集 diff：变更前角色集 vs 变更后）：

```java
    public void replaceUserRoles(Long userId, List<UserRoleAssignDTO> assigns) {
        Set<Long> before = new LinkedHashSet<>();
        for (UmsUserRole ur : userRoleService.listEffectiveByUserId(userId)) {
            before.add(ur.getRoleId());
        }
        Set<Long> after = new LinkedHashSet<>();
        for (UserRoleAssignDTO a : (assigns == null ? List.<UserRoleAssignDTO>of() : assigns)) {
            after.add(a.getRoleId());
        }
        List<Map<String, Object>> diff = DiffUtils.diffIdSet("roleIds", before, after);
        EvictPlan plan = EvictPlan.builder().userIds(List.of(userId)).sourceDesc("用户角色覆盖 userId=" + userId).build();
        manageWrite(() -> {
            userRoleService.replaceRoles(userId, toEntities(assigns), UserContext.get().getUserId());
            return null;
        }, plan, auditLog("replaceUserRoles", "用户 " + userId + " 角色覆盖 → " + assignDesc(assigns),
                OperTypeEnum.GRANT, "ums_user_role", userId, diff));
    }
```

`assignRolesBatch` 方法整体替换（逐用户 diff：已绑该角色者不再记 added）：

```java
    public void assignRolesBatch(List<Long> userIds, Long roleId, LocalDateTime begin, LocalDateTime end) {
        if (begin != null && end != null && !begin.isBefore(end)) {
            throw new BizException(ResultCode.BAD_REQUEST, "begin 必须早于 end");
        }
        // 逐用户 diff（等保要变更内容，规格附录「每用户 added roleIds」）；已绑该角色者无新增不记
        List<Map<String, Object>> diff = new ArrayList<>();
        for (Long uid : userIds) {
            Set<Long> before = new LinkedHashSet<>();
            for (UmsUserRole ur : userRoleService.listEffectiveByUserId(uid)) {
                before.add(ur.getRoleId());
            }
            if (before.contains(roleId)) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("field", "roleIds");
            entry.put("userId", uid);
            entry.put("op", "add");
            entry.put("ids", List.of(roleId));
            diff.add(entry);
        }
        EvictPlan plan = EvictPlan.builder().userIds(userIds).sourceDesc("批量授角色 roleId=" + roleId).build();
        manageWrite(() -> {
            for (Long uid : userIds) {
                userRoleService.assignRole(uid, roleId, UserContext.get().getUserId(), begin, end);
            }
            return null;
        }, plan, auditLog("assignRolesBatch", "批量授角色 roleId=" + roleId + " 用户数=" + userIds.size(),
                OperTypeEnum.GRANT, "ums_user_role", roleId, diff));
    }
```

`removeUserRole` 方法整体替换（关联集 remove diff）：

```java
    public boolean removeUserRole(Long userId, Long roleId) {
        List<Map<String, Object>> diff = List.of(Map.of("field", "roleIds", "op", "remove", "ids", List.of(roleId)));
        EvictPlan plan = EvictPlan.builder().userIds(List.of(userId)).sourceDesc("解绑角色 userId=" + userId + " roleId=" + roleId).build();
        return manageWrite(() -> userRoleService.removeUserRole(userId, roleId),
                plan, auditLog("removeUserRole", "解绑", OperTypeEnum.DELETE, "ums_user_role", userId, diff));
    }
```

> 注：`removeUserRole` 是 DELETE 语义，但关联集变更（移除一个角色绑定）按规格附录记 removed roleId——DELETE 无字段 diff 仅指行删除；绑定解绑记录变更内容。

- [ ] **Step 5: 角色-权限 GRANT/REVOKE 就地 diff（权限集 added/removed，规格附录）**

`replaceRolePermissions` 方法体中 `revokeByRoleId` 之前算 diff、manageWrite 传 diff：

```java
        Set<Long> before = new LinkedHashSet<>(rolePermissionService.getPermissionIdsByRoleId(roleId));
        Set<Long> after = permissionIds == null ? new LinkedHashSet<>() : new LinkedHashSet<>(permissionIds);
        List<Map<String, Object>> diff = DiffUtils.diffIdSet("permissionIds", before, after);
        EvictPlan plan = EvictPlan.builder().roleId(roleId).sourceDesc("角色权限覆盖 roleId=" + roleId).build();
        manageWrite(() -> {
            rolePermissionService.revokeByRoleId(roleId);
            rolePermissionService.grantBatch(roleId, permissionIds, UserContext.get().getUserId());
            return null;
        }, plan, auditLog("replaceRolePermissions", "角色 " + roleId + " 权限覆盖 → " + permissionIds.size() + " 条",
                OperTypeEnum.GRANT, "ums_role_permission", roleId, diff));
```

`grantRolePermissions` 方法整体替换（增量授权记 added 权限 id）：

```java
    public void grantRolePermissions(Long roleId, List<Long> permissionIds) {
        Set<Long> before = new LinkedHashSet<>(rolePermissionService.getPermissionIdsByRoleId(roleId));
        List<Long> added = new ArrayList<>();
        for (Long pid : permissionIds) {
            if (!before.contains(pid)) {
                added.add(pid);
            }
        }
        List<Map<String, Object>> diff = added.isEmpty() ? List.of()
                : List.of(Map.of("field", "permissionIds", "op", "add", "ids", added));
        EvictPlan plan = EvictPlan.builder().roleId(roleId).sourceDesc("角色增量授权 roleId=" + roleId).build();
        manageWrite(() -> {
            rolePermissionService.grantBatch(roleId, permissionIds, UserContext.get().getUserId());
            return null;
        }, plan, auditLog("grantRolePermissions", "角色 " + roleId + " 增量授权 " + permissionIds.size() + " 条",
                OperTypeEnum.GRANT, "ums_role_permission", roleId, diff));
    }
```

`revokeRolePermissions` 方法整体替换（回收记 removed 权限 id）：

```java
    public void revokeRolePermissions(Long roleId, List<Long> permissionIds) {
        Set<Long> before = new LinkedHashSet<>(rolePermissionService.getPermissionIdsByRoleId(roleId));
        List<Long> removed = new ArrayList<>();
        for (Long pid : permissionIds) {
            if (before.contains(pid)) {
                removed.add(pid);
            }
        }
        List<Map<String, Object>> diff = removed.isEmpty() ? List.of()
                : List.of(Map.of("field", "permissionIds", "op", "remove", "ids", removed));
        EvictPlan plan = EvictPlan.builder().roleId(roleId).sourceDesc("角色回收权限 roleId=" + roleId).build();
        manageWrite(() -> {
            for (Long pid : permissionIds) {
                rolePermissionService.revoke(roleId, pid);
            }
            return null;
        }, plan, auditLog("revokeRolePermissions", "角色 " + roleId + " 回收权限 " + permissionIds.size() + " 条",
                OperTypeEnum.DELETE, "ums_role_permission", roleId, diff));
    }
```

`importRoles` 覆盖分支（overwrite）中 `Set<Long> before` 之后加 diff、manageWrite 传 diff（该分支已算 `before` 集，复用）：

```java
                Set<Long> before = new HashSet<>(rolePermissionService.getPermissionIdsByRoleId(role.getId()));
                boolean changed = !before.equals(new HashSet<>(validPermIds));
                List<Map<String, Object>> diff = DiffUtils.diffIdSet("permissionIds", before, new LinkedHashSet<>(validPermIds));
                EvictPlan plan = changed
                        ? EvictPlan.builder().roleId(role.getId()).sourceDesc("角色导入覆盖 roleId=" + role.getId()).build()
                        : null;
                manageWrite(() -> {
                    rolePermissionService.revokeByRoleId(role.getId());
                    if (!validPermIds.isEmpty()) {
                        rolePermissionService.grantBatch(role.getId(), validPermIds, UserContext.get().getUserId());
                    }
                    return null;
                }, plan, OperLogReq.builder().module("rbac").action("importRoles").desc("覆盖导入角色 " + it.getRoleCode() + " 权限变化=" + changed)
                        .type(OperTypeEnum.IMPORT).success(true)
                        .targetEntity("ums_role").targetId(role.getId())
                        .changeDiff(DiffUtils.toChangeDiffJson(diff)).build());
```

- [ ] **Step 6: CREATE/DELETE 调用点补 target（无 diff）**

按下表替换各 `OperLogReq.builder()` 链（`desc`/`type` 保持原样，仅追加 target 链段）：

| 方法 | 追加调用 |
|------|----------|
| `createRole` | `.targetEntity("ums_role").targetId(role.getId())` |
| `deleteRole` | `.targetEntity("ums_role").targetId(id)` |
| `cloneRole` | `.targetEntity("ums_role").targetId(id)` |
| `importRoles`（新增分支） | `.targetEntity("ums_role").targetId(nr.getId())` |
| `createPermission` | `.targetEntity("ums_permission").targetId(p.getId())` |
| `deletePermission` | `.targetEntity("ums_permission").targetId(id)` |

> 注：`createRole`/`createPermission`/`cloneRole`/`importRoles` 新增分支的 targetId 依赖插入主键回填——`manageWrite` 的 action lambda 在 `record` 之前执行，`role.getId()`/`p.getId()`/`nr.getId()` 此时已回填（`MpBaseServiceImpl.insertEntityWithBackfill` 保证）。`evictLog` 不加 target（evict 不 diff，规格附录）。

- [ ] **Step 7: RbacManageServiceTest 补 diff 断言**

`RbacManageServiceTest.java` 新增 import（`java.util.Map` 与 `ArgumentCaptor` 已有或按需补）：

```java
import org.mockito.ArgumentCaptor;
```

新增用例（沿用既有 setUp mock 装配，`operLogService` 为 mock，用 `ArgumentCaptor<OperLogReq>` 捕获断言 `changeDiff`）：

```java
    @Test
    void updateRoleStatusRecordsStatusDiff() {
        UmsRole r = role(10L);
        when(roleService.getById(10L)).thenReturn(r);
        when(userRoleService.countUserIdsByRoleId(10L)).thenReturn(1L);

        facade.updateRoleStatus(10L, RoleStatusEnum.DISABLED);

        ArgumentCaptor<OperLogReq> captor = ArgumentCaptor.forClass(OperLogReq.class);
        verify(operLogService).record(captor.capture());
        OperLogReq req = captor.getValue();
        assertEquals("ums_role", req.getTargetEntity());
        assertEquals(10L, req.getTargetId());
        assertTrue(req.getChangeDiff().contains("\"field\":\"status\""));
        assertTrue(req.getChangeDiff().contains("\"new\":\"0\""));
    }

    @Test
    void replaceUserRolesRecordsRoleIdsSetDiff() {
        UserRoleAssignDTO dto = new UserRoleAssignDTO();
        dto.setRoleId(5L);
        UmsUserRole existing = new UmsUserRole();
        existing.setRoleId(3L);
        existing.setUserId(USER_ID);
        when(userRoleService.listEffectiveByUserId(USER_ID)).thenReturn(List.of(existing));

        facade.replaceUserRoles(USER_ID, List.of(dto));

        ArgumentCaptor<OperLogReq> captor = ArgumentCaptor.forClass(OperLogReq.class);
        verify(operLogService).record(captor.capture());
        OperLogReq req = captor.getValue();
        assertEquals("ums_user_role", req.getTargetEntity());
        assertEquals(USER_ID, req.getTargetId());
        assertTrue(req.getChangeDiff().contains("\"op\":\"remove\""));
        assertTrue(req.getChangeDiff().contains("\"ids\":[3]"));
        assertTrue(req.getChangeDiff().contains("\"op\":\"add\""));
        assertTrue(req.getChangeDiff().contains("\"ids\":[5]"));
    }

    @Test
    void renewUserRoleRecordsEndTimeDiffWithBindIdTarget() {
        UmsUserRole bind = new UmsUserRole();
        bind.setId(77L);
        bind.setUserId(USER_ID);
        bind.setRoleId(10L);
        bind.setEndTime(LocalDateTime.of(2026, 12, 31, 23, 59));
        when(userRoleService.findByUserIdAndRoleId(USER_ID, 10L)).thenReturn(bind);
        when(userRoleService.renew(USER_ID, 10L, LocalDateTime.of(2027, 12, 31, 23, 59))).thenReturn(true);

        facade.renewUserRole(USER_ID, 10L, LocalDateTime.of(2027, 12, 31, 23, 59));

        ArgumentCaptor<OperLogReq> captor = ArgumentCaptor.forClass(OperLogReq.class);
        verify(operLogService).record(captor.capture());
        OperLogReq req = captor.getValue();
        assertEquals(77L, req.getTargetId());   // target = 绑定行 id（bindId，规格附录）
        assertTrue(req.getChangeDiff().contains("\"field\":\"endTime\""));
        assertTrue(req.getChangeDiff().contains("\"old\":\"2026-12-31T23:59\""));
        assertTrue(req.getChangeDiff().contains("\"new\":\"2027-12-31T23:59\""));
    }
```

> 注：`renewUserRoleTest` 用例中 `LocalDateTime.of(...)` 序列化为 ISO-8601 字符串（`toString`），断言按 `2026-12-31T23:59` 形态；若门面 desc/format 有出入以实际 `toString` 输出为准。既有七用例断言全部保留，仅新增上述用例。

- [ ] **Step 8: 编译 + 全量回归**

Run: `& "D:\Tool\apache-maven-3.8.8\bin\mvn" test -q`
Expected: 全部 PASS（既有 `RbacManageServiceTest` 断言不受影响；新用例按 diff 输出补充断言）。

- [ ] **Step 9: Commit**

```powershell
git add src/main/java/com/sanye/strategy/application/rbac/RbacManageService.java src/test/java/com/sanye/strategy/application/rbac/RbacManageServiceTest.java
git commit -m "[feat] 日志阶段0：RBAC 审计调用点补 target + 字段 diff（9 类 UPDATE + 权限集 GRANT/REVOKE）"
```

---

### Task 10: 安全事件基础设施（SecurityEventLogger + IP 分级）

**Files:**
- Create: `src/main/java/com/sanye/strategy/infrastructure/logging/SecurityEventLogger.java`
- Test: `src/test/java/com/sanye/strategy/infrastructure/logging/SecurityEventLoggerTest.java`

**Interfaces:**
- Consumes: Task 4 的 `SECURITY` logger（logback 路由 security.log）；Task 2 的 `IpMaskUtils.maskLastSegment`。
- Produces: `SecurityEventLogger.log(String securityType, String account, String ip, String result, String detail)` —— `securityType` 取规格 4.2 五值（`authn`/`authz`/`account`/`credential`/`anomaly`）；IP 分级：`authz`/`anomaly`/`account` 为高威胁**完整 IP 保留**，`authn`/`credential` 末段掩码（规格 6.3）。Task 11 的 AuthService/拦截器调用。

- [ ] **Step 1: 写失败测试**

```java
package com.sanye.strategy.infrastructure.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValue;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SecurityEventLogger 单测 — IP 分级策略（高威胁完整/普通掩码）+ 结构化 kv 输出
 */
class SecurityEventLoggerTest {

    private SecurityEventLogger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = new SecurityEventLogger();
        Logger securityLogger = (Logger) LoggerFactory.getLogger("SECURITY");
        appender = new ListAppender<>();
        appender.start();
        securityLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger("SECURITY")).detachAppender(appender);
    }

    private Optional<KeyValue> kv(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream().filter(p -> key.equals(p.key)).findFirst();
    }

    @Test
    void authzKeepsFullIp() {
        logger.log("authz", "user1", "10.1.2.3", "DENY", "越权访问 /rbac/roles");
        ILoggingEvent event = appender.list.get(0);
        assertEquals("10.1.2.3", kv(event, "ip").orElseThrow().value);
        assertEquals("authz", kv(event, "securityType").orElseThrow().value);
        assertEquals("DENY", kv(event, "result").orElseThrow().value);
    }

    @Test
    void accountAndAnomalyKeepFullIp() {
        logger.log("account", "user1", "10.1.2.3", "LOCKED", "密码错 5 次锁定");
        assertEquals("10.1.2.3", kv(appender.list.get(0), "ip").orElseThrow().value);
        logger.log("anomaly", "user1", "10.1.2.4", "RISK", "风控触发");
        assertEquals("10.1.2.4", kv(appender.list.get(1), "ip").orElseThrow().value);
    }

    @Test
    void authnMasksIpLastSegment() {
        logger.log("authn", "user1", "10.1.2.3", "SUCCESS", "登录成功");
        assertEquals("10.1.2.***", kv(appender.list.get(0), "ip").orElseThrow().value);
    }

    @Test
    void credentialMasksIpLastSegment() {
        logger.log("credential", "user1", "10.1.2.3", "SUCCESS", "改密");
        assertEquals("10.1.2.***", kv(appender.list.get(0), "ip").orElseThrow().value);
    }

    @Test
    void messageContainsAccount() {
        logger.log("authn", "user1", "10.1.2.3", "FAIL", "密码错误");
        assertTrue(appender.list.get(0).getFormattedMessage().contains("user1"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `& "D:\Tool\apache-maven-3.8.8\bin\mvn" test "-Dtest=SecurityEventLoggerTest" -q`
Expected: 编译失败（`SecurityEventLogger` 不存在）。

- [ ] **Step 3: 实现**

```java
package com.sanye.strategy.infrastructure.logging;

import com.sanye.strategy.common.util.IpMaskUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValue;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * <p>
 * 安全事件日志器 — 事件轨（category=security）产生端唯一出口（规格 4.2/6.3）
 * </p>
 * <p>
 * 经 {@code SECURITY} logger 输出结构化事件（logback 路由 security.log，不挂 ValueMasker——
 * 事件轨结构化敏感字段禁碰，规格 6.1）。IP 分级策略产生端完成：
 * 高威胁（authz 越权 / anomaly 风控 / account 锁定冻结）完整原始 IP 保留（攻击溯源取证）；
 * 普通事件（authn 登录 / credential 凭据变更）末段掩码（规格 6.3）。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：安全事件收口组件，AuthService/拦截器经本类记录，禁止散落直接打 SECURITY logger。</li>
 *   <li>优缺点：IP 分级与 securityType 词表收口一处、口径统一；
 *       代价为调用方须按五类语义选型（选型错误仅影响 IP 粒度，不影响事件留痕）。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Component
public class SecurityEventLogger {

    private static final Logger SECURITY_LOG = LoggerFactory.getLogger("SECURITY");

    /**
     * 高威胁事件类型（完整 IP 保留，规格 6.3）
     */
    private static final Set<String> HIGH_THREAT_TYPES = Set.of("authz", "anomaly", "account");

    /**
     * 记录安全事件
     *
     * @param securityType 事件子类型（authn/authz/account/credential/anomaly，规格 4.2）
     * @param account      账号标识（用户名等；失败事件无 userId 时靠账号文本定位）
     * @param ip           客户端 IP（分级处理见类说明）
     * @param result       结果（SUCCESS/FAIL/LOCKED/DENY 等受控词）
     * @param detail       补充说明
     */
    public void log(String securityType, String account, String ip, String result, String detail) {
        String effectiveIp = HIGH_THREAT_TYPES.contains(securityType)
                ? ip
                : IpMaskUtils.maskLastSegment(ip);
        SECURITY_LOG.atWarn()
                .addKeyValue(KeyValue.of("securityType", securityType))
                .addKeyValue(KeyValue.of("account", account))
                .addKeyValue(KeyValue.of("ip", effectiveIp))
                .addKeyValue(KeyValue.of("result", result))
                .addKeyValue(KeyValue.of("detail", detail))
                .log("security event: " + securityType + " account=" + account + " result=" + result);
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `& "D:\Tool\apache-maven-3.8.8\bin\mvn" test "-Dtest=SecurityEventLoggerTest" -q`
Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/sanye/strategy/infrastructure/logging/SecurityEventLogger.java src/test/java/com/sanye/strategy/infrastructure/logging/SecurityEventLoggerTest.java
git commit -m "[feat] 日志阶段0：安全事件日志器（IP 分级策略产生端完成）"
```

---

### Task 11: 认证域安全事件埋点（AuthService + 双拦截器）

**Files:**
- Modify: `src/main/java/com/sanye/strategy/application/auth/AuthService.java`
- Modify: `src/main/java/com/sanye/strategy/infrastructure/interceptor/TokenAuthInterceptor.java`
- Modify: `src/main/java/com/sanye/strategy/infrastructure/interceptor/PermissionInterceptor.java`
- Modify: `src/test/java/com/sanye/strategy/infrastructure/interceptor/PermissionInterceptorTest.java`（构造适配）

**Interfaces:**
- Consumes: Task 10 的 `SecurityEventLogger.log(securityType, account, ip, result, detail)`。
- Produces: 认证域全分支安全事件留痕——authn（登录成功/失败/MFA/刷新失败/token 校验失败）、account（冻结/注销/锁定）、authz（越权 403，完整 IP）。失败事件无 UserContext 时以账号文本定位（规格 4.2 消费方为告警引擎）。

- [ ] **Step 1: AuthService 注入并埋点**

`AuthService.java` 改造：

新增 import 与依赖（构造注入，`@RequiredArgsConstructor` 自动纳入）：

```java
import com.sanye.strategy.infrastructure.logging.SecurityEventLogger;
```

```java
    private final SecurityEventLogger securityEventLogger;
```

各埋点位置（在对应 `throw` 之前或事务成功返回前插入一行）：

`register` —— 事务成功返回前（`return transactionTemplate.execute(...)` 改为先接收结果）：

```java
        TokenVO vo = transactionTemplate.execute(status -> {
            userService.insert(user);
            initSecurity(user.getId());
            initProfile(user.getId());
            assignDefaultRole(user.getId());
            // 注册即登录：会话行落渠道 + 登入方式（注册走账号密码）
            UmsUserLoginDevice session = deviceService.createSession(
                    user.getId(), dto.getDeviceInfo(), clientIp, refreshToken, REFRESH_TOKEN_TTL_DAYS,
                    LoginTypeEnum.PASSWORD, channel);
            return issueTokens(user.getId(), loadRoleCodes(user.getId()), loadPermCodes(user.getId()),
                    session.getId(), session.getDeviceId(), refreshToken);
        });
        securityEventLogger.log("authn", dto.getUsername(), clientIp, "REGISTER_OK", "注册成功");
        return vo;
```

`login` —— 四个分支：

```java
        UmsUser user = findByAccount(dto.getAccount(), loginType);
        if (user == null) {
            securityEventLogger.log("authn", dto.getAccount(), clientIp, "FAIL", "账号不存在");
            throw new BizException(ResultCode.UNAUTHORIZED, INVALID_ACCOUNT_MESSAGE);
        }
```

```java
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            increaseErrorCount(security);
            securityEventLogger.log("authn", dto.getAccount(), clientIp, "FAIL", "密码错误");
            throw new BizException(ResultCode.UNAUTHORIZED, INVALID_ACCOUNT_MESSAGE);
        }
```

```java
            securityEventLogger.log("authn", dto.getAccount(), clientIp, "MFA_CHALLENGE", "MFA 挑战签发");
            throw new BizException(ResultCode.MFA_REQUIRED, "请完成二次验证", challenge);
```

```java
        String refreshToken = generateRefreshToken();
        TokenVO vo = transactionTemplate.execute(status -> {
            clearErrorCount(security);
            UmsUserLoginDevice session = deviceService.createSession(
                    user.getId(), dto.getDeviceInfo(), clientIp, refreshToken, REFRESH_TOKEN_TTL_DAYS,
                    loginType, channel);
            updateLastLogin(user, dto.getDeviceInfo(), clientIp);
            return issueTokens(user.getId(), loadRoleCodes(user.getId()), loadPermCodes(user.getId()),
                    session.getId(), session.getDeviceId(), refreshToken);
        });
        securityEventLogger.log("authn", dto.getAccount(), clientIp, "SUCCESS", "登录成功");
        return vo;
```

（原 `return transactionTemplate.execute(...)` 改为 `TokenVO vo = ...` + 埋点 + `return vo;`）

`verifyMfa` —— 挑战过期/设备不符/OTP 错/成功四处，account 取 `user == null ? "unknown" : user.getUsername()`（挑战过期分支 user 未解出，落 `String.valueOf(binding == null ? null : binding.userId())`）：

```java
        ChallengeTokenService.ChallengeBinding binding = challengeTokenService.consume(dto.getTempToken());
        if (binding == null) {
            securityEventLogger.log("authn", "unknown", clientIp, "FAIL", "MFA 挑战过期或已消费");
            throw new BizException(ResultCode.MFA_CHALLENGE_EXPIRED);
        }
        // 2. 绑定 deviceId 与请求比对（防跨设备复用）
        if (!binding.deviceId().equals(dto.getDeviceInfo().getDeviceId())) {
            securityEventLogger.log("authn", String.valueOf(binding.userId()), clientIp, "FAIL", "MFA 挑战设备不符");
            throw new BizException(ResultCode.MFA_CHALLENGE_EXPIRED, "挑战凭证与当前设备不符");
        }
```

```java
        if (!totpUtil.verify(security.getMfaSecret(), dto.getCode())) {
            // 与密码共用防爆破：错 5 次锁 30min；挑战已消费，重试须重新登录
            increaseErrorCount(security);
            securityEventLogger.log("authn", user.getUsername(), clientIp, "FAIL", "MFA 验证码错误");
            throw new BizException(ResultCode.UNAUTHORIZED, "验证码错误");
        }
```

verifyMfa 成功分支同 login 模式（`TokenVO vo = ...` + `securityEventLogger.log("authn", user.getUsername(), clientIp, "SUCCESS", "MFA 验证成功")` + `return vo;`）。

`checkUserStatus` / `checkLocked`（account 类，高威胁完整 IP——方法无 ip 参数，改签名加入）：

```java
    private void checkUserStatus(UmsUser user, String clientIp) {
        if (user.getUserStatus() == UserStatusEnum.FROZEN) {
            securityEventLogger.log("account", user.getUsername(), clientIp, "FROZEN", "冻结账号尝试登录");
            throw new BizException(ResultCode.ACCOUNT_DISABLED, "账号已冻结");
        }
        if (user.getUserStatus() == UserStatusEnum.CANCELLED) {
            securityEventLogger.log("account", user.getUsername(), clientIp, "CANCELLED", "注销账号尝试登录");
            throw new BizException(ResultCode.ACCOUNT_DELETED, "账号已注销");
        }
    }
```

```java
    private void checkLocked(UmsUserAccountSecurity security, Long userId, String clientIp) {
        if (security.getLockTime() != null && LocalDateTime.now().isBefore(security.getLockTime())) {
            securityEventLogger.log("account", String.valueOf(userId), clientIp, "LOCKED", "锁定账号尝试登录");
            throw new BizException(ResultCode.ACCOUNT_LOCKED, "账号已锁定，请稍后再试");
        }
    }
```

调用点同步改：`login` 中 `checkUserStatus(user)` → `checkUserStatus(user, clientIp)`、`checkLocked(security)` → `checkLocked(security, user.getId(), clientIp)`；`verifyMfa` 中 `checkLocked(security)` → `checkLocked(security, user.getId(), clientIp)`、`checkUserStatus(user)` → `checkUserStatus(user, clientIp)`；`refresh` 中 `checkUserStatus(user)` → `checkUserStatus(user, null)`（refresh 无 IP 入参，落 null 由 IpMaskUtils 原样返回）。

`increaseErrorCount` 达阈值锁定后记 account 事件——在方法内两处 `security.setLockTime(...)`/`current.setLockTime(...)` 之后各加：

```java
                    securityEventLogger.log("account", String.valueOf(security.getUserId()), null, "LOCKED", "密码/OTP 错误达阈值锁定 30min");
```

（第二处用 `current.getUserId()`。）

`refresh` —— 四个 TOKEN_EXPIRED 分支各加一行（account 取 `String.valueOf(session.getUserId())`，无 IP 传 null）：

```java
            securityEventLogger.log("authn", String.valueOf(session.getUserId()), null, "FAIL", "refresh 会话已失效");
```

（其余三分支 detail 分别为 `"refresh 会话已过期"`/`"refresh 会话已吊销"`/`"refresh 轮换竞态失效"`；`user == null` 分支 account 落 `"unknown"`。）

`logout` —— `jtiBlacklistService.revoke` 之后：

```java
        securityEventLogger.log("authn", String.valueOf(context.getUserId()), null, "LOGOUT", "登出");
```

- [ ] **Step 2: TokenAuthInterceptor 埋点（token 校验失败 = authn）**

构造注入 `SecurityEventLogger`（`@RequiredArgsConstructor` 自动纳入，新增 import `com.sanye.strategy.infrastructure.logging.SecurityEventLogger` 与 `com.sanye.strategy.common.util.IpUtils`）。

`preHandle` 中四处拒绝点前插入（IP 取 `IpUtils.getClientIp(request)`）：

```java
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith(BEARER_PREFIX)) {
            securityEventLogger.log("authn", "anonymous", IpUtils.getClientIp(request), "FAIL", "缺失或非法 Authorization 头");
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
```

```java
            if (!TYPE_ACCESS.equals(claims.get("type", String.class))) {
                securityEventLogger.log("authn", "anonymous", IpUtils.getClientIp(request), "FAIL", "token 类型非 ACCESS");
                throw new BizException(ResultCode.UNAUTHORIZED);
            }
```

```java
            if (jtiBlacklistService.isRevoked(jti)) {
                securityEventLogger.log("authn", String.valueOf(jti), IpUtils.getClientIp(request), "FAIL", "jti 黑名单命中");
                throw new BizException(ResultCode.TOKEN_EXPIRED, "登录已失效，请重新登录");
            }
```

> 注：jti 黑名单分支在 `userId` 解析之前，account 落 jti 串（该埋点置于 `Long userId = ...` 语句之前）。

catch 兜底分支：

```java
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            // 验签失败 / 算法不符 / 过期 / 签名合法但 claim 缺失或类型不符（非数值 jti、缺 userId 等）
            // 统一按 401 收敛，不落 500
            securityEventLogger.log("authn", "anonymous", IpUtils.getClientIp(request), "FAIL", "token 校验失败");
            throw new BizException(ResultCode.UNAUTHORIZED, "登录已过期，请重新登录");
        }
```

- [ ] **Step 3: PermissionInterceptor 埋点（越权 = authz，完整 IP）**

构造注入 `SecurityEventLogger`（无 `@RequiredArgsConstructor`，显式加构造器）+ import `SecurityEventLogger`/`IpUtils`/`UserContext` 已有：

```java
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private static final String RBAC_PATH_PREFIX = "/rbac/";
    private static final String SUPER_ADMIN = "SUPER_ADMIN";

    private final SecurityEventLogger securityEventLogger;

    public PermissionInterceptor(SecurityEventLogger securityEventLogger) {
        this.securityEventLogger = securityEventLogger;
    }
```

三处拒绝点前插入（authz 为高威胁，SecurityEventLogger 内部保留完整 IP）：

```java
            if (uri != null && uri.startsWith(RBAC_PATH_PREFIX)) {
                securityEventLogger.log("authz", "anonymous", IpUtils.getClientIp(request), "DENY", "接口未配置权限点 uri=" + uri);
                throw new BizException(ResultCode.FORBIDDEN, "接口未配置权限点");
            }
```

```java
        if (ctx == null) {
            securityEventLogger.log("authz", "anonymous", IpUtils.getClientIp(request), "DENY", "无用户上下文访问受保护接口");
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
```

```java
        if (ctx.getPermCodes().contains(requires.value())) { return true; }
        securityEventLogger.log("authz", String.valueOf(ctx.getUserId()), IpUtils.getClientIp(request), "DENY", "权限不足 perm=" + requires.value());
        throw new BizException(ResultCode.FORBIDDEN);
```

- [ ] **Step 4: 适配 PermissionInterceptorTest**

`PermissionInterceptorTest` 中 `new PermissionInterceptor()` 改为 `new PermissionInterceptor(mock(SecurityEventLogger.class))`（import `com.sanye.strategy.infrastructure.logging.SecurityEventLogger` 与 `org.mockito.Mockito.mock`）。既有断言全部保留。

- [ ] **Step 5: 全量回归**

Run: `& "D:\Tool\apache-maven-3.8.8\bin\mvn" test -q`
Expected: 全部 PASS。

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/sanye/strategy/application/auth/AuthService.java src/main/java/com/sanye/strategy/infrastructure/interceptor/TokenAuthInterceptor.java src/main/java/com/sanye/strategy/infrastructure/interceptor/PermissionInterceptor.java src/test/java/com/sanye/strategy/infrastructure/interceptor/PermissionInterceptorTest.java
git commit -m "[feat] 日志阶段0：认证域安全事件埋点（authn/account/authz + IP 分级）"
```

---

### Task 12: 端到端冒烟 + CLAUDE.md 状态更新

**Files:**
- Modify: `CLAUDE.md`（阶段0 状态行与待办表）

**Interfaces:**
- Consumes: Task 1-11 全部产物。
- Produces: 阶段0 验收证据（六类文件生成、traceId 贯通、脱敏生效、审计双写 + change_diff、安全事件落盘）+ 文档状态同步。

- [ ] **Step 1: 启动应用**

Run: `& "D:\Tool\apache-maven-3.8.8\bin\mvn" spring-boot:run`（非阻塞，等待 `Started StrategyApplication`）

- [ ] **Step 2: 冒烟用例**

依次执行并验证（PowerShell；登录/注册参数按 `LoginDTO`/`RegisterDTO` 现有字段组装）：

1. `Invoke-RestMethod -Uri "http://localhost:8080/actuator/health"`
   Expected: `logs/access.log` 新增一行 JSON，含 `"category":"access"`（customFields）、`"uri":"/actuator/health"`、`"ip":"<掩码后>"`、`"traceId"` 字段。
2. 注册新用户（`POST /auth/register`，body 含合法 username/password/registerChannel/deviceInfo）
   Expected: `logs/security.log` 出现 `"securityType":"authn"` + `"result":"REGISTER_OK"`，ip 末段掩码；`logs/business.log` 同 traceId 可关联。
3. 错误密码登录（`POST /auth/login`）
   Expected: `security.log` `"result":"FAIL"` + `"detail":"密码错误"`，ip 掩码。
4. 连续 5 次错误密码
   Expected: `security.log` 出现 `"result":"LOCKED"`（account 类，ip **完整**）。
5. 正确登录 → 携带 accessToken 访问任一 `/rbac/**` 无权限接口（非 SUPER_ADMIN）
   Expected: `security.log` `"securityType":"authz"` + `"result":"DENY"`，ip **完整**。
6. 以 SUPER_ADMIN 执行一次 `PUT /rbac/roles/{id}`（改 remark）
   Expected: `logs/audit.log` 新增一行 JSON，含 `"traceId"`（与 business.log 同请求 traceId 一致）、`"targetEntity":"ums_role"`、`"targetId"`、`"operatorType":1`、`"changeDiff"`（数组字符串含 `"field":"remark"`）；MySQL `ums_oper_log` 同 action 行五新列有值（`change_diff` 非空）。
7. 无 token 访问受保护接口
   Expected: `security.log` `"detail":"缺失或非法 Authorization 头"`。

- [ ] **Step 3: 停止应用，更新 CLAUDE.md**

`CLAUDE.md` 两处修改：

1. 「已知缺陷与待办」表中日志行，将「阶段0（产生端：Tracing + logback 结构化分文件 + 脱敏）未实施」改为「阶段0 已实施（Tracing + logback JSON 六类分文件 + 产生端脱敏 + 审计 trace_id/target/operator_type + 字段 diff change_diff + audit.log 双写 + 安全事件埋点）」。
2. 「新增代码注意事项」中「日志产生端改造（Micrometer Tracing + logback 结构化分文件 + 脱敏）为阶段0，待实施」改为「日志产生端（阶段0）已落地，含审计字段 diff（`DiffUtils` + RBAC 门面就地 diff），见 `docs/superpowers/plans/2026-08-23-log-phase0-producer.md`；阶段1（Vector 采集）起日志文件经 hostPath 挂载采集」。

- [ ] **Step 4: Commit**

```powershell
git add CLAUDE.md
git commit -m "[docs] 日志阶段0：CLAUDE.md 状态更新（产生端含 diff 已实施）"
```

---

## 自检记录

**规格覆盖（阶段0 范围）：**
- 5.1 产生端（JSON/分文件/traceId/脱敏/限大小）→ Task 1/4/5；大报文限制经 AccessLogFilter 不记 body + 审计不存请求体体现
- 6.1 双保险第一道 → Task 3/4；第二道（Vector PII 正则）属阶段1，计划外
- 6.2 两级语义 → Task 3（剔除/掩码）+ Task 8（diff 复用）
- 6.3 IP 分级 → Task 2/10（SecurityEventLogger）/11（埋点）
- 6.4 单一源 → Task 3 SensitiveFields，ValueMasker 与 DiffUtils 共用（Task 3 静态助手 + Task 8）
- 7.1 字段 diff → Task 6（change_diff 列）+ Task 8（DiffUtils）+ Task 9（门面就地 diff 13 处）；忽略 id/审计字段、new null/相同不报、关联集 added/removed 均覆盖
- 7.2 target 元数据 → Task 6/7/9
- 7.3 traceId 不信任调用方 → Task 7（MDC 取）
- 7.4 operator_type 逻辑推导 → Task 7
- 7.5 凭据剔除 → Task 3 CredentialValueMasker + Task 8 diff changed 占位
- 7.6 WORM 权威链产生端 → Task 4 audit appender + Task 7 双写（MinIO Object Lock 属阶段3）
- 4.2 securityType 五值 → Task 10/11
- 附录 RBAC 管理面 diff 范围 → Task 9：9 类 UPDATE 全部覆盖（updateRole/updateRoleStatus/updatePermission/updatePermissionStatus/renewUserRole/renewBatch/replaceUserRoles/assignRolesBatch/removeUserRole）+ 权限集 GRANT/REVOKE（replaceRolePermissions/grantRolePermissions/revokeRolePermissions/importRoles 覆盖分支）added/removed；CREATE/DELETE 仅 target；evict 不 diff
- 反模式禁止 → Task 3/8/10/11 全程（SensitiveFields 单一源、高威胁事件完整 IP、事件轨不挂 ValueMasker）

**偏差与校正（相对原计划）：**
- 原 Task 3 测试以 null context 调 mask 但 ValueMasker 字段名驱动——改为 Mockito mock context 提供字段名（Task 3 Step 1/2 修正）
- 原 Task 8（现 Task 9）renewUserRole target 误写 userId——按规格附录校正为绑定行 id（bindId），diff 前置新增 `findByUserIdAndRoleId` 契约（Task 8 Step 5）
- 原计划「字段 diff 暂缓」注记移除；任务重新编号（新增 Task 8 DiffUtils，Task 8→9、9→10、10→11、11→12）

**遗留到后续阶段（非本计划缺口）：** 中间件轨慢查询埋点（appender 已建）、Vector/Kafka/ES/MinIO（阶段1-4）、IPv6 掩码定稿（待决事项 4，interim 已实现）、`ums_oper_log` 应用账号 INSERT/SELECT-only 权限收紧（规格 7.6，DB 运维动作，非代码）。

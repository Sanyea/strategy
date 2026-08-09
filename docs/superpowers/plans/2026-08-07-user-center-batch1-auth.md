# 用户中心批1（认证主链 + DDL）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 打通认证主链闭环——注册 / 登录 / 刷新 / 登出 / MFA 二次验证，双 Token（JWT access + 不透明 refresh），jti 吊销黑名单（Redis），全程零 Spring Security。

**Architecture:** 在既有 DIP 分层（`Controller → 门面 AuthService/DeviceService → 5 个 base service → MpBaseServiceImpl → BaseMapper`）上新增 `auth` / `device` 两个能力包与 `common/auth`、`common/interceptor` 支撑件。认证管道走 Spring MVC `HandlerInterceptor`（责任链），accessToken 无状态 JWT 验签 + Redis jti 黑名单即时吊销；refreshToken 只存 SHA-256 哈希于 `ums_user_login_device.refresh_token_hash`。跨表写（注册/登录成功段）经共享 `TransactionTemplate`（`TransactionConfig`）编排。

**Tech Stack:** Java 21、Spring Boot 4.1.0、MyBatis-Plus 3.5.15（boot4-starter）、jjwt 0.12.6、jbcrypt 0.4、spring-boot-starter-data-redis（Lettuce）、MySQL、JUnit 5 + Mockito + AssertJ（测试）。

## Global Constraints

- **分支**：所有改动与 `git commit` 仅在 `AI` 分支执行，禁止在 `dev`/`main` 提交。
- **构建命令**：本机 Maven 需在 PATH（`D:\Tool\apache-maven-3.8.8\bin`），以下命令统一用 `mvn`。`mvn compile` 编译；`mvn test` 跑测试；`mvn -DskipTests package` 打包。
- **依赖版本（固定）**：`jjwt-api/jjwt-impl/jjwt-jackson 0.12.6`（impl+jackson 为 `runtime` scope）、`org.mindrot:jbcrypt 0.4`、`spring-boot-starter-data-redis`（版本由 Boot 4.1 BOM 管理，不写版本号）。禁止引入 boot3-starter。
- **认证约束（spec 4.1）**：accessToken JWT HS256 30min，claims 恒含 `type=ACCESS, userId, userType, jti, deviceId, exp`；refreshToken 32B 不透明串，仅存 `refresh_token_hash`（SHA-256 Hex），不存明文；二者格式天然互斥，验签后强制校验 `type=ACCESS`。
- **Redis 双用途（瞬态认证数据）**：accessToken jti 吊销黑名单（`SETEX jti:{jti}`）+ MFA 挑战凭证 tempToken（`SETEX mfa:{tempToken} {userId}:{deviceId} 300`，5min TTL，`GETDEL` 单次消费）。不存会话/业务缓存（refresh 会话仍落库）。密钥/算法演进不在本批。
- **统一响应/异常/状态码**：一律 `R<T>` + `ResultCode` + `BizException`，禁止业务层魔法数字；401/403/410 码段与 HTTP 语义对齐。
- **密码策略**：≥8 位，含字母与数字（注册/改密/重置统一校验）。
- **敏感字段**：`password`/`salt`/`mfaSecret` 不进 VO、不进日志；日志不打印 token。**例外：tempToken（MfaChallengeVO）**——5min 短时效、GETDEL 单次消费的瞬态挑战凭证，刻意随 403 MFA_REQUIRED 一次性返回，不属永久凭证白名单。
- **设计模式三件套**：新门面（AuthService/DeviceService）、新责任链（TokenAuthInterceptor）javadoc 必须含「角色说明 + 优缺点 + UML」。
- **DTO 校验**：`jakarta.validation` 注解，Controller `@Valid`。DTO 组合分页参数、禁止继承分页对象（本批无分页）。

## Spec Corrections（规划期发现，先改 spec 再按此实现）

1. **spec 4.3 白名单漏 `/auth/mfa/verify`**：MFA 二次验证发生在签发 token 之前、客户端无 accessToken，必须加入白名单。`WebMvcConfig` 白名单 = `/auth/login, /auth/register, /auth/refresh, /auth/mfa/verify, /actuator/**, /error`。
2. **spec 5.2.1 `MfaVerifyDTO` 形状（挑战凭证反转后再定）**：`createSession` 需写 `ums_user_login_device.device_id`（NOT NULL），故 MFA 验证请求必须自带 `deviceInfo`（会话行落库 + 与挑战绑定 deviceId 比对）。结合本批设计反转（见下方纠偏 7），请求体 = `{tempToken, code, deviceInfo}`——去 `account`/`password`（userId 由挑战绑定解出，密码因子已在登录步骤 5 校验，tempToken 即证明）。
3. **spec 8.3 与 `sql/user.sql` 不一致**：新库 `ums_user_login_device` 已含 `refresh_token_hash` + `idx_refresh_token_hash`，但缺 `idx_user_current (user_id, is_current)`。本批在 `user.sql` 补该索引，存量库经一次性迁移脚本补全部三处。
4. **`UmsUserLoginDeviceMapper.xml` 缺 `refresh_token_hash` 映射**：实体/PO/列均已就绪，XML 的 `<resultMap>` 与 `Base_Column_List` 需补齐（CLAUDE.md：每个 Mapper 配 XML 结果映射）。
5. **`DEVICE_KICKED(401)` 本批仅注册状态码**，拦截器黑名单命中一律抛 `TOKEN_EXPIRED`（登出语义）。`DEVICE_KICKED` 由批4 踢设备流程使用（届时黑名单 value 区分原因）。
6. **不建独立 `RedisConfig` 类**：spec 批1 列了 `RedisConfig（Lettuce）`，但 `spring-boot-starter-data-redis` 自动装配已提供 `StringRedisTemplate`（Lettuce 默认），无自定义序列化需求，故省略（YAGNI）。Redis 连接参数在 `application-dev.yaml` 的 `spring.data.redis.*`，键前缀/命令封装在 `JtiBlacklistService`（键域 `jti:*`）与 `ChallengeTokenService`（键域 `mfa:*`，Task 8 新增）两个服务。
7. **spec 5.2.1 反转：MFA 挑战凭证 tempToken（本次设计变更）**：原「verify 自包含——密码 + OTP 双因子，MFA 通过前不发凭证」改「登录步骤 5 密码对 + mfa=1 → 签发 5min 短效 tempToken（`ChallengeTokenService.issue`，`SETEX mfa:{tempToken} {userId}:{deviceId} 300`）随 403 MFA_REQUIRED 返回；verify 请求 `{tempToken, code, deviceInfo}`，不再重传密码，`GETDEL` 原子单次消费 + 绑定比对 + 验 OTP」。理由：tempToken 即密码已验证证明，verify 仅剩 OTP 因子；GETDEL 单次消费防重放（OTP 错 = 挑战已消费，须重新登录）。波及 Task 3（ResultCode + R/BizException 数据通道）、7（TotpUtil 注记）、8（新增 ChallengeTokenService）、12（MfaVerifyDTO + MfaChallengeVO）、13（login/verifyMfa）、14（Controller 提示语）、15（冒烟）、16（spec 同步）。

## File Structure

```
新增（能力包 auth）
  src/main/java/com/sanye/strategy/auth/dto/        RegisterDTO / LoginDTO / RefreshDTO / MfaVerifyDTO / TokenVO / MfaChallengeVO
  src/main/java/com/sanye/strategy/auth/service/    AuthService（门面）
  src/main/java/com/sanye/strategy/auth/controller/ AuthController
新增（能力包 device，本批仅会话属主核心，批4 补设备管理端点）
  src/main/java/com/sanye/strategy/device/dto/      DeviceInfo
  src/main/java/com/sanye/strategy/device/service/  DeviceService（门面：会话行读写属主）
新增（common 支撑件）
  src/main/java/com/sanye/strategy/common/auth/     PasswordEncoder / JwtUtil / UserContext / TotpUtil / JtiBlacklistService / ChallengeTokenService
  src/main/java/com/sanye/strategy/common/interceptor/ TokenAuthInterceptor
  src/main/java/com/sanye/strategy/common/config/   WebMvcConfig
  src/main/java/com/sanye/strategy/common/util/     HashUtil / IpUtils
修改
  pom.xml                                            依赖 5 项
  application.yaml                                   jwt.secret / jwt.access-token-ttl-minutes
  application-dev.yaml                               spring.data.redis
  ResultCode.java                                    7 个新状态码（含 MFA_CHALLENGE_EXPIRED）
  R.java / BizException.java / GlobalExceptionHandler.java  错误数据通道（R.fail data 重载 + BizException payload 透传）
  MybatisPlusConfig.java                             MetaObjectHandler 审计人填充（UserContext）
  sql/user.sql                                       列宽放宽 / 默认 NULL / idx_user_current
  sql/migration/2026-08-07-batch1-auth.sql          存量库一次性迁移（新增）
  mapper/UmsUserLoginDeviceMapper.xml               refresh_token_hash 结果映射
  docs/superpowers/specs/2026-08-07-user-center-design.md  4.3 白名单 / 5.2.1 挑战凭证反转 / 8.3 一致性 / Redis 双用途
  CLAUDE.md                                          技术栈 Redis 双用途 / 包结构 / 待办表更新（收尾任务）
测试（新增）
  src/test/java/com/sanye/strategy/common/auth/     PasswordEncoderTest / JwtUtilTest / TotpUtilTest / UserContextTest / JtiBlacklistServiceTest / ChallengeTokenServiceTest
  src/test/java/com/sanye/strategy/device/service/  DeviceServiceTest
  src/test/java/com/sanye/strategy/common/interceptor/ TokenAuthInterceptorTest
  src/test/java/com/sanye/strategy/auth/service/    AuthServiceTest
```

> 分层约定：`UmsXxxService`（base service，纯数据访问）已存在；新门面 `AuthService`/`DeviceService` 是具体 `@Service` 类（非接口——单实现聚合，YAGNI）。`DeviceService` 是 `ums_user_login_device` 表属主，认证与设备管理共用，会话行读写一律经它（spec 3.1）。新代码用构造器注入（`@RequiredArgsConstructor`），与既有字段注入不混用。

---

### Task 1: 依赖与配置（pom + yaml）

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yaml`
- Modify: `src/main/resources/application-dev.yaml`

**Interfaces:**
- Consumes: 无
- Produces: `jwt.secret` / `jwt.access-token-ttl-minutes` 配置项（Task 6 `JwtUtil` 读取）；`spring.data.redis.*`（Task 8 自动装配 `StringRedisTemplate` 读取）

- [ ] **Step 1: pom.xml 加 5 个依赖**

在 `<dependencies>` 内、`spring-boot-starter-webmvc-test` 之后追加：

```xml
		<dependency>
			<groupId>io.jsonwebtoken</groupId>
			<artifactId>jjwt-api</artifactId>
			<version>0.12.6</version>
		</dependency>
		<dependency>
			<groupId>io.jsonwebtoken</groupId>
			<artifactId>jjwt-impl</artifactId>
			<version>0.12.6</version>
			<scope>runtime</scope>
		</dependency>
		<dependency>
			<groupId>io.jsonwebtoken</groupId>
			<artifactId>jjwt-jackson</artifactId>
			<version>0.12.6</version>
			<scope>runtime</scope>
		</dependency>
		<dependency>
			<groupId>org.mindrot</groupId>
			<artifactId>jbcrypt</artifactId>
			<version>0.4</version>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-data-redis</artifactId>
		</dependency>
```

- [ ] **Step 2: application.yaml 加 jwt 配置**

`application.yaml` 末尾追加：

```yaml
jwt:
  # 生产环境必须经环境变量 JWT_SECRET 覆盖（≥32 字节）；dev 默认值仅本地联调用，不入库不进代码
  secret: ${JWT_SECRET:dev-only-jwt-secret-0123456789abcdef0123456789abcdef}
  access-token-ttl-minutes: 30
```

- [ ] **Step 3: application-dev.yaml 加 Redis 配置**

`application-dev.yaml` 的 `spring:` 下、`datasource` 同级追加（host/port 按实际 Redis 调整）：

```yaml
  data:
    redis:
      host: 10.244.166.140
      port: 6379
      database: 0
```

- [ ] **Step 4: 编译验证依赖可解析**

Run: `mvn compile -q`
Expected: BUILD SUCCESS。若报 `spring-boot-starter-data-redis` 找不到，说明 Boot 4.1 改名，把依赖坐标改为实际名（如 `spring-boot-starter-data-redis` 是否改为 `spring-boot-starter-redis`）并记录；若 jjwt/jbcrypt 版本号报错，改 `0.12.5`/`0.3` 并记录。

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/resources/application.yaml src/main/resources/application-dev.yaml
git commit -m "feat(auth): 批1 依赖与配置 — jjwt/jbcrypt/spring-data-redis + jwt.secret + redis 连接"
```

---

### Task 2: DDL — 存量迁移脚本 + user.sql 同步 + login_device XML 映射

**Files:**
- Create: `sql/migration/2026-08-07-batch1-auth.sql`
- Modify: `sql/user.sql`
- Modify: `src/main/resources/mapper/UmsUserLoginDeviceMapper.xml`

**Interfaces:**
- Consumes: 无
- Produces: 存量库升级 SQL（Task 14 冒烟测试前置——须先执行迁移再启应用）；`refresh_token_hash`/`idx_user_current` 列与索引（Task 10 `DeviceService` 查询按 `refresh_token_hash`）

- [ ] **Step 1: 新建存量迁移脚本**

创建 `sql/migration/2026-08-07-batch1-auth.sql`：

```sql
-- 批1 认证主链 DDL（存量库一次性升级，可重复执行需人工判断；MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS）
-- 新库建表以 sql/user.sql 为准，本文件仅升级已存在库。

-- 8.1 唯一标识列宽放宽（注销后缀 #del#{id} 最多 24 字符，预留余量；缺陷 10）
ALTER TABLE ums_user
  MODIFY username VARCHAR(120) NOT NULL,
  MODIFY phone    VARCHAR(48)  DEFAULT NULL,
  MODIFY email    VARCHAR(180) DEFAULT NULL;

-- 8.2 空手机/邮箱 NULL 化（缺陷 11）
ALTER TABLE ums_user
  MODIFY phone    VARCHAR(48)  DEFAULT NULL,
  MODIFY email    VARCHAR(180) DEFAULT NULL,
  MODIFY phone_country_code VARCHAR(12) DEFAULT '+86';

-- 存量回填：'' -> NULL（否则旧行仍占 uk_phone/uk_email 唯一键）
UPDATE ums_user SET phone = NULL WHERE phone = '';
UPDATE ums_user SET email = NULL WHERE email = '';

-- 8.3 会话表 refresh_token_hash + 索引（缺陷 4/7）
ALTER TABLE ums_user_login_device
  ADD COLUMN refresh_token_hash CHAR(64) DEFAULT NULL COMMENT 'refreshToken SHA-256 哈希（Hex，非明文）',
  ADD KEY idx_user_current (user_id, is_current),
  ADD KEY idx_refresh_token_hash (refresh_token_hash);
```

- [ ] **Step 2: 同步 user.sql（新库建表）**

`sql/user.sql` 的 `ums_user` 建表内三列宽与默认值改为：

```sql
  `username`             VARCHAR(120)     NOT NULL COMMENT '登录账号(唯一)',
  ...
  `phone`                VARCHAR(48)               DEFAULT NULL COMMENT '手机号',
  `phone_country_code`   VARCHAR(12)               DEFAULT '+86' COMMENT '手机国家码',
  `email`                VARCHAR(180)              DEFAULT NULL COMMENT '邮箱',
```

`ums_user_login_device` 建表内追加联合索引（保留既有 `idx_user_id`、`idx_device_id`、`idx_refresh_token_hash`）：

```sql
  KEY `idx_user_current` (`user_id`, `is_current`),
```

- [ ] **Step 3: 补齐 login_device XML 结果映射**

`src/main/resources/mapper/UmsUserLoginDeviceMapper.xml` 的 `<resultMap>` 在 `isCurrent` 行后加一行：

```xml
            <result property="refreshTokenHash" column="refresh_token_hash" />
```

`<sql id="Base_Column_List">` 的列清单末尾 `is_current,` 后加 `refresh_token_hash,`：

```xml
        is_current,refresh_token_hash,deleted,create_time,update_time
```

- [ ] **Step 4: 人工执行迁移（本机/测试库）**

Run（在 MySQL 客户端对 `sys_strategy` 库执行 `sql/migration/2026-08-07-batch1-auth.sql`，注意该文件无 `USE` 语句，需先 `use sys_strategy;`）
Expected: 全部语句成功；重复执行会因 ADD COLUMN 已存在报错（正常，一次性脚本）。

- [ ] **Step 5: Commit**

```bash
git add sql/migration/2026-08-07-batch1-auth.sql sql/user.sql src/main/resources/mapper/UmsUserLoginDeviceMapper.xml
git commit -m "feat(auth): 批1 DDL — 列宽放宽/空值NULL化/refresh_token_hash+索引，user.sql 与 login_device XML 同步"
```

---

### Task 3: 统一响应/异常数据通道 + ResultCode 新增 7 个状态码

**Files:**
- Modify: `src/main/java/com/sanye/strategy/common/response/ResultCode.java`
- Modify: `src/main/java/com/sanye/strategy/common/response/R.java`
- Modify: `src/main/java/com/sanye/strategy/common/exception/BizException.java`
- Modify: `src/main/java/com/sanye/strategy/common/exception/GlobalExceptionHandler.java`

**Interfaces:**
- Consumes: 无
- Produces: `ResultCode.TOKEN_EXPIRED / ACCOUNT_LOCKED / ACCOUNT_DISABLED / ACCOUNT_DELETED / DEVICE_KICKED / MFA_REQUIRED / MFA_CHALLENGE_EXPIRED`（Task 11 拦截器、Task 13 AuthService 抛出）；`R.fail(ResultCode, String, T data)` 数据重载 + `BizException(Object payload)` 载荷通道（MFA_REQUIRED 携带 `MfaChallengeVO` 挑战凭证，Task 13 login 使用）

- [ ] **Step 1: 插入新状态码**

`ResultCode.java` 中：
- `UNAUTHORIZED(401, ...)` 行后插入 `TOKEN_EXPIRED`、`DEVICE_KICKED`、`MFA_CHALLENGE_EXPIRED`
- `FORBIDDEN(403, ...)` 行后插入 `ACCOUNT_LOCKED`、`ACCOUNT_DISABLED`、`MFA_REQUIRED`
- `CONFLICT(409, ...)` 行后插入 `ACCOUNT_DELETED`

```java
    /** 未认证或登录已过期 */
    UNAUTHORIZED(401, "未认证或登录已过期"),

    /** 会话已过期或无效（登录态失效） */
    TOKEN_EXPIRED(401, "登录已过期，请重新登录"),

    /** 设备已下线（被踢）——本批仅注册码，批4 踢设备流程使用 */
    DEVICE_KICKED(401, "账号已在其他设备登录，请重新登录"),

    /** MFA 挑战凭证失效（过期/已消费/跨设备复用）——verify GETDEL 消费失败 */
    MFA_CHALLENGE_EXPIRED(401, "二次验证凭证已失效，请重新登录"),
```

```java
    /** 无权限访问 */
    FORBIDDEN(403, "无权限访问"),

    /** 账号锁定中（防爆破锁定） */
    ACCOUNT_LOCKED(403, "账号已锁定，请稍后再试"),

    /** 账号已冻结 */
    ACCOUNT_DISABLED(403, "账号已冻结，请联系管理员"),

    /** 需二次验证（MFA 未通过） */
    MFA_REQUIRED(403, "请完成二次验证"),
```

```java
    /** 资源冲突（如唯一键冲突、状态不允许该操作） */
    CONFLICT(409, "资源冲突"),

    /** 账号已注销（终态） */
    ACCOUNT_DELETED(410, "账号已注销"),
```

- [ ] **Step 2: R / BizException / GlobalExceptionHandler 错误数据通道**

MFA_REQUIRED 需随 403 携带挑战凭证，现异常链路三处无 data 通道。三处改动：

1. `R.java` 新增带数据 fail 重载（现 `fail(ResultCode)`/`fail(ResultCode, String)` 均传 `data=null`）：

```java
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
```

2. `BizException.java` 增 `Object payload`（可选载荷）+ 三参构造器 + getter（现有两参构造器行为不变，`payload=null`）：

```java
    /** 可选数据载荷（如 MFA 挑战凭证），无则 null */
    private final Object payload;

    public BizException(ResultCode resultCode, String message, Object payload) {
        super(message);
        this.resultCode = resultCode;
        this.payload = payload;
    }

    public BizException(ResultCode resultCode) {
        this(resultCode, resultCode.getMessage(), null);
    }

    public BizException(ResultCode resultCode, String message) {
        this(resultCode, message, null);
    }

    public Object getPayload() {
        return payload;
    }
```

3. `GlobalExceptionHandler.java` `handleBizException` payload 非空时透传进 `data`（返回类型 `ResponseEntity<R<Void>>` → `ResponseEntity<R<Object>>`）：

```java
    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Object>> handleBizException(HttpServletRequest request, BizException e) {
        ResultCode resultCode = e.getResultCode();
        String message = e.getMessage();
        if (resultCode == null) {
            log.error("业务异常缺少状态码, request={}, message={}", requestLine(request), message);
            return response(ResultCode.INTERNAL_ERROR, ResultCode.INTERNAL_ERROR.getMessage());
        }
        log.warn("业务异常 {}: code={}, message={}", requestLine(request), resultCode.getCode(), message);
        if (e.getPayload() != null) {
            HttpStatus httpStatus = resolveHttpStatus(resultCode);
            return ResponseEntity.status(httpStatus).body(R.fail(resultCode, message, e.getPayload()));
        }
        return response(resultCode, message);
    }
```

`response()` 辅助方法已是泛型 `private <T> ResponseEntity<R<T>> response(ResultCode, String)`，**无需改动**——handleBizException 改为 `ResponseEntity<R<Object>>` 后，原分支 `return response(resultCode, message)` 经类型推断（T=Object）自动适配；其余 12 个 handler 仍返回 `ResponseEntity<R<Void>>`，泛型不变性下不受影响。**勿**将 `response()` 改成固定 `ResponseEntity<R<Object>>`（会破坏其他 handler）。`getPayload() == null` 时走原 `response()` 路径，行为与现状完全一致（零回归）。

- [ ] **Step 3: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sanye/strategy/common/response/ResultCode.java src/main/java/com/sanye/strategy/common/response/R.java src/main/java/com/sanye/strategy/common/exception/BizException.java src/main/java/com/sanye/strategy/common/exception/GlobalExceptionHandler.java
git commit -m "feat(auth): 批1 ResultCode 7 码 + 错误数据通道 — TOKEN_EXPIRED/.../MFA_REQUIRED/MFA_CHALLENGE_EXPIRED + R.fail data 重载 + BizException payload"
```

---

### Task 4: PasswordEncoder（jbcrypt 封装）+ 单测

**Files:**
- Create: `src/main/java/com/sanye/strategy/common/auth/PasswordEncoder.java`
- Test: `src/test/java/com/sanye/strategy/common/auth/PasswordEncoderTest.java`

**Interfaces:**
- Produces: `PasswordEncoder.encode(String):String`、`PasswordEncoder.matches(String, String):boolean`（Task 13 AuthService 注册/登录使用；verifyMfa 不再验密码——挑战凭证反转后密码因子已在登录步骤 5 校验）

- [ ] **Step 1: 写失败单测**

```java
package com.sanye.strategy.common.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p>
 * {@link PasswordEncoder} BCrypt 封装验证
 * </p>
 *
 * @author 31372
 */
class PasswordEncoderTest {

    private final PasswordEncoder passwordEncoder = new PasswordEncoder();

    @Test
    void shouldEncodeAndMatch() {
        String hash = passwordEncoder.encode("abc12345");

        assertThat(hash).startsWith("$2");
        assertThat(passwordEncoder.matches("abc12345", hash)).isTrue();
        assertThat(passwordEncoder.matches("wrong-pass", hash)).isFalse();
    }

    @Test
    void shouldReturnFalseOnNullOrEmptyEncoded() {
        assertThat(passwordEncoder.matches("abc12345", null)).isFalse();
        assertThat(passwordEncoder.matches("abc12345", "")).isFalse();
    }

    @Test
    void shouldBeRandomSaltPerEncode() {
        assertThat(passwordEncoder.encode("abc12345"))
                .isNotEqualTo(passwordEncoder.encode("abc12345"));
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `mvn test -Dtest=PasswordEncoderTest`
Expected: 编译失败（`PasswordEncoder` 类不存在）。

- [ ] **Step 3: 实现 PasswordEncoder**

```java
package com.sanye.strategy.common.auth;

import org.mindrot.jbcrypt.BCrypt;

/**
 * <p>
 * 密码哈希工具 — BCrypt 封装
 * </p>
 * <p>
 * 库选 {@code org.mindrot:jbcrypt}（纯库，零 Spring 依赖，契合 DIP 零框架风格）。
 * 注册/改密/重置统一走本封装；密码算法演进（Argon2）只换实现类，调用方签名不变。
 * 兼容旧体系：{@code salt} 字段废弃不再写入（BCrypt 自带盐），旧数据 {@code matches} 仍可验证。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：密码学操作封装，隔离第三方库细节。</li>
 *   <li>优缺点：零框架依赖、单测无需 Spring 上下文；缺点：BCrypt 单次哈希慢（<100ms），可接受。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
public class PasswordEncoder {

    /**
     * 明文密码 → BCrypt 哈希
     *
     * @param rawPassword 明文密码
     * @return BCrypt 哈希串（自带随机盐）
     */
    public String encode(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt());
    }

    /**
     * 校验明文密码与已存哈希是否匹配
     *
     * @param rawPassword    明文密码
     * @param encodedPassword 已存哈希（null/空直接返回 false）
     * @return true 匹配
     */
    public boolean matches(String rawPassword, String encodedPassword) {
        if (encodedPassword == null || encodedPassword.isEmpty()) {
            return false;
        }
        return BCrypt.checkpw(rawPassword, encodedPassword);
    }
}
```

- [ ] **Step 4: 跑测试验证通过**

Run: `mvn test -Dtest=PasswordEncoderTest`
Expected: 3 个测试全部 PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sanye/strategy/common/auth/PasswordEncoder.java src/test/java/com/sanye/strategy/common/auth/PasswordEncoderTest.java
git commit -m "feat(auth): 批1 PasswordEncoder — jbcrypt BCrypt 封装 + 单测"
```

---

### Task 5: UserContext（ThreadLocal 用户上下文）

**Files:**
- Create: `src/main/java/com/sanye/strategy/common/auth/UserContext.java`
- Test: `src/test/java/com/sanye/strategy/common/auth/UserContextTest.java`

**Interfaces:**
- Produces: `UserContext.set/get/clear`、`UserContext(Long, UserTypeEnum, Long, String)`（Task 9 MetaObjectHandler 读取、Task 11 拦截器填充、Task 13 logout 取 jti）

- [ ] **Step 1: 写失败单测**

```java
package com.sanye.strategy.common.auth;

import com.sanye.strategy.enums.UserTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p>
 * {@link UserContext} ThreadLocal 语义验证
 * </p>
 *
 * @author 31372
 */
class UserContextTest {

    @Test
    void shouldSetGetAndClear() {
        UserContext context = new UserContext(1L, UserTypeEnum.NORMAL_USER, 10L, "dev-1");

        UserContext.set(context);
        assertThat(UserContext.get()).isSameAs(context);
        assertThat(UserContext.get().getUserId()).isEqualTo(1L);
        assertThat(UserContext.get().getUserType()).isEqualTo(UserTypeEnum.NORMAL_USER);
        assertThat(UserContext.get().getJti()).isEqualTo(10L);
        assertThat(UserContext.get().getDeviceId()).isEqualTo("dev-1");

        UserContext.clear();
        assertThat(UserContext.get()).isNull();
    }

    @Test
    void shouldNotLeakAcrossThreads() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<UserContext> otherThread = new AtomicReference<>();

        Thread t = new Thread(() -> {
            UserContext.set(new UserContext(2L, UserTypeEnum.OPERATOR, 20L, "dev-2"));
            otherThread.set(UserContext.get());
            latch.countDown();
        });
        t.start();
        latch.await(5, TimeUnit.SECONDS);

        // 主线程无上下文，不受其他线程影响
        assertThat(UserContext.get()).isNull();
        assertThat(otherThread.get().getUserId()).isEqualTo(2L);
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `mvn test -Dtest=UserContextTest`
Expected: 编译失败（`UserContext` 类不存在）。

- [ ] **Step 3: 实现 UserContext**

```java
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
```

- [ ] **Step 4: 跑测试验证通过**

Run: `mvn test -Dtest=UserContextTest`
Expected: 2 个测试全部 PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sanye/strategy/common/auth/UserContext.java src/test/java/com/sanye/strategy/common/auth/UserContextTest.java
git commit -m "feat(auth): 批1 UserContext — ThreadLocal 用户上下文 + 单测"
```

---

### Task 6: JwtUtil（jjwt HS256 封装）+ 单测

**Files:**
- Create: `src/main/java/com/sanye/strategy/common/auth/JwtUtil.java`
- Test: `src/test/java/com/sanye/strategy/common/auth/JwtUtilTest.java`

**Interfaces:**
- Consumes: `jwt.secret` / `jwt.access-token-ttl-minutes`（Task 1）
- Produces: `JwtUtil.generateAccessToken(Long userId, UserTypeEnum userType, Long jti, String deviceId):String`、`JwtUtil.parseToken(String):Claims`（抛 `io.jsonwebtoken.JwtException`）、`JwtUtil.getAccessTokenTtlSeconds():long`（Task 11 拦截器、Task 13 AuthService 使用）

- [ ] **Step 1: 写失败单测**

```java
package com.sanye.strategy.common.auth;

import com.sanye.strategy.enums.UserTypeEnum;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <p>
 * {@link JwtUtil} 签发/解析验证
 * </p>
 *
 * @author 31372
 */
class JwtUtilTest {

    private static final String SECRET = "test-secret-0123456789-abcdefghijklmnopqrstuvwxyz";

    private final JwtUtil jwtUtil = new JwtUtil(SECRET, 30);

    @Test
    void shouldGenerateAndParseAccessToken() {
        String token = jwtUtil.generateAccessToken(1L, UserTypeEnum.NORMAL_USER, 10L, "dev-1");

        Claims claims = jwtUtil.parseToken(token);
        assertThat(claims.get("type", String.class)).isEqualTo("ACCESS");
        assertThat(claims.get("userId", Number.class).longValue()).isEqualTo(1L);
        assertThat(claims.get("userType", Number.class).intValue()).isEqualTo(UserTypeEnum.NORMAL_USER.getCode());
        assertThat(claims.get("jti", Number.class).longValue()).isEqualTo(10L);
        assertThat(claims.get("deviceId", String.class)).isEqualTo("dev-1");
        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(jwtUtil.getAccessTokenTtlSeconds()).isEqualTo(1800L);
    }

    @Test
    void shouldRejectTamperedToken() {
        String token = jwtUtil.generateAccessToken(1L, UserTypeEnum.NORMAL_USER, 10L, "dev-1");

        assertThatThrownBy(() -> jwtUtil.parseToken(token + "x"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void shouldRejectExpiredToken() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String expired = Jwts.builder()
                .claims(Map.of("type", "ACCESS"))
                .subject("1")
                .issuedAt(new Date(System.currentTimeMillis() - 7_200_000L))
                .expiration(new Date(System.currentTimeMillis() - 3_600_000L))
                .signWith(key)
                .compact();

        assertThatThrownBy(() -> jwtUtil.parseToken(expired))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void shouldRejectWrongSecret() {
        JwtUtil other = new JwtUtil("another-test-secret-0123456789-abcdefghijklmnopqrstuvwxyz", 30);
        String token = jwtUtil.generateAccessToken(1L, UserTypeEnum.NORMAL_USER, 10L, "dev-1");

        assertThatThrownBy(() -> other.parseToken(token))
                .isInstanceOf(JwtException.class);
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `mvn test -Dtest=JwtUtilTest`
Expected: 编译失败（`JwtUtil` 类不存在）。

- [ ] **Step 3: 实现 JwtUtil**

```java
package com.sanye.strategy.common.auth;

import com.sanye.strategy.enums.UserTypeEnum;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * <p>
 * JWT 工具 — HS256 签名签发/解析
 * </p>
 * <p>
 * accessToken claims：{@code type=ACCESS, userId, userType, jti, deviceId}（+ 标准 sub/iat/exp）。
 * 密钥 ≥32 字节，从配置注入（{@code jwt.secret}），环境变量/密管覆盖，不进代码不入库。
 * 算法/密钥演进预留：签发时写入 {@code kid} 头（当前固定 "1"），轮换时新旧密钥并存验证。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：Token 生命周期工具，隔离 jjwt 细节。</li>
 *   <li>优缺点：无状态验签、免 DB 查询；缺点：吊销只能靠黑名单（本批 Redis jti 黑名单承接），
 *       且 HS256 密钥须妥善保管。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Component
public class JwtUtil {

    private static final String CLAIM_TYPE = "type";
    private static final String CLAIM_USER_ID = "userId";
    private static final String CLAIM_USER_TYPE = "userType";
    private static final String CLAIM_JTI = "jti";
    private static final String CLAIM_DEVICE_ID = "deviceId";
    private static final String TYPE_ACCESS = "ACCESS";
    private static final String KID = "1";

    private final SecretKey secretKey;
    private final long accessTokenTtlMillis;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.access-token-ttl-minutes:30}") long accessTokenTtlMinutes) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtlMillis = accessTokenTtlMinutes * 60_000L;
    }

    /**
     * 签发 accessToken
     *
     * @param userId   用户ID
     * @param userType 用户类型
     * @param jti      会话行 ID（吊销黑名单键）
     * @param deviceId 设备 ID
     * @return JWT 串
     */
    public String generateAccessToken(Long userId, UserTypeEnum userType, Long jti, String deviceId) {
        Date now = new Date();
        return Jwts.builder()
                .header().keyId(KID).and()
                .claims(Map.of(
                        CLAIM_TYPE, TYPE_ACCESS,
                        CLAIM_USER_ID, userId,
                        CLAIM_USER_TYPE, userType.getCode(),
                        CLAIM_JTI, jti,
                        CLAIM_DEVICE_ID, deviceId))
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessTokenTtlMillis))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 验签 + 过期校验并解析 claims
     *
     * @param token JWT 串
     * @return claims
     * @throws io.jsonwebtoken.JwtException 验签失败 / 过期
     */
    public Claims parseToken(String token) {
        return Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload();
    }

    /**
     * accessToken 有效期（秒），用于黑名单 TTL 上限
     */
    public long getAccessTokenTtlSeconds() {
        return accessTokenTtlMillis / 1000L;
    }
}
```

- [ ] **Step 4: 跑测试验证通过**

Run: `mvn test -Dtest=JwtUtilTest`
Expected: 4 个测试全部 PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sanye/strategy/common/auth/JwtUtil.java src/test/java/com/sanye/strategy/common/auth/JwtUtilTest.java
git commit -m "feat(auth): 批1 JwtUtil — jjwt HS256 签发/解析 + kid 预留 + 单测"
```

---

### Task 7: TotpUtil（RFC 6238 TOTP）+ 单测

**Files:**
- Create: `src/main/java/com/sanye/strategy/common/auth/TotpUtil.java`
- Test: `src/test/java/com/sanye/strategy/common/auth/TotpUtilTest.java`

**Interfaces:**
- Produces: `TotpUtil.verify(String base32Secret, String code):boolean`（30s 窗口 ±1，Task 13 `verifyMfa` 对挑战绑定 userId 的 `mfa_secret` 使用）；包私有 `TotpUtil.generateAt(String, long)`（测试向量用，批3 开启 MFA 时在此类补 `generateSecret()`）。**tempToken 生成不属本类**——32B `SecureRandom` hex 归 `ChallengeTokenService`（Task 8），与本批 `generateRefreshToken` 同模式

- [ ] **Step 1: 写失败单测（RFC 6238 官方向量）**

```java
package com.sanye.strategy.common.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p>
 * {@link TotpUtil} RFC 6238 测试向量验证
 * </p>
 *
 * @author 31372
 */
class TotpUtilTest {

    // RFC 6238 Appendix B：ASCII "12345678901234567890" 的 Base32 编码
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void shouldMatchRfc6238TestVectors() {
        assertThat(TotpUtil.generateAt(RFC_SECRET, 59L)).isEqualTo("287082");
        assertThat(TotpUtil.generateAt(RFC_SECRET, 1111111109L)).isEqualTo("081804");
        assertThat(TotpUtil.generateAt(RFC_SECRET, 1234567890L)).isEqualTo("005924");
        assertThat(TotpUtil.generateAt(RFC_SECRET, 20000000000L)).isEqualTo("279037");
    }

    @Test
    void shouldReturnFalseForNullSecretOrCode() {
        assertThat(TotpUtil.verify(null, "123456")).isFalse();
        assertThat(TotpUtil.verify(RFC_SECRET, null)).isFalse();
        assertThat(TotpUtil.verify("", "123456")).isFalse();
    }

    @Test
    void shouldRejectIllegalBase32() {
        assertThat(TotpUtil.verify("INVALID_CHARACTERS!", "123456")).isFalse();
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `mvn test -Dtest=TotpUtilTest`
Expected: 编译失败（`TotpUtil` 类不存在）。

- [ ] **Step 3: 实现 TotpUtil**

```java
package com.sanye.strategy.common.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * <p>
 * TOTP 一次性密码工具 — RFC 6238（HmacSHA1，6 位数字，30s 时间步）
 * </p>
 * <p>
 * JDK 内置 {@link Mac} HmacSHA1；JDK 无内置 Base32——自实现 RFC 4648 Base32 解码（不引第三方库）。
 * {@code mfa_secret} 存 Base32 密钥；校验 30s 时间窗口 ±1（容忍时钟偏移一步）。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：TOTP 运算封装，双因子认证核心。</li>
 *   <li>优缺点：零第三方依赖、常量时间比较防时序侧信道；缺点：HmacSHA1 强度低于 SHA-256，
 *       对 MFA 场景足够（共享密钥 + 30s 步进），换算法只改本类常量。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
public final class TotpUtil {

    private static final int TIME_STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private TotpUtil() {
    }

    /**
     * 校验 TOTP 验证码（时间窗口 ±1 步）
     *
     * @param base32Secret Base32 密钥
     * @param code         用户输入的 6 位验证码
     * @return true 校验通过
     */
    public static boolean verify(String base32Secret, String code) {
        if (base32Secret == null || base32Secret.isEmpty() || code == null || code.isEmpty()) {
            return false;
        }
        try {
            byte[] key = decodeBase32(base32Secret);
            long counter = System.currentTimeMillis() / 1000L / TIME_STEP_SECONDS;
            for (long offset = -1; offset <= 1; offset++) {
                String expected = generateForCounter(key, counter + offset);
                if (MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.US_ASCII),
                        code.getBytes(StandardCharsets.US_ASCII))) {
                    return true;
                }
            }
            return false;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 固定时间点生成 TOTP（供 RFC 6238 测试向量验证，包私有）
     */
    static String generateAt(String base32Secret, long timeSeconds) {
        return generateForCounter(decodeBase32(base32Secret), timeSeconds / TIME_STEP_SECONDS);
    }

    private static String generateForCounter(byte[] key, long counter) {
        byte[] data = new byte[8];
        for (int i = 7; i >= 0; i--) {
            data[i] = (byte) (counter & 0xFF);
            counter >>= 8;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA1 不可用", e);
        }
    }

    private static byte[] decodeBase32(String base32) {
        String clean = base32.replaceAll("=", "").toUpperCase(Locale.ROOT);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;
        for (int i = 0; i < clean.length(); i++) {
            int value = BASE32_ALPHABET.indexOf(clean.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("非法 Base32 字符: " + clean.charAt(i));
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }
}
```

- [ ] **Step 4: 跑测试验证通过**

Run: `mvn test -Dtest=TotpUtilTest`
Expected: 3 个测试全部 PASS（向量值 287082/081804/005924/279037 与 RFC 6238 附录 B 一致）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sanye/strategy/common/auth/TotpUtil.java src/test/java/com/sanye/strategy/common/auth/TotpUtilTest.java
git commit -m "feat(auth): 批1 TotpUtil — RFC 6238 TOTP + 自实现 Base32 + 单测"
```

---

### Task 8: JtiBlacklistService + ChallengeTokenService（Redis 双用途）+ 单测

**Files:**
- Create: `src/main/java/com/sanye/strategy/common/auth/JtiBlacklistService.java`
- Create: `src/main/java/com/sanye/strategy/common/auth/ChallengeTokenService.java`
- Test: `src/test/java/com/sanye/strategy/common/auth/JtiBlacklistServiceTest.java`
- Test: `src/test/java/com/sanye/strategy/common/auth/ChallengeTokenServiceTest.java`

**Interfaces:**
- Consumes: `StringRedisTemplate`（`spring-boot-starter-data-redis` 自动装配，Task 1）
- Produces: `JtiBlacklistService.revoke(Long jti, long ttlSeconds)`、`JtiBlacklistService.isRevoked(Long jti):boolean`、`JtiBlacklistService.remove(Long jti)`（Task 11 拦截器 isRevoked、Task 13 AuthService 登出/刷新调用）
- Produces: `ChallengeTokenService.issue(Long userId, String deviceId, int ttlSeconds):String`、`ChallengeTokenService.consume(String tempToken):ChallengeBinding`（`ChallengeBinding` = record(userId, deviceId)；返回 null 表示已消费/过期）（Task 13 login 签发挑战、verifyMfa GETDEL 消费）

- [ ] **Step 1: 写失败单测**

```java
package com.sanye.strategy.common.auth;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <p>
 * {@link JtiBlacklistService} Redis 命令转发验证
 * </p>
 *
 * @author 31372
 */
class JtiBlacklistServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final JtiBlacklistService jtiBlacklistService = new JtiBlacklistService(redisTemplate);

    @Test
    void shouldRevokeWithSetExAndTtl() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);

        jtiBlacklistService.revoke(123L, 1800L);

        verify(ops).set("jti:123", "1", Duration.ofSeconds(1800));
    }

    @Test
    void shouldCheckRevokedState() {
        when(redisTemplate.hasKey("jti:123")).thenReturn(true);
        assertThat(jtiBlacklistService.isRevoked(123L)).isTrue();

        when(redisTemplate.hasKey("jti:456")).thenReturn(false);
        assertThat(jtiBlacklistService.isRevoked(456L)).isFalse();
    }

    @Test
    void shouldRemoveFromBlacklist() {
        jtiBlacklistService.remove(123L);
        verify(redisTemplate).delete("jti:123");
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `mvn test -Dtest=JtiBlacklistServiceTest`
Expected: 编译失败（`JtiBlacklistService` 类不存在）。

- [ ] **Step 3: 实现 JtiBlacklistService**

```java
package com.sanye.strategy.common.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * <p>
 * accessToken jti 吊销黑名单 — Redis 秒级冻结
 * </p>
 * <p>
 * 吊销操作（登出/踢设备/改密/冻结/注销）写黑名单 {@code SETEX jti:{jti} ttl 1}（TTL = 剩余 exp）；
 * 拦截器验签后 {@code EXISTS jti:{jti}} 命中即拒——accessToken 即时失效，不等 JWT 自身 TTL 过期。
 * 记录随 TTL 自动过期，无手工清理。Redis 双用途：jti 吊销黑名单 + MFA 挑战凭证（键域 {@code mfa:*}，见 {@link ChallengeTokenService}），不存会话/业务缓存（refresh 会话仍落库）。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：吊销机制存储适配器，隔离 Redis 命令细节。</li>
 *   <li>优缺点：秒级生效、TTL 自清理、实现简单；缺点：依赖 Redis 可用性（不可用则拦截器检活失败，
 *       以 fail-open 或 fail-closed 取舍——本实现不可用时抛连接异常走 500，运维兜底）。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Service
public class JtiBlacklistService {

    private static final String KEY_PREFIX = "jti:";
    private static final String VALUE = "1";

    private final StringRedisTemplate redisTemplate;

    public JtiBlacklistService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 吊销会话（写黑名单，TTL = accessToken 剩余有效期上限）
     *
     * @param jti        会话行 ID
     * @param ttlSeconds 黑名单存活秒数（取 accessToken TTL，保守覆盖剩余 exp）
     */
    public void revoke(Long jti, long ttlSeconds) {
        redisTemplate.opsForValue().set(KEY_PREFIX + jti, VALUE, Duration.ofSeconds(ttlSeconds));
    }

    /**
     * 会话是否已被吊销
     */
    public boolean isRevoked(Long jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + jti));
    }

    /**
     * 清除黑名单记录（refresh 轮换后新 token 新 exp，清旧吊销记录）
     */
    public void remove(Long jti) {
        redisTemplate.delete(KEY_PREFIX + jti);
    }
}
```

- [ ] **Step 4: 跑测试验证通过**

Run: `mvn test -Dtest=JtiBlacklistServiceTest`
Expected: 3 个测试全部 PASS。

- [ ] **Step 5: 写 ChallengeTokenService 失败单测**

```java
package com.sanye.strategy.common.auth;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <p>
 * {@link ChallengeTokenService} Redis 命令转发 + GETDEL 原子消费验证
 * </p>
 *
 * @author 31372
 */
class ChallengeTokenServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final ChallengeTokenService service = new ChallengeTokenService(redisTemplate);

    @Test
    void shouldIssueWithSetExAndRandomToken() {
        when(redisTemplate.opsForValue()).thenReturn(ops);

        String token = service.issue(1L, "device-1", 300);

        assertThat(token).matches("^[0-9a-f]{64}$");
        verify(ops).set("mfa:" + token, "1:device-1", Duration.ofSeconds(300));
    }

    @Test
    void shouldConsumeAtomicallyAndReturnBinding() {
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.getAndDelete("mfa:tok")).thenReturn("7:device-9");

        ChallengeTokenService.ChallengeBinding binding = service.consume("tok");

        assertThat(binding.userId()).isEqualTo(7L);
        assertThat(binding.deviceId()).isEqualTo("device-9");
        verify(ops).getAndDelete("mfa:tok");
    }

    @Test
    void shouldReturnNullWhenAlreadyConsumedOrExpired() {
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.getAndDelete("mfa:gone")).thenReturn(null);

        assertThat(service.consume("gone")).isNull();
    }
}
```

- [ ] **Step 6: 实现 ChallengeTokenService**

```java
package com.sanye.strategy.common.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

/**
 * <p>
 * MFA 挑战凭证（tempToken）— Redis 短效一次性瞬态凭证
 * </p>
 * <p>
 * 登录 MFA 分支签发 32B 随机 tempToken，Redis {@code SETEX mfa:{tempToken} {userId}:{deviceId} ttl}（5min）；
 * verify 时 {@code GETDEL} 原子单次消费（{@link ValueOperations#getAndDelete(Object)}）——命中即删，防重放/双消费竞态。
 * 记录随 TTL 自动过期，无手工清理。键域 {@code mfa:*} 与 {@link JtiBlacklistService}（{@code jti:*}）并列，Redis 双用途。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：挑战凭证存储适配器，隔离 Redis 命令细节。</li>
 *   <li>优缺点：GETDEL 原子消费天然防重放、免事后作废步骤；缺点：依赖 Redis 可用性
 *       （不可用时签发/消费抛连接异常走 500，运维兜底，与 JtiBlacklistService 同策略）。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Service
public class ChallengeTokenService {

    private static final String KEY_PREFIX = "mfa:";

    private final StringRedisTemplate redisTemplate;

    public ChallengeTokenService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 签发挑战凭证
     *
     * @param userId     账号 ID（绑定，verify 时解出）
     * @param deviceId   设备 ID（绑定，verify 比对防跨设备）
     * @param ttlSeconds 存活秒数（本批 300 = 5min）
     * @return 32B hex tempToken（64 字符）
     */
    public String issue(Long userId, String deviceId, int ttlSeconds) {
        String token = generateToken();
        redisTemplate.opsForValue().set(KEY_PREFIX + token, userId + ":" + deviceId, Duration.ofSeconds(ttlSeconds));
        return token;
    }

    /**
     * 原子单次消费挑战凭证（GETDEL）
     *
     * @param tempToken 挑战凭证
     * @return 绑定信息（userId/deviceId）；null 表示已消费/过期/不存在
     */
    public ChallengeBinding consume(String tempToken) {
        String value = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + tempToken);
        if (value == null) {
            return null;
        }
        int separator = value.indexOf(':');
        if (separator <= 0) {
            return null;
        }
        try {
            return new ChallengeBinding(Long.parseLong(value.substring(0, separator)), value.substring(separator + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** 挑战绑定信息（userId + deviceId） */
    public record ChallengeBinding(Long userId, String deviceId) {
    }
}
```

- [ ] **Step 7: 跑测试验证通过**

Run: `mvn test -Dtest=ChallengeTokenServiceTest`
Expected: 3 个测试全部 PASS。

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/sanye/strategy/common/auth/JtiBlacklistService.java src/main/java/com/sanye/strategy/common/auth/ChallengeTokenService.java src/test/java/com/sanye/strategy/common/auth/JtiBlacklistServiceTest.java src/test/java/com/sanye/strategy/common/auth/ChallengeTokenServiceTest.java
git commit -m "feat(auth): 批1 Redis 双用途 — JtiBlacklistService(jti 黑名单) + ChallengeTokenService(mfa 挑战凭证 GETDEL 原子消费)"
```

---

### Task 9: MetaObjectHandler 审计人字段填充（UserContext 联动）

**Files:**
- Modify: `src/main/java/com/sanye/strategy/common/config/MybatisPlusConfig.java`

**Interfaces:**
- Consumes: `UserContext.get()`（Task 5）
- Produces: 插入/更新时自动填充 `createUserId`/`updateUserId`（缺陷 5 修复；无上下文落 NULL 不阻断，后台脚本场景）

- [ ] **Step 1: 改造 metaObjectHandler**

`MybatisPlusConfig.java`：
1. 加 import：`import com.sanye.strategy.common.auth.UserContext;`
2. `insertFill`/`updateFill` 两个方法体改为：

```java
            @Override
            public void insertFill(MetaObject metaObject) {
                this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
                this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
                // 审计人：有用户上下文（拦截器已填充）则写入，无上下文（定时任务/初始化脚本）落 NULL 不阻断
                Long userId = UserContext.get() == null ? null : UserContext.get().getUserId();
                if (userId != null) {
                    this.strictInsertFill(metaObject, "createUserId", Long.class, userId);
                    this.strictInsertFill(metaObject, "updateUserId", Long.class, userId);
                }
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
                Long userId = UserContext.get() == null ? null : UserContext.get().getUserId();
                if (userId != null) {
                    this.strictUpdateFill(metaObject, "updateUserId", Long.class, userId);
                }
            }
```

同时更新类 javadoc：`createUserId/updateUserId 待用户上下文接入后补充` → `已由拦截器填充 UserContext，MetaObjectHandler 从上下文取值`。

- [ ] **Step 2: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS。（无单测——需 MyBatis 上下文；行为经 Task 14 冒烟：注册（无上下文）落 NULL 不 NPE，批2 有鉴权写接口后验证真值填充。）

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sanye/strategy/common/config/MybatisPlusConfig.java
git commit -m "feat(auth): 批1 MetaObjectHandler — 从 UserContext 填充 createUserId/updateUserId，无上下文落 NULL"
```

---

### Task 10: DeviceInfo + DeviceService（会话行属主核心）+ 单测

**Files:**
- Create: `src/main/java/com/sanye/strategy/device/dto/DeviceInfo.java`
- Create: `src/main/java/com/sanye/strategy/device/service/DeviceService.java`
- Create: `src/main/java/com/sanye/strategy/common/util/HashUtil.java`
- Test: `src/test/java/com/sanye/strategy/device/service/DeviceServiceTest.java`

**Interfaces:**
- Consumes: `UmsUserLoginDeviceService`（base service，`IService<UmsUserLoginDevice>`，已存在）、`YesNoEnum`/`DeviceTypeEnum`
- Produces:
  - `DeviceService.createSession(Long userId, DeviceInfo info, String loginIp, String refreshToken, int ttlDays):UmsUserLoginDevice`（插入 `is_current=1` 会话行，存 hash，返回实体含 id=jti）
  - `DeviceService.findByRefreshTokenHash(String hash):UmsUserLoginDevice`
  - `DeviceService.rotateRefreshToken(Long sessionId, String newRefreshToken, int ttlDays)`
  - `DeviceService.invalidateSession(Long sessionId)`
  - `DeviceService.invalidateAllByUser(Long userId)`
  - `DeviceService.listByUser(Long userId):List<UmsUserLoginDevice>`
  - `HashUtil.sha256Hex(String):String`
  - `DeviceInfo`（`deviceType:Integer, deviceOs, deviceBrand, deviceModel, deviceId, appVersion`，`deviceId @NotBlank`）

- [ ] **Step 1: 写失败单测**

```java
package com.sanye.strategy.device.service;

import com.sanye.strategy.common.util.HashUtil;
import com.sanye.strategy.device.dto.DeviceInfo;
import com.sanye.strategy.domain.UmsUserLoginDevice;
import com.sanye.strategy.enums.DeviceTypeEnum;
import com.sanye.strategy.enums.YesNoEnum;
import com.sanye.strategy.service.UmsUserLoginDeviceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <p>
 * {@link DeviceService} 会话行操作验证
 * </p>
 *
 * @author 31372
 */
class DeviceServiceTest {

    private final UmsUserLoginDeviceService loginDeviceService = mock(UmsUserLoginDeviceService.class);
    private final DeviceService deviceService = new DeviceService(loginDeviceService);

    @Test
    void shouldCreateSessionWithHashedRefreshToken() {
        DeviceInfo info = new DeviceInfo();
        info.setDeviceType(DeviceTypeEnum.PHONE.getCode());
        info.setDeviceOs("iOS");
        info.setDeviceId("device-1");

        UmsUserLoginDevice entity = deviceService.createSession(100L, info, "127.0.0.1", "raw-token", 14);

        assertThat(entity.getUserId()).isEqualTo(100L);
        assertThat(entity.getDeviceType()).isEqualTo(DeviceTypeEnum.PHONE);
        assertThat(entity.getDeviceId()).isEqualTo("device-1");
        assertThat(entity.getLoginIp()).isEqualTo("127.0.0.1");
        assertThat(entity.getIsCurrent()).isEqualTo(YesNoEnum.YES);
        assertThat(entity.getRefreshTokenHash()).isEqualTo(HashUtil.sha256Hex("raw-token"));
        verify(loginDeviceService).insert(entity);
    }

    @Test
    void shouldFindSessionByRefreshTokenHash() {
        UmsUserLoginDevice session = new UmsUserLoginDevice();
        when(loginDeviceService.getOne(any())).thenReturn(session);

        UmsUserLoginDevice found = deviceService.findByRefreshTokenHash("some-hash");

        assertThat(found).isSameAs(session);
    }

    @Test
    void shouldRotateRefreshTokenPartialUpdate() {
        deviceService.rotateRefreshToken(5L, "new-token", 14);

        ArgumentCaptor<UmsUserLoginDevice> captor = ArgumentCaptor.forClass(UmsUserLoginDevice.class);
        verify(loginDeviceService).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(5L);
        assertThat(captor.getValue().getRefreshTokenHash()).isEqualTo(HashUtil.sha256Hex("new-token"));
        assertThat(captor.getValue().getExpireTime()).isNotNull();
    }

    @Test
    void shouldInvalidateSession() {
        deviceService.invalidateSession(5L);

        ArgumentCaptor<UmsUserLoginDevice> captor = ArgumentCaptor.forClass(UmsUserLoginDevice.class);
        verify(loginDeviceService).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(5L);
        assertThat(captor.getValue().getIsCurrent()).isEqualTo(YesNoEnum.NO);
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `mvn test -Dtest=DeviceServiceTest`
Expected: 编译失败（`DeviceService`/`DeviceInfo`/`HashUtil` 不存在）。

- [ ] **Step 3: 实现 HashUtil**

```java
package com.sanye.strategy.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * <p>
 * 哈希工具 — SHA-256（Hex）
 * </p>
 * <p>
 * 用于 refreshToken 落库哈希（不存明文，碰撞时凭哈希定位会话行）。
 * </p>
 *
 * @author 31372
 */
public final class HashUtil {

    private HashUtil() {
    }

    /**
     * SHA-256 十六进制小写串；入参 null 返回 null
     */
    public static String sha256Hex(String value) {
        if (value == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
```

- [ ] **Step 4: 实现 DeviceInfo**

```java
package com.sanye.strategy.device.dto;

import com.sanye.strategy.enums.DeviceTypeEnum;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * <p>
 * 登录设备信息（客户端上报，login_ip 服务端注入不入此对象）
 * </p>
 * <p>
 * 注册/登录/MFA 验证请求体均含本对象，供会话行落库与注册渠道推导。
 * </p>
 *
 * @author 31372
 */
@Data
public class DeviceInfo {

    /**
     * 设备类型码，见 {@link DeviceTypeEnum}（1-手机 2-平板 3-PC 4-小程序）
     */
    private Integer deviceType;

    /**
     * 操作系统
     */
    private String deviceOs;

    /**
     * 设备品牌
     */
    private String deviceBrand;

    /**
     * 设备型号
     */
    private String deviceModel;

    /**
     * 设备唯一ID
     */
    @NotBlank(message = "设备ID不能为空")
    private String deviceId;

    /**
     * APP版本
     */
    private String appVersion;
}
```

- [ ] **Step 5: 实现 DeviceService**

```java
package com.sanye.strategy.device.service;

import com.sanye.strategy.common.base.DefaultQueryWrapper;
import com.sanye.strategy.common.util.HashUtil;
import com.sanye.strategy.device.dto.DeviceInfo;
import com.sanye.strategy.domain.UmsUserLoginDevice;
import com.sanye.strategy.enums.DeviceTypeEnum;
import com.sanye.strategy.enums.YesNoEnum;
import com.sanye.strategy.service.UmsUserLoginDeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 登录设备/会话门面 — {@code ums_user_login_device} 会话行属主
 * </p>
 * <p>
 * 认证与设备管理共用本服务，会话行读写一律经此收口（spec 3.1）：创建会话、按 refreshToken 哈希定位、
 * 轮换、失效、整户失效、列表。本批提供会话属主核心；设备管理端点（列表/踢设备）批4 在此之上扩展。
 * refreshToken 只存 SHA-256 哈希（{@code refresh_token_hash}），不存明文。
 * </p>
 * <p>
 * 设计说明（门面模式）：
 * <ul>
 *   <li>角色：子系统（{@link UmsUserLoginDeviceService} 单表数据访问）之上的门面，暴露会话级粗粒度操作。</li>
 *   <li>优点：表属主单一，会话读写规则（hash 化、is_current 语义、过期计算）集中一处；设备管理与认证共用。</li>
 *   <li>缺点：多一层抽象；批量失效（{@link #invalidateAllByUser}）逐行更新，量大时改走单条 UPDATE 批量 SQL（YAGNI，本批不引入）。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Service
@RequiredArgsConstructor
public class DeviceService {

    private final UmsUserLoginDeviceService loginDeviceService;

    /**
     * 创建登录会话行
     *
     * @param userId       用户ID
     * @param info         设备信息（可 null）
     * @param loginIp      登录 IP（服务端注入）
     * @param refreshToken 明文 refreshToken（仅此处哈希后落库）
     * @param ttlDays      refresh 有效期天数
     * @return 已落库会话实体（id 即 jti）
     */
    public UmsUserLoginDevice createSession(Long userId, DeviceInfo info, String loginIp,
                                            String refreshToken, int ttlDays) {
        UmsUserLoginDevice entity = new UmsUserLoginDevice();
        entity.setUserId(userId);
        entity.setDeviceType(info == null ? null : DeviceTypeEnum.valueOf(info.getDeviceType()));
        entity.setDeviceOs(info == null ? null : info.getDeviceOs());
        entity.setDeviceBrand(info == null ? null : info.getDeviceBrand());
        entity.setDeviceModel(info == null ? null : info.getDeviceModel());
        entity.setDeviceId(info == null ? null : info.getDeviceId());
        entity.setAppVersion(info == null ? null : info.getAppVersion());
        entity.setLoginIp(loginIp);
        entity.setLoginTime(LocalDateTime.now());
        entity.setExpireTime(LocalDateTime.now().plusDays(ttlDays));
        entity.setIsCurrent(YesNoEnum.YES);
        entity.setRefreshTokenHash(HashUtil.sha256Hex(refreshToken));
        loginDeviceService.insert(entity);
        return entity;
    }

    /**
     * 按 refreshToken 哈希定位有效会话（未逻辑删除 + is_current=1）
     *
     * @param hash refreshToken SHA-256 哈希
     * @return 会话实体，未找到返回 null
     */
    public UmsUserLoginDevice findByRefreshTokenHash(String hash) {
        return loginDeviceService.getOne(new DefaultQueryWrapper<UmsUserLoginDevice>()
                .eq("refresh_token_hash", hash)
                .eq("is_current", YesNoEnum.YES.getCode()));
    }

    /**
     * 轮换 refreshToken（防重放）：更新会话行哈希 + 过期时间，其余字段不动（部分更新）
     */
    public void rotateRefreshToken(Long sessionId, String newRefreshToken, int ttlDays) {
        UmsUserLoginDevice entity = new UmsUserLoginDevice();
        entity.setId(sessionId);
        entity.setRefreshTokenHash(HashUtil.sha256Hex(newRefreshToken));
        entity.setExpireTime(LocalDateTime.now().plusDays(ttlDays));
        loginDeviceService.updateById(entity);
    }

    /**
     * 失效单会话（登出）
     */
    public void invalidateSession(Long sessionId) {
        UmsUserLoginDevice entity = new UmsUserLoginDevice();
        entity.setId(sessionId);
        entity.setIsCurrent(YesNoEnum.NO);
        loginDeviceService.updateById(entity);
    }

    /**
     * 失效某用户全部会话（改密吊销/后台冻结注销，批3/批5 使用）
     */
    public void invalidateAllByUser(Long userId) {
        for (UmsUserLoginDevice session : listByUser(userId)) {
            invalidateSession(session.getId());
        }
    }

    /**
     * 用户全部会话行（按登录时间倒序）
     */
    public List<UmsUserLoginDevice> listByUser(Long userId) {
        return loginDeviceService.list(new DefaultQueryWrapper<UmsUserLoginDevice>()
                .eq("user_id", userId)
                .orderByDesc("login_time"));
    }
}
```

- [ ] **Step 6: 跑测试验证通过**

Run: `mvn test -Dtest=DeviceServiceTest`
Expected: 4 个测试全部 PASS。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/sanye/strategy/device/dto/DeviceInfo.java src/main/java/com/sanye/strategy/device/service/DeviceService.java src/main/java/com/sanye/strategy/common/util/HashUtil.java src/test/java/com/sanye/strategy/device/service/DeviceServiceTest.java
git commit -m "feat(auth): 批1 DeviceService — 会话行属主核心（createSession/轮换/失效/哈希定位）+ HashUtil + 单测"
```

---

### Task 11: TokenAuthInterceptor + WebMvcConfig（认证管道）

**Files:**
- Create: `src/main/java/com/sanye/strategy/common/interceptor/TokenAuthInterceptor.java`
- Create: `src/main/java/com/sanye/strategy/common/config/WebMvcConfig.java`
- Test: `src/test/java/com/sanye/strategy/common/interceptor/TokenAuthInterceptorTest.java`

**Interfaces:**
- Consumes: `JwtUtil.parseToken` / `JwtUtil`、`JtiBlacklistService.isRevoked`（Task 6/8）、`UserContext`（Task 5）
- Produces: 白名单外请求强制认证，认证通过填充 `UserContext`、`afterCompletion` 清除；`WebMvcConfig` 注册拦截器并声明白名单

- [ ] **Step 1: 写失败单测**

```java
package com.sanye.strategy.common.interceptor;

import com.sanye.strategy.common.auth.JtiBlacklistService;
import com.sanye.strategy.common.auth.JwtUtil;
import com.sanye.strategy.common.auth.UserContext;
import com.sanye.strategy.common.exception.BizException;
import com.sanye.strategy.common.response.ResultCode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <p>
 * {@link TokenAuthInterceptor} 认证链验证（白名单由 WebMvcConfig 注册排除，拦截器自身不感知）
 * </p>
 *
 * @author 31372
 */
class TokenAuthInterceptorTest {

    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final JtiBlacklistService jtiBlacklistService = mock(JtiBlacklistService.class);
    private final TokenAuthInterceptor interceptor = new TokenAuthInterceptor(jwtUtil, jtiBlacklistService);

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void shouldRejectMissingHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.UNAUTHORIZED);
    }

    @Test
    void shouldRejectWrongScheme() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.UNAUTHORIZED);
    }

    @Test
    void shouldFillUserContextOnValidAccessToken() {
        when(jwtUtil.parseToken("token")).thenReturn(Jwts.claims(Map.of(
                "type", "ACCESS", "userId", 1L, "userType", 1, "jti", 10L, "deviceId", "dev-1")));
        when(jtiBlacklistService.isRevoked(10L)).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isTrue();
        assertThat(UserContext.get().getUserId()).isEqualTo(1L);
        assertThat(UserContext.get().getJti()).isEqualTo(10L);
        assertThat(UserContext.get().getDeviceId()).isEqualTo("dev-1");
    }

    @Test
    void shouldRejectBlacklistedToken() {
        when(jwtUtil.parseToken("token")).thenReturn(Jwts.claims(Map.of(
                "type", "ACCESS", "userId", 1L, "userType", 1, "jti", 10L, "deviceId", "dev-1")));
        when(jtiBlacklistService.isRevoked(10L)).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.TOKEN_EXPIRED);
    }

    @Test
    void shouldRejectNonAccessTypeToken() {
        when(jwtUtil.parseToken("token")).thenReturn(Jwts.claims(Map.of(
                "type", "REFRESH", "userId", 1L, "userType", 1, "jti", 10L)));
        when(jtiBlacklistService.isRevoked(10L)).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.UNAUTHORIZED);
    }

    @Test
    void shouldRejectInvalidSignature() {
        when(jwtUtil.parseToken("bad")).thenThrow(new JwtException("验签失败"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer bad");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.UNAUTHORIZED);
    }

    @Test
    void shouldClearUserContextAfterCompletion() {
        UserContext.set(new UserContext(1L, null, 10L, "dev-1"));

        interceptor.afterCompletion(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object(), null);

        assertThat(UserContext.get()).isNull();
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `mvn test -Dtest=TokenAuthInterceptorTest`
Expected: 编译失败（`TokenAuthInterceptor` 不存在）。

- [ ] **Step 3: 实现 TokenAuthInterceptor**

```java
package com.sanye.strategy.common.interceptor;

import com.sanye.strategy.common.auth.JtiBlacklistService;
import com.sanye.strategy.common.auth.JwtUtil;
import com.sanye.strategy.common.auth.UserContext;
import com.sanye.strategy.common.exception.BizException;
import com.sanye.strategy.common.response.ResultCode;
import com.sanye.strategy.enums.UserTypeEnum;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

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

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith(BEARER_PREFIX)) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        Claims claims;
        try {
            claims = jwtUtil.parseToken(token);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BizException(ResultCode.UNAUTHORIZED, "登录已过期，请重新登录");
        }
        if (!TYPE_ACCESS.equals(claims.get("type", String.class))) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        Long jti = claims.get("jti", Number.class).longValue();
        if (jtiBlacklistService.isRevoked(jti)) {
            throw new BizException(ResultCode.TOKEN_EXPIRED, "登录已失效，请重新登录");
        }
        Long userId = claims.get("userId", Number.class).longValue();
        UserTypeEnum userType = UserTypeEnum.valueOf(claims.get("userType", Number.class).intValue());
        String deviceId = claims.get("deviceId", String.class);
        UserContext.set(new UserContext(userId, userType, jti, deviceId));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }
}
```

- [ ] **Step 4: 实现 WebMvcConfig**

```java
package com.sanye.strategy.common.config;

import com.sanye.strategy.common.interceptor.TokenAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * <p>
 * Web MVC 配置 — 注册认证拦截器与白名单
 * </p>
 * <p>
 * 白名单路径不经过 {@link TokenAuthInterceptor}（放行）：登录/注册/刷新/MFA 验证发生在签发 token 前，
 * 必须免认证；actuator 健康检查与 /error 亦放行。白名单集中维护于此。
 * </p>
 *
 * @author 31372
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    /** 白名单：无需登录即可访问 */
    private static final String[] WHITE_LIST = {
            "/auth/login", "/auth/register", "/auth/refresh", "/auth/mfa/verify",
            "/actuator/**", "/error"
    };

    private final TokenAuthInterceptor tokenAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tokenAuthInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(WHITE_LIST);
    }
}
```

- [ ] **Step 5: 跑测试验证通过**

Run: `mvn test -Dtest=TokenAuthInterceptorTest`
Expected: 7 个测试全部 PASS。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/sanye/strategy/common/interceptor/TokenAuthInterceptor.java src/main/java/com/sanye/strategy/common/config/WebMvcConfig.java src/test/java/com/sanye/strategy/common/interceptor/TokenAuthInterceptorTest.java
git commit -m "feat(auth): 批1 TokenAuthInterceptor + WebMvcConfig — 认证管道（验签/type/jti黑名单/UserContext填充），白名单含 mfa/verify"
```

---

### Task 12: Auth DTOs（Register/Login/Refresh/MfaVerify/MfaChallenge/TokenVO）

**Files:**
- Create: `src/main/java/com/sanye/strategy/auth/dto/RegisterDTO.java`
- Create: `src/main/java/com/sanye/strategy/auth/dto/LoginDTO.java`
- Create: `src/main/java/com/sanye/strategy/auth/dto/RefreshDTO.java`
- Create: `src/main/java/com/sanye/strategy/auth/dto/MfaVerifyDTO.java`
- Create: `src/main/java/com/sanye/strategy/auth/dto/TokenVO.java`

**Interfaces:**
- Produces: 五个 DTO/VO 类型（Task 13 AuthService 签名、Task 14 AuthController 请求/响应）

- [ ] **Step 1: 创建 RegisterDTO**

```java
package com.sanye.strategy.auth.dto;

import com.sanye.strategy.device.dto.DeviceInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * <p>
 * 注册请求 DTO
 * </p>
 * <p>
 * 用户类型不对外开放（注册恒为普通用户，防提权）；昵称缺省回落用户名。
 * </p>
 *
 * @author 31372
 */
@Data
public class RegisterDTO {

    /** 登录账号（4-50 位字母/数字/下划线） */
    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_]{4,50}$", message = "用户名需为4-50位字母、数字或下划线")
    private String username;

    /** 明文密码（≥8 位，字母数字组合由业务校验） */
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度需在8-64位之间")
    private String password;

    /** 手机号（可选） */
    @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
    private String phone;

    /** 邮箱（可选） */
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 昵称（可选） */
    @Size(max = 50, message = "昵称长度不能超过50位")
    private String nickname;

    /** 设备信息（落库会话行 + 注册渠道推导） */
    @Valid
    @NotNull(message = "设备信息不能为空")
    private DeviceInfo deviceInfo;
}
```

- [ ] **Step 2: 创建 LoginDTO**

```java
package com.sanye.strategy.auth.dto;

import com.sanye.strategy.device.dto.DeviceInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * <p>
 * 登录请求 DTO
 * </p>
 * <p>
 * account 可为手机号/邮箱/用户名（服务端判型，见 AuthService）。
 * </p>
 *
 * @author 31372
 */
@Data
public class LoginDTO {

    /** 账号（手机号/邮箱/用户名） */
    @NotBlank(message = "账号不能为空")
    private String account;

    /** 明文密码 */
    @NotBlank(message = "密码不能为空")
    private String password;

    /** 设备信息 */
    @Valid
    @NotNull(message = "设备信息不能为空")
    private DeviceInfo deviceInfo;
}
```

- [ ] **Step 3: 创建 RefreshDTO**

```java
package com.sanye.strategy.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * <p>
 * 刷新 token 请求 DTO
 * </p>
 *
 * @author 31372
 */
@Data
public class RefreshDTO {

    /** 不透明 refreshToken */
    @NotBlank(message = "refreshToken 不能为空")
    private String refreshToken;

    /** 设备 ID（与会话行比对，防跨设备盗用） */
    @NotBlank(message = "deviceId 不能为空")
    private String deviceId;
}
```

- [ ] **Step 4: 创建 MfaVerifyDTO + MfaChallengeVO**

```java
package com.sanye.strategy.auth.dto;

import com.sanye.strategy.device.dto.DeviceInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * <p>
 * MFA 二次验证请求 DTO
 * </p>
 * <p>
 * tempToken + OTP 双要素：tempToken 为登录步骤 5 密码校验通过后签发的挑战凭证（绑定账号/设备，5min 一次性）；
 * 本接口不再传密码/账号——userId 由挑战绑定解出，密码因子已在登录时校验。
 * deviceInfo 供会话行落库 + deviceId 与挑战绑定比对（防跨设备复用）。
 * </p>
 *
 * @author 31372
 */
@Data
public class MfaVerifyDTO {

    /** 挑战凭证（登录 403 MFA_REQUIRED 响应携带） */
    @NotBlank(message = "挑战凭证不能为空")
    private String tempToken;

    /** 6 位 TOTP 验证码 */
    @NotBlank(message = "验证码不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "验证码为6位数字")
    private String code;

    /** 设备信息（会话行落库 + deviceId 比对） */
    @Valid
    @NotNull(message = "设备信息不能为空")
    private DeviceInfo deviceInfo;
}
```

同包新增挑战响应 VO（仅 403 MFA_REQUIRED 响应体携带，一次性返回）：

```java
package com.sanye.strategy.auth.dto;

import lombok.Data;

/**
 * <p>
 * MFA 挑战凭证响应 VO
 * </p>
 * <p>
 * 登录 mfa=1 时随 403 MFA_REQUIRED 返回；tempToken 为 5min 短时效瞬态凭证，
 * GETDEL 单次消费后即失效，客户端凭此调用 /auth/mfa/verify。
 * </p>
 *
 * @author 31372
 */
@Data
public class MfaChallengeVO {

    /** 挑战凭证（32B hex） */
    private String tempToken;

    /** 有效秒数（TTL，本批 300） */
    private Integer expiresIn;
}
```

- [ ] **Step 5: 创建 TokenVO**

```java
package com.sanye.strategy.auth.dto;

import lombok.Data;

/**
 * <p>
 * 双 Token 响应 VO
 * </p>
 * <p>
 * 客户端保存：accessToken 走内存/Header，refreshToken 走安全存储；
 * 不返回任何敏感字段（userId/userType 经 /users/me 获取）。
 * </p>
 *
 * @author 31372
 */
@Data
public class TokenVO {

    /** JWT accessToken（30min） */
    private String accessToken;

    /** 不透明 refreshToken（14 天，一次性） */
    private String refreshToken;

    /** accessToken 有效期（秒），供客户端预判刷新时机 */
    private Integer accessExpiresIn;
}
```

- [ ] **Step 6: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/sanye/strategy/auth/dto/
git commit -m "feat(auth): 批1 Auth DTOs — Register/Login/Refresh/MfaVerify{tempToken,code,deviceInfo}/MfaChallengeVO/TokenVO（jakarta 校验）"
```

---

### Task 13: AuthService（注册/登录/刷新/登出/MFA 验证）+ 单测

**Files:**
- Create: `src/main/java/com/sanye/strategy/auth/service/AuthService.java`
- Create: `src/main/java/com/sanye/strategy/common/util/IpUtils.java`
- Test: `src/test/java/com/sanye/strategy/auth/service/AuthServiceTest.java`

**Interfaces:**
- Consumes: 全部 base service + 门面/工具（`UmsUserService`、`UmsUserAccountSecurityService`、`UmsUserProfileService`、`DeviceService`、`PasswordEncoder`、`JwtUtil`、`TotpUtil`、`JtiBlacklistService`、`ChallengeTokenService`、`TransactionTemplate`）、DTO（Task 12）。`PasswordEncoder` 仅 register/login 用；`verifyMfa` 用 `ChallengeTokenService`/`TotpUtil`
- Produces: `AuthService.register(RegisterDTO, String clientIp):TokenVO`、`login(LoginDTO, String clientIp):TokenVO`、`verifyMfa(MfaVerifyDTO, String clientIp):TokenVO`、`refresh(RefreshDTO):TokenVO`、`logout()`、`IpUtils.getClientIp(HttpServletRequest):String`

- [ ] **Step 1: 写失败单测**

```java
package com.sanye.strategy.auth.service;

import com.sanye.strategy.auth.dto.LoginDTO;
import com.sanye.strategy.auth.dto.MfaChallengeVO;
import com.sanye.strategy.auth.dto.MfaVerifyDTO;
import com.sanye.strategy.auth.dto.RefreshDTO;
import com.sanye.strategy.auth.dto.RegisterDTO;
import com.sanye.strategy.auth.dto.TokenVO;
import com.sanye.strategy.common.auth.ChallengeTokenService;
import com.sanye.strategy.common.auth.JtiBlacklistService;
import com.sanye.strategy.common.auth.JwtUtil;
import com.sanye.strategy.common.auth.PasswordEncoder;
import com.sanye.strategy.common.auth.TotpUtil;
import com.sanye.strategy.common.auth.UserContext;
import com.sanye.strategy.common.exception.BizException;
import com.sanye.strategy.common.response.ResultCode;
import com.sanye.strategy.device.dto.DeviceInfo;
import com.sanye.strategy.device.service.DeviceService;
import com.sanye.strategy.domain.UmsUser;
import com.sanye.strategy.domain.UmsUserAccountSecurity;
import com.sanye.strategy.domain.UmsUserLoginDevice;
import com.sanye.strategy.enums.UserStatusEnum;
import com.sanye.strategy.enums.UserTypeEnum;
import com.sanye.strategy.enums.YesNoEnum;
import com.sanye.strategy.service.UmsUserAccountSecurityService;
import com.sanye.strategy.service.UmsUserProfileService;
import com.sanye.strategy.service.UmsUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <p>
 * {@link AuthService} 认证流程分支验证（TransactionTemplate 以 mock 直通回调，无真实事务）
 * </p>
 *
 * @author 31372
 */
class AuthServiceTest {

    private UmsUserService userService;
    private UmsUserAccountSecurityService securityService;
    private UmsUserProfileService profileService;
    private DeviceService deviceService;
    private PasswordEncoder passwordEncoder;
    private JwtUtil jwtUtil;
    private TotpUtil totpUtil;
    private JtiBlacklistService jtiBlacklistService;
    private ChallengeTokenService challengeTokenService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userService = mock(UmsUserService.class);
        securityService = mock(UmsUserAccountSecurityService.class);
        profileService = mock(UmsUserProfileService.class);
        deviceService = mock(DeviceService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtUtil = mock(JwtUtil.class);
        totpUtil = mock(TotpUtil.class);
        jtiBlacklistService = mock(JtiBlacklistService.class);
        challengeTokenService = mock(ChallengeTokenService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(jwtUtil.getAccessTokenTtlSeconds()).thenReturn(1800L);
        when(jwtUtil.generateAccessToken(any(), any(), any(), any())).thenReturn("mock-access-token");
        authService = new AuthService(userService, securityService, profileService, deviceService,
                passwordEncoder, jwtUtil, totpUtil, jtiBlacklistService, challengeTokenService, transactionTemplate);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private RegisterDTO validRegisterDto() {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("user01");
        dto.setPassword("abc12345");
        dto.setDeviceInfo(deviceInfo());
        return dto;
    }

    private LoginDTO loginDto(String account, String password) {
        LoginDTO dto = new LoginDTO();
        dto.setAccount(account);
        dto.setPassword(password);
        dto.setDeviceInfo(deviceInfo());
        return dto;
    }

    private DeviceInfo deviceInfo() {
        DeviceInfo info = new DeviceInfo();
        info.setDeviceType(1);
        info.setDeviceId("device-1");
        return info;
    }

    private UmsUser normalUser() {
        UmsUser user = new UmsUser();
        user.setId(1L);
        user.setUsername("user01");
        user.setPassword("hashed");
        user.setUserType(UserTypeEnum.NORMAL_USER);
        user.setUserStatus(UserStatusEnum.NORMAL);
        return user;
    }

    private UmsUserAccountSecurity noMfaSecurity() {
        UmsUserAccountSecurity security = new UmsUserAccountSecurity();
        security.setId(100L);
        security.setUserId(1L);
        security.setPasswordErrorCount(0);
        security.setLockTime(null);
        security.setMfaStatus(YesNoEnum.NO);
        return security;
    }

    // ==================== 注册 ====================

    @Test
    void shouldRegisterAndIssueTokens() {
        when(userService.count(any())).thenReturn(0L);
        when(passwordEncoder.encode("abc12345")).thenReturn("$2a$10$encoded");
        UmsUserLoginDevice session = new UmsUserLoginDevice();
        session.setId(10L);
        session.setDeviceId("device-1");
        when(deviceService.createSession(any(), any(), any(), any(), any())).thenReturn(session);

        TokenVO vo = authService.register(validRegisterDto(), "127.0.0.1");

        assertThat(vo.getAccessToken()).isEqualTo("mock-access-token");
        assertThat(vo.getAccessExpiresIn()).isEqualTo(1800);
        verify(userService).insert(any());
        verify(securityService).insert(any());
        verify(profileService).insert(any());
        verify(deviceService).createSession(any(), any(), any(), any(), any());
    }

    @Test
    void shouldRejectDuplicateUsername() {
        when(userService.count(any())).thenReturn(1L);

        assertThatThrownBy(() -> authService.register(validRegisterDto(), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.CONFLICT);
    }

    @Test
    void shouldRejectWeakPassword() {
        RegisterDTO dto = validRegisterDto();
        dto.setPassword("12345678");

        assertThatThrownBy(() -> authService.register(dto, "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    // ==================== 登录 ====================

    @Test
    void shouldLoginSuccessAndClearErrorCount() {
        when(userService.getOne(any())).thenReturn(normalUser());
        when(securityService.getOne(any())).thenReturn(noMfaSecurity());
        when(passwordEncoder.matches("abc12345", "hashed")).thenReturn(true);
        UmsUserLoginDevice session = new UmsUserLoginDevice();
        session.setId(10L);
        session.setDeviceId("device-1");
        when(deviceService.createSession(any(), any(), any(), any(), any())).thenReturn(session);

        TokenVO vo = authService.login(loginDto("user01", "abc12345"), "127.0.0.1");

        assertThat(vo.getAccessToken()).isEqualTo("mock-access-token");
        verify(securityService).updateById(any());
        verify(userService).updateById(any());
    }

    @Test
    void shouldNotDistinguishWrongPasswordOrMissingUser() {
        when(userService.getOne(any())).thenReturn(null);

        assertThatThrownBy(() -> authService.login(loginDto("nobody", "abc12345"), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.UNAUTHORIZED);
    }

    @Test
    void shouldIncreaseErrorCountOnWrongPassword() {
        when(userService.getOne(any())).thenReturn(normalUser());
        when(securityService.getOne(any())).thenReturn(noMfaSecurity());
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(loginDto("user01", "wrong"), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.UNAUTHORIZED);

        verify(securityService).updateById(any());
        verify(deviceService, never()).createSession(any(), any(), any(), any(), any());
    }

    @Test
    void shouldLockAccountAtThreshold() {
        UmsUserAccountSecurity security = noMfaSecurity();
        security.setPasswordErrorCount(4);
        when(userService.getOne(any())).thenReturn(normalUser());
        when(securityService.getOne(any())).thenReturn(security);
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(loginDto("user01", "wrong"), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.UNAUTHORIZED);

        org.mockito.ArgumentCaptor<UmsUserAccountSecurity> captor =
                org.mockito.ArgumentCaptor.forClass(UmsUserAccountSecurity.class);
        verify(securityService).updateById(captor.capture());
        assertThat(captor.getValue().getPasswordErrorCount()).isEqualTo(5);
        assertThat(captor.getValue().getLockTime()).isNotNull();
    }

    @Test
    void shouldRejectLockedAccount() {
        UmsUserAccountSecurity security = noMfaSecurity();
        security.setLockTime(LocalDateTime.now().plusMinutes(10));
        when(userService.getOne(any())).thenReturn(normalUser());
        when(securityService.getOne(any())).thenReturn(security);

        assertThatThrownBy(() -> authService.login(loginDto("user01", "abc12345"), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.ACCOUNT_LOCKED);
    }

    @Test
    void shouldRequireMfaAndIssueChallengeToken() {
        UmsUserAccountSecurity security = noMfaSecurity();
        security.setMfaStatus(YesNoEnum.YES);
        when(userService.getOne(any())).thenReturn(normalUser());
        when(securityService.getOne(any())).thenReturn(security);
        when(passwordEncoder.matches("abc12345", "hashed")).thenReturn(true);
        when(challengeTokenService.issue(1L, "device-1", 300)).thenReturn("challenge-token");

        Throwable thrown = catchThrowable(() -> authService.login(loginDto("user01", "abc12345"), "127.0.0.1"));

        assertThat(thrown).isInstanceOf(BizException.class);
        BizException ex = (BizException) thrown;
        assertThat(ex.getResultCode()).isEqualTo(ResultCode.MFA_REQUIRED);
        assertThat(ex.getPayload()).isInstanceOf(MfaChallengeVO.class);
        MfaChallengeVO challenge = (MfaChallengeVO) ex.getPayload();
        assertThat(challenge.getTempToken()).isEqualTo("challenge-token");
        assertThat(challenge.getExpiresIn()).isEqualTo(300);
        verify(challengeTokenService).issue(1L, "device-1", 300);
        verify(deviceService, never()).createSession(any(), any(), any(), any(), any());
        verify(securityService, never()).updateById(any());   // MFA 分支不清计数
    }

    @Test
    void shouldRejectFrozenAccount() {
        UmsUser user = normalUser();
        user.setUserStatus(UserStatusEnum.FROZEN);
        when(userService.getOne(any())).thenReturn(user);

        assertThatThrownBy(() -> authService.login(loginDto("user01", "abc12345"), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.ACCOUNT_DISABLED);
    }

    @Test
    void shouldRejectCancelledAccount() {
        UmsUser user = normalUser();
        user.setUserStatus(UserStatusEnum.CANCELLED);
        when(userService.getOne(any())).thenReturn(user);

        assertThatThrownBy(() -> authService.login(loginDto("user01", "abc12345"), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.ACCOUNT_DELETED);
    }

    // ==================== MFA 验证 ====================

    @Test
    void shouldVerifyMfaAndIssueTokens() {
        when(challengeTokenService.consume("challenge-token"))
                .thenReturn(new ChallengeTokenService.ChallengeBinding(1L, "device-1"));
        when(userService.getById(1L)).thenReturn(normalUser());
        UmsUserAccountSecurity security = noMfaSecurity();
        security.setMfaSecret("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        when(securityService.getOne(any())).thenReturn(security);
        when(totpUtil.verify("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "123456")).thenReturn(true);
        UmsUserLoginDevice session = new UmsUserLoginDevice();
        session.setId(10L);
        session.setDeviceId("device-1");
        when(deviceService.createSession(any(), any(), any(), any(), any())).thenReturn(session);

        MfaVerifyDTO dto = new MfaVerifyDTO();
        dto.setTempToken("challenge-token");
        dto.setCode("123456");
        dto.setDeviceInfo(deviceInfo());

        TokenVO vo = authService.verifyMfa(dto, "127.0.0.1");

        assertThat(vo.getAccessToken()).isEqualTo("mock-access-token");
        verify(challengeTokenService).consume("challenge-token");
        verify(securityService).updateById(any());
    }

    @Test
    void shouldRejectWrongMfaCodeAndIncrementCounter() {
        when(challengeTokenService.consume("challenge-token"))
                .thenReturn(new ChallengeTokenService.ChallengeBinding(1L, "device-1"));
        when(userService.getById(1L)).thenReturn(normalUser());
        UmsUserAccountSecurity security = noMfaSecurity();
        security.setMfaSecret("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        when(securityService.getOne(any())).thenReturn(security);
        when(totpUtil.verify(any(), any())).thenReturn(false);

        MfaVerifyDTO dto = new MfaVerifyDTO();
        dto.setTempToken("challenge-token");
        dto.setCode("000000");
        dto.setDeviceInfo(deviceInfo());

        assertThatThrownBy(() -> authService.verifyMfa(dto, "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.UNAUTHORIZED);
        verify(challengeTokenService).consume("challenge-token");
        verify(securityService).updateById(any());
    }

    @Test
    void shouldRejectConsumedOrExpiredChallenge() {
        when(challengeTokenService.consume("gone")).thenReturn(null);
        MfaVerifyDTO dto = new MfaVerifyDTO();
        dto.setTempToken("gone");
        dto.setCode("123456");
        dto.setDeviceInfo(deviceInfo());

        assertThatThrownBy(() -> authService.verifyMfa(dto, "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.MFA_CHALLENGE_EXPIRED);
        verify(securityService, never()).updateById(any());
    }

    @Test
    void shouldRejectChallengeFromAnotherDevice() {
        when(challengeTokenService.consume("challenge-token"))
                .thenReturn(new ChallengeTokenService.ChallengeBinding(1L, "device-1"));
        DeviceInfo other = deviceInfo();
        other.setDeviceId("other-device");
        MfaVerifyDTO dto = new MfaVerifyDTO();
        dto.setTempToken("challenge-token");
        dto.setCode("123456");
        dto.setDeviceInfo(other);

        assertThatThrownBy(() -> authService.verifyMfa(dto, "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.MFA_CHALLENGE_EXPIRED);
    }

    // ==================== 刷新 ====================

    @Test
    void shouldRotateRefreshTokenAndIssueNewAccess() {
        UmsUserLoginDevice session = new UmsUserLoginDevice();
        session.setId(10L);
        session.setUserId(1L);
        session.setDeviceId("device-1");
        session.setExpireTime(LocalDateTime.now().plusDays(14));
        when(deviceService.findByRefreshTokenHash(any())).thenReturn(session);
        when(jtiBlacklistService.isRevoked(10L)).thenReturn(false);
        when(userService.getById(1L)).thenReturn(normalUser());

        RefreshDTO dto = new RefreshDTO();
        dto.setRefreshToken("refresh-token");
        dto.setDeviceId("device-1");

        TokenVO vo = authService.refresh(dto);

        assertThat(vo.getAccessToken()).isEqualTo("mock-access-token");
        assertThat(vo.getRefreshToken()).isNotEqualTo("refresh-token");
        verify(deviceService).rotateRefreshToken(any(), any(), any());
        verify(jtiBlacklistService).remove(10L);
    }

    @Test
    void shouldRejectRefreshWithRevokedSession() {
        UmsUserLoginDevice session = new UmsUserLoginDevice();
        session.setId(10L);
        session.setUserId(1L);
        session.setDeviceId("device-1");
        session.setExpireTime(LocalDateTime.now().plusDays(14));
        when(deviceService.findByRefreshTokenHash(any())).thenReturn(session);
        when(jtiBlacklistService.isRevoked(10L)).thenReturn(true);

        RefreshDTO dto = new RefreshDTO();
        dto.setRefreshToken("refresh-token");
        dto.setDeviceId("device-1");

        assertThatThrownBy(() -> authService.refresh(dto))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.TOKEN_EXPIRED);
        verify(deviceService, never()).rotateRefreshToken(any(), any(), any());
    }

    @Test
    void shouldRejectRefreshWithDeviceMismatch() {
        UmsUserLoginDevice session = new UmsUserLoginDevice();
        session.setId(10L);
        session.setUserId(1L);
        session.setDeviceId("other-device");
        session.setExpireTime(LocalDateTime.now().plusDays(14));
        when(deviceService.findByRefreshTokenHash(any())).thenReturn(session);

        RefreshDTO dto = new RefreshDTO();
        dto.setRefreshToken("refresh-token");
        dto.setDeviceId("device-1");

        assertThatThrownBy(() -> authService.refresh(dto))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.TOKEN_EXPIRED);
    }

    // ==================== 登出 ====================

    @Test
    void shouldLogoutInvalidateSessionAndBlacklist() {
        UserContext.set(new UserContext(1L, UserTypeEnum.NORMAL_USER, 10L, "device-1"));

        authService.logout();

        verify(deviceService).invalidateSession(10L);
        verify(jtiBlacklistService).revoke(10L, 1800L);
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `mvn test -Dtest=AuthServiceTest`
Expected: 编译失败（`AuthService`/`IpUtils` 不存在）。

- [ ] **Step 3: 实现 IpUtils**

```java
package com.sanye.strategy.common.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * <p>
 * 客户端 IP 工具
 * </p>
 * <p>
 * 优先取 X-Forwarded-For 首段（经代理），否则取 remoteAddr。
 * </p>
 *
 * @author 31372
 */
public final class IpUtils {

    private IpUtils() {
    }

    public static String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
```

- [ ] **Step 4: 实现 AuthService**

```java
package com.sanye.strategy.auth.service;

import com.sanye.strategy.auth.dto.LoginDTO;
import com.sanye.strategy.auth.dto.MfaChallengeVO;
import com.sanye.strategy.auth.dto.MfaVerifyDTO;
import com.sanye.strategy.auth.dto.RefreshDTO;
import com.sanye.strategy.auth.dto.RegisterDTO;
import com.sanye.strategy.auth.dto.TokenVO;
import com.sanye.strategy.common.auth.ChallengeTokenService;
import com.sanye.strategy.common.auth.JtiBlacklistService;
import com.sanye.strategy.common.auth.JwtUtil;
import com.sanye.strategy.common.auth.PasswordEncoder;
import com.sanye.strategy.common.auth.TotpUtil;
import com.sanye.strategy.common.auth.UserContext;
import com.sanye.strategy.common.base.DefaultQueryWrapper;
import com.sanye.strategy.common.exception.BizException;
import com.sanye.strategy.common.response.ResultCode;
import com.sanye.strategy.device.dto.DeviceInfo;
import com.sanye.strategy.device.service.DeviceService;
import com.sanye.strategy.domain.UmsUser;
import com.sanye.strategy.domain.UmsUserAccountSecurity;
import com.sanye.strategy.domain.UmsUserLoginDevice;
import com.sanye.strategy.domain.UmsUserProfile;
import com.sanye.strategy.enums.DeviceTypeEnum;
import com.sanye.strategy.enums.RegisterChannelEnum;
import com.sanye.strategy.enums.UserStatusEnum;
import com.sanye.strategy.enums.UserTypeEnum;
import com.sanye.strategy.enums.YesNoEnum;
import com.sanye.strategy.service.UmsUserAccountSecurityService;
import com.sanye.strategy.service.UmsUserProfileService;
import com.sanye.strategy.service.UmsUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * <p>
 * 认证门面 — 注册/登录/刷新/登出/MFA 二次验证
 * </p>
 * <p>
 * 编排 3 个 base service + {@link DeviceService}（会话行属主），承载跨表事务与防爆破/状态校验规则。
 * 跨表写（注册 3-5、登录 5(成功清零)-7、verifyMfa 5(成功清零)-7）经共享 {@link TransactionTemplate} 单事务，
 * 门面非 {@code AbstractBaseService} 子类、够不到其 {@code doInTransaction} 钩子，故注入框架 Bean。
 * </p>
 * <p>
 * 安全约定：
 * <ul>
 *   <li>登录「用户不存在」与「密码错误」同一提示「账号或密码错误」，防账号枚举。</li>
 *   <li>防爆破：密码/OTP 错误共累计 {@code passwordErrorCount}，达阈值锁 30min（{@code lockTime}）。</li>
 *   <li>userStatus 校验在签发前（冻结 403 / 注销 410）；MFA 开启时登录发 5min 挑战凭证（tempToken）随 403 MFA_REQUIRED 返回，
 *       OTP 通过（verifyMfa）才清计数并发证——密码因子在登录步骤 5 校验，verify 仅验 OTP + 挑战绑定。</li>
 *   <li>refresh 轮换防重放：旧 refresh 一次性作废（写回新哈希）。</li>
 * </ul>
 * </p>
 * <p>
 * 设计说明（门面模式）：
 * <ul>
 *   <li>角色：门面，对外暴露粗粒度认证方法，编排子系统（base service）；Controller 只依赖本门面。</li>
 *   <li>优点：跨表事务与业务规则收口一处，Controller 保持瘦；可单测（mock 子系统与 TransactionTemplate）。</li>
 *   <li>缺点：多一层抽象；门面职责限定认证能力包，不做大杂烩。</li>
 * </ul>
 * </p>
 * <p>
 * 时序（登录，简化）：
 * <pre>
 * AuthController → AuthService.login(dto, ip)
 *   AuthService → userService.getOne(判型)          // 手机/邮箱/用户名
 *   AuthService → securityService.getOne(userId)    // 锁校验/计数/MFA
 *   AuthService → passwordEncoder.matches(...)       // 密码校验
 *   AuthService → transactionTemplate.execute {      // 成功路径单事务
 *       securityService.updateById(清计数+锁)
 *       deviceService.createSession(...)             // 会话行
 *       userService.updateById(lastLogin)
 *   }
 *   AuthService → jwtUtil.generateAccessToken(...)   // 签发（事务外）
 * AuthService --> AuthController: TokenVO
 * </pre>
 * </p>
 *
 * @author 31372
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int PASSWORD_ERROR_THRESHOLD = 5;
    private static final int LOCK_MINUTES = 30;
    private static final int REFRESH_TOKEN_TTL_DAYS = 14;
    private static final int CHALLENGE_TTL_SECONDS = 300;
    private static final String DEFAULT_PHONE_COUNTRY_CODE = "+86";
    private static final String INVALID_ACCOUNT_MESSAGE = "账号或密码错误";

    private final UmsUserService userService;
    private final UmsUserAccountSecurityService securityService;
    private final UmsUserProfileService profileService;
    private final DeviceService deviceService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final TotpUtil totpUtil;
    private final JtiBlacklistService jtiBlacklistService;
    private final ChallengeTokenService challengeTokenService;
    private final TransactionTemplate transactionTemplate;

    // ==================== 注册 ====================

    /**
     * 注册并返回双 Token
     *
     * @param dto      注册参数
     * @param clientIp 客户端 IP
     */
    public TokenVO register(RegisterDTO dto, String clientIp) {
        validatePasswordPolicy(dto.getPassword());
        if (userService.count(new DefaultQueryWrapper<UmsUser>()
                .eq("username", dto.getUsername())) > 0) {
            throw new BizException(ResultCode.CONFLICT, "用户名已被占用");
        }
        String refreshToken = generateRefreshToken();
        UmsUser user = buildRegisterUser(dto, clientIp);
        return transactionTemplate.execute(status -> {
            userService.insert(user);
            initSecurity(user.getId());
            initProfile(user.getId());
            UmsUserLoginDevice session = deviceService.createSession(
                    user.getId(), dto.getDeviceInfo(), clientIp, refreshToken, REFRESH_TOKEN_TTL_DAYS);
            return issueTokens(user, session.getId(), session.getDeviceId(), refreshToken);
        });
    }

    private UmsUser buildRegisterUser(RegisterDTO dto, String clientIp) {
        UmsUser user = new UmsUser();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname() == null || dto.getNickname().isBlank()
                ? dto.getUsername() : dto.getNickname());
        user.setPhone(normalizeNullable(dto.getPhone()));
        user.setEmail(normalizeNullable(dto.getEmail()));
        user.setPhoneCountryCode(DEFAULT_PHONE_COUNTRY_CODE);
        user.setUserType(UserTypeEnum.NORMAL_USER);
        user.setUserStatus(UserStatusEnum.NORMAL);
        user.setRegisterChannel(mapChannel(dto.getDeviceInfo()));
        user.setRegisterClientIp(clientIp);
        user.setRegisterDeviceId(dto.getDeviceInfo() == null ? null : dto.getDeviceInfo().getDeviceId());
        user.setIsVip(YesNoEnum.NO);
        return user;
    }

    private void initSecurity(Long userId) {
        UmsUserAccountSecurity security = new UmsUserAccountSecurity();
        security.setUserId(userId);
        security.setPasswordErrorCount(0);
        security.setHasSetPayPassword(YesNoEnum.NO);
        security.setSecretQuestionStatus(YesNoEnum.NO);
        security.setMfaStatus(YesNoEnum.NO);
        securityService.insert(security);
    }

    private void initProfile(Long userId) {
        UmsUserProfile profile = new UmsUserProfile();
        profile.setUserId(userId);
        profileService.insert(profile);
    }

    // ==================== 登录 ====================

    /**
     * 登录（含防爆破/状态校验/MFA 分支）
     */
    public TokenVO login(LoginDTO dto, String clientIp) {
        UmsUser user = findByAccount(dto.getAccount());
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, INVALID_ACCOUNT_MESSAGE);
        }
        checkUserStatus(user);
        UmsUserAccountSecurity security = loadOrCreateSecurity(user.getId());
        checkLocked(security);
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            increaseErrorCount(security);
            throw new BizException(ResultCode.UNAUTHORIZED, INVALID_ACCOUNT_MESSAGE);
        }
        if (YesNoEnum.YES.equals(security.getMfaStatus())) {
            // MFA 开启：签发 5min 挑战凭证（Redis-only，零 DB 写，不清计数——OTP 通过 verifyMfa 才清）
            String tempToken = challengeTokenService.issue(
                    user.getId(), dto.getDeviceInfo().getDeviceId(), CHALLENGE_TTL_SECONDS);
            MfaChallengeVO challenge = new MfaChallengeVO();
            challenge.setTempToken(tempToken);
            challenge.setExpiresIn(CHALLENGE_TTL_SECONDS);
            throw new BizException(ResultCode.MFA_REQUIRED, "请完成二次验证", challenge);
        }
        String refreshToken = generateRefreshToken();
        return transactionTemplate.execute(status -> {
            clearErrorCount(security);
            UmsUserLoginDevice session = deviceService.createSession(
                    user.getId(), dto.getDeviceInfo(), clientIp, refreshToken, REFRESH_TOKEN_TTL_DAYS);
            updateLastLogin(user, dto.getDeviceInfo(), clientIp);
            return issueTokens(user, session.getId(), session.getDeviceId(), refreshToken);
        });
    }

    /**
     * MFA 二次验证（挑战凭证 + OTP；密码因子已在登录步骤 5 校验，tempToken 即证明）
     * <p>
     * GETDEL 原子单次消费在方法入口（事务外），防重放；OTP 错 = 挑战已消费，重试须重新登录。
     * </p>
     */
    public TokenVO verifyMfa(MfaVerifyDTO dto, String clientIp) {
        // 1. GETDEL 原子单次消费挑战凭证（不存在/已消费/过期 → null）
        ChallengeTokenService.ChallengeBinding binding = challengeTokenService.consume(dto.getTempToken());
        if (binding == null) {
            throw new BizException(ResultCode.MFA_CHALLENGE_EXPIRED);
        }
        // 2. 绑定 deviceId 与请求比对（防跨设备复用）
        if (!binding.deviceId().equals(dto.getDeviceInfo().getDeviceId())) {
            throw new BizException(ResultCode.MFA_CHALLENGE_EXPIRED, "挑战凭证与当前设备不符");
        }
        // 3. 按 userId 查用户（防御分支，挑战签发时已验存在）
        UmsUser user = userService.getById(binding.userId());
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, INVALID_ACCOUNT_MESSAGE);
        }
        UmsUserAccountSecurity security = loadOrCreateSecurity(user.getId());
        checkLocked(security);      // 4. lockTime 防御复检（签发后 5min 内可被锁）
        checkUserStatus(user);      // 5. 状态复检（签发后 5min 内可变）
        if (!totpUtil.verify(security.getMfaSecret(), dto.getCode())) {   // 6. OTP 因子
            // 与密码共用防爆破：错 5 次锁 30min；挑战已消费，重试须重新登录
            increaseErrorCount(security);
            throw new BizException(ResultCode.UNAUTHORIZED, "验证码错误");
        }
        String refreshToken = generateRefreshToken();
        return transactionTemplate.execute(status -> {
            clearErrorCount(security);
            UmsUserLoginDevice session = deviceService.createSession(
                    user.getId(), dto.getDeviceInfo(), clientIp, refreshToken, REFRESH_TOKEN_TTL_DAYS);
            updateLastLogin(user, dto.getDeviceInfo(), clientIp);
            return issueTokens(user, session.getId(), session.getDeviceId(), refreshToken);
        });
    }

    // ==================== 刷新 ====================

    /**
     * 刷新 token（轮换防重放）
     */
    public TokenVO refresh(RefreshDTO dto) {
        UmsUserLoginDevice session = deviceService.findByRefreshTokenHash(
                com.sanye.strategy.common.util.HashUtil.sha256Hex(dto.getRefreshToken()));
        if (session == null || !dto.getDeviceId().equals(session.getDeviceId())) {
            throw new BizException(ResultCode.TOKEN_EXPIRED, "会话已失效，请重新登录");
        }
        if (session.getExpireTime() != null && LocalDateTime.now().isAfter(session.getExpireTime())) {
            throw new BizException(ResultCode.TOKEN_EXPIRED, "会话已过期，请重新登录");
        }
        if (jtiBlacklistService.isRevoked(session.getId())) {
            throw new BizException(ResultCode.TOKEN_EXPIRED, "会话已吊销，请重新登录");
        }
        UmsUser user = userService.getById(session.getUserId());
        if (user == null) {
            throw new BizException(ResultCode.TOKEN_EXPIRED);
        }
        checkUserStatus(user);
        String newRefreshToken = generateRefreshToken();
        deviceService.rotateRefreshToken(session.getId(), newRefreshToken, REFRESH_TOKEN_TTL_DAYS);
        // 新 accessToken 新 exp，清旧吊销记录
        jtiBlacklistService.remove(session.getId());
        String accessToken = jwtUtil.generateAccessToken(
                user.getId(), user.getUserType(), session.getId(), session.getDeviceId());
        return buildTokenVO(accessToken, newRefreshToken);
    }

    // ==================== 登出 ====================

    /**
     * 登出：失效当前会话行 + jti 写黑名单（accessToken 即时失效）
     */
    public void logout() {
        UserContext context = UserContext.get();
        if (context == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        deviceService.invalidateSession(context.getJti());
        jtiBlacklistService.revoke(context.getJti(), jwtUtil.getAccessTokenTtlSeconds());
    }

    // ==================== 私有 ====================

    private UmsUser findByAccount(String account) {
        DefaultQueryWrapper<UmsUser> wrapper = new DefaultQueryWrapper<>();
        if (account.contains("@")) {
            wrapper.eq("email", account);
        } else if (account.matches("^[0-9+]+$")) {
            wrapper.eq("phone", account).eq("phone_country_code", DEFAULT_PHONE_COUNTRY_CODE);
        } else {
            wrapper.eq("username", account);
        }
        return userService.getOne(wrapper);
    }

    private void checkUserStatus(UmsUser user) {
        if (user.getUserStatus() == UserStatusEnum.FROZEN) {
            throw new BizException(ResultCode.ACCOUNT_DISABLED, "账号已冻结");
        }
        if (user.getUserStatus() == UserStatusEnum.CANCELLED) {
            throw new BizException(ResultCode.ACCOUNT_DELETED, "账号已注销");
        }
    }

    private UmsUserAccountSecurity loadOrCreateSecurity(Long userId) {
        UmsUserAccountSecurity security = securityService.getOne(
                new DefaultQueryWrapper<UmsUserAccountSecurity>().eq("user_id", userId));
        if (security == null) {
            security = new UmsUserAccountSecurity();
            security.setUserId(userId);
            security.setPasswordErrorCount(0);
            security.setLockTime(null);
            security.setMfaStatus(YesNoEnum.NO);
        }
        return security;
    }

    private void checkLocked(UmsUserAccountSecurity security) {
        if (security.getLockTime() != null && LocalDateTime.now().isBefore(security.getLockTime())) {
            throw new BizException(ResultCode.ACCOUNT_LOCKED, "账号已锁定，请稍后再试");
        }
    }

    private void increaseErrorCount(UmsUserAccountSecurity security) {
        int count = (security.getPasswordErrorCount() == null ? 0 : security.getPasswordErrorCount()) + 1;
        security.setPasswordErrorCount(count);
        if (count >= PASSWORD_ERROR_THRESHOLD) {
            security.setLockTime(LocalDateTime.now().plusMinutes(LOCK_MINUTES));
        }
        if (security.getId() == null) {
            securityService.insert(security);
        } else {
            securityService.updateById(security);
        }
    }

    private void clearErrorCount(UmsUserAccountSecurity security) {
        security.setPasswordErrorCount(0);
        security.setLockTime(null);
        if (security.getId() != null) {
            securityService.updateById(security);
        }
    }

    private void updateLastLogin(UmsUser user, DeviceInfo info, String clientIp) {
        user.setLastLoginTime(LocalDateTime.now());
        user.setLastLoginIp(clientIp);
        user.setLastLoginDeviceId(info == null ? null : info.getDeviceId());
        userService.updateById(user);
    }

    private TokenVO issueTokens(UmsUser user, Long sessionId, String deviceId, String refreshToken) {
        String accessToken = jwtUtil.generateAccessToken(
                user.getId(), user.getUserType(), sessionId, deviceId);
        return buildTokenVO(accessToken, refreshToken);
    }

    private TokenVO buildTokenVO(String accessToken, String refreshToken) {
        TokenVO vo = new TokenVO();
        vo.setAccessToken(accessToken);
        vo.setRefreshToken(refreshToken);
        vo.setAccessExpiresIn((int) jwtUtil.getAccessTokenTtlSeconds());
        return vo;
    }

    private String generateRefreshToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private RegisterChannelEnum mapChannel(DeviceInfo info) {
        if (info == null || info.getDeviceType() == null) {
            return RegisterChannelEnum.UNKNOWN;
        }
        DeviceTypeEnum type = DeviceTypeEnum.valueOf(info.getDeviceType());
        if (type == null) {
            return RegisterChannelEnum.UNKNOWN;
        }
        return switch (type) {
            case PC -> RegisterChannelEnum.PC;
            case MINI_PROGRAM -> RegisterChannelEnum.MINI_PROGRAM;
            default -> RegisterChannelEnum.APP; // PHONE/PAD
        };
    }

    private void validatePasswordPolicy(String password) {
        if (password == null || password.length() < 8
                || !password.matches(".*[a-zA-Z].*") || !password.matches(".*[0-9].*")) {
            throw new BizException(ResultCode.BAD_REQUEST, "密码至少8位，且包含字母与数字");
        }
    }

    private String normalizeNullable(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
```

> 说明：`refresh()` 内 SHA-256 调用使用全限定名 `com.sanye.strategy.common.util.HashUtil.sha256Hex`，可改 import 引入，二选一即可。

- [ ] **Step 5: 跑测试验证通过**

Run: `mvn test -Dtest=AuthServiceTest`
Expected: 19 个测试全部 PASS（注册 3 + 登录 8 + MFA verify 4 + 刷新 3 + 登出 1）。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/sanye/strategy/auth/service/AuthService.java src/main/java/com/sanye/strategy/common/util/IpUtils.java src/test/java/com/sanye/strategy/auth/service/AuthServiceTest.java
git commit -m "feat(auth): 批1 AuthService — 注册/登录/刷新/登出/MFA挑战凭证验证门面（防爆破/状态校验/事务编排）+ 单测"
```

---

### Task 14: AuthController（端点收口）

**Files:**
- Create: `src/main/java/com/sanye/strategy/auth/controller/AuthController.java`

**Interfaces:**
- Consumes: `AuthService`（Task 13）、DTO（Task 12）、`IpUtils`（Task 13）
- Produces: HTTP 端点 `POST /auth/register|login|refresh|mfa/verify|logout`（白名单前 4 个 + logout 走拦截器鉴权）

- [ ] **Step 1: 实现 AuthController**

```java
package com.sanye.strategy.auth.controller;

import com.sanye.strategy.auth.dto.LoginDTO;
import com.sanye.strategy.auth.dto.MfaVerifyDTO;
import com.sanye.strategy.auth.dto.RefreshDTO;
import com.sanye.strategy.auth.dto.RegisterDTO;
import com.sanye.strategy.auth.dto.TokenVO;
import com.sanye.strategy.auth.service.AuthService;
import com.sanye.strategy.common.response.R;
import com.sanye.strategy.common.util.IpUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 认证端点 — 薄 Controller，只做参数接收/校验/VO 包装，业务全在 {@link AuthService}
 * </p>
 * <p>
 * 登录/注册/刷新/MFA 验证为白名单（WebMvcConfig），登出需 Bearer accessToken（拦截器鉴权）。
 * 登录遇 MFA_REQUIRED 返回 403 + 挑战凭证（MfaChallengeVO），客户端凭 tempToken 调 verify。
 * </p>
 *
 * @author 31372
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 注册
     */
    @PostMapping("/register")
    public R<TokenVO> register(@Valid @RequestBody RegisterDTO dto, HttpServletRequest request) {
        return R.ok(authService.register(dto, IpUtils.getClientIp(request)));
    }

    /**
     * 登录
     */
    @PostMapping("/login")
    public R<TokenVO> login(@Valid @RequestBody LoginDTO dto, HttpServletRequest request) {
        return R.ok(authService.login(dto, IpUtils.getClientIp(request)));
    }

    /**
     * MFA 二次验证（登录 403 挑战凭证 + OTP，白名单）
     */
    @PostMapping("/mfa/verify")
    public R<TokenVO> verifyMfa(@Valid @RequestBody MfaVerifyDTO dto, HttpServletRequest request) {
        return R.ok(authService.verifyMfa(dto, IpUtils.getClientIp(request)));
    }

    /**
     * 刷新双 Token（轮换）
     */
    @PostMapping("/refresh")
    public R<TokenVO> refresh(@Valid @RequestBody RefreshDTO dto) {
        return R.ok(authService.refresh(dto));
    }

    /**
     * 登出（需登录）
     */
    @PostMapping("/logout")
    public R<Void> logout() {
        authService.logout();
        return R.ok();
    }
}
```

- [ ] **Step 2: 全量编译 + 测试**

Run: `mvn test`
Expected: 全部现有测试 + 本批新增测试 PASS，BUILD SUCCESS。（现有 `DeleteFlagEnumTypeHandlerTest` 应保持绿。）

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sanye/strategy/auth/controller/AuthController.java
git commit -m "feat(auth): 批1 AuthController — 注册/登录/刷新/MFA验证/登出端点"
```

---

### Task 15: 端到端冒烟验证（手动，前置 Redis + 迁移）

**Files:** 无（验证性任务）

**Interfaces:**
- Consumes: Task 1-14 全部
- Produces: 认证闭环可用性证明

**前置条件：**
1. Redis 已启动（`application-dev.yaml` 配置的 host:port）。
2. Task 2 迁移脚本已在 `sys_strategy` 库执行。
3. 应用启动：`mvn spring-boot:run`（dev 配置）。

- [ ] **Step 1: 启动应用**

Run: `mvn spring-boot:run`
Expected: 应用启动无异常（日志无 SqlSessionFactory 相关报错）。

- [ ] **Step 2: 注册 → 得双 Token**

Run（新开终端，curl 或 Postman；`jq` 提取 token）：
```bash
curl -s -X POST http://localhost:8080/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"smoke01","password":"abc12345","deviceInfo":{"deviceType":3,"deviceId":"pc-1"}}'
```
Expected: `code=200`，`data.accessToken`（三段 JWT，payload 含 `type=ACCESS`）、`data.refreshToken`（64 位 hex）、`data.accessExpiresIn=1800`。

- [ ] **Step 3: 未带 token 访问登出 → 401**

```bash
curl -s -X POST http://localhost:8080/auth/logout
```
Expected: `code=401`。

- [ ] **Step 4: 登出（带 token）→ 200，再带同 token 登出 → 401**

```bash
# ACCESS=$(上一步 data.accessToken)
curl -s -X POST http://localhost:8080/auth/logout -H "Authorization: Bearer $ACCESS"
# 再执行一次 → 黑名单命中
curl -s -X POST http://localhost:8080/auth/logout -H "Authorization: Bearer $ACCESS"
```
Expected: 第一次 `code=200`；第二次 `code=401`（jti 黑名单秒级冻结生效，且 refresh 会话已失效）。

- [ ] **Step 5: 登录 → 刷新轮换**

```bash
# LOGIN=$(curl -s -X POST http://localhost:8080/auth/login -H 'Content-Type: application/json' \
#   -d '{"account":"smoke01","password":"abc12345","deviceInfo":{"deviceType":3,"deviceId":"pc-1"}}')
# RT=$(echo $LOGIN | jq -r .data.refreshToken)
curl -s -X POST http://localhost:8080/auth/refresh \
  -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$RT\",\"deviceId\":\"pc-1\"}"
```
Expected: `code=200`，返回新双 Token；旧 `$RT` 再刷新 → `code=401`（轮换防重放）。

- [ ] **Step 6: 防爆破锁 30min**

```bash
# 连续 5 次错密码
for i in $(seq 1 5); do curl -s -X POST http://localhost:8080/auth/login -H 'Content-Type: application/json' \
  -d '{"account":"smoke01","password":"wrong-pass","deviceInfo":{"deviceType":3,"deviceId":"pc-1"}}'; echo; done
# 第 6 次用正确密码 → 锁定期
curl -s -X POST http://localhost:8080/auth/login -H 'Content-Type: application/json' \
  -d '{"account":"smoke01","password":"abc12345","deviceInfo":{"deviceType":3,"deviceId":"pc-1"}}'
```
Expected: 前 5 次 `code=401`（第 5 次置 lockTime）；正确密码 → `code=403 ACCOUNT_LOCKED`。

- [ ] **Step 7: MFA 挑战凭证链路（SQL 手动开启）**

```bash
# SQL：UPDATE ums_user_account_security SET mfa_status=1, mfa_secret='GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ' WHERE user_id=<id>;
# 阶段 1：正确密码登录 → 403 MFA_REQUIRED + data 含挑战凭证
curl -s -X POST http://localhost:8080/auth/login -H 'Content-Type: application/json' \
  -d '{"account":"smoke01","password":"abc12345","deviceInfo":{"deviceType":3,"deviceId":"pc-1"}}'
# TEMP=$(上一步 data.tempToken)
# OTP=$(用 TOTP 客户端 / 脚本按 RFC secret 取当前 6 位码，同 TotpUtilTest 向量)
# 阶段 2：verify → 双 Token
curl -s -X POST http://localhost:8080/auth/mfa/verify -H 'Content-Type: application/json' \
  -d "{\"tempToken\":\"$TEMP\",\"code\":\"$OTP\",\"deviceInfo\":{\"deviceType\":3,\"deviceId\":\"pc-1\"}}"
# 重放：同一 $TEMP 再 verify → 401（GETDEL 单次消费）
curl -s -X POST http://localhost:8080/auth/mfa/verify -H 'Content-Type: application/json' \
  -d "{\"tempToken\":\"$TEMP\",\"code\":\"$OTP\",\"deviceInfo\":{\"deviceType\":3,\"deviceId\":\"pc-1\"}}"
```
Expected: 阶段 1 `code=403 MFA_REQUIRED`，`data.tempToken`（64 位 hex）+ `data.expiresIn=300`，无 access/refresh token；阶段 2 `code=200`，返回双 Token；重放 → `code=401 MFA_CHALLENGE_EXPIRED`（GETDEL 单次消费防重放生效）。OTP 错（`code=000000`）→ `code=401`（验证码错误，计数 +1）；错码后再重放同 `$TEMP` → 401 挑战已消费。

- [ ] **Step 8: 恢复账号 + 记录冒烟结论**

```bash
# SQL：UPDATE ums_user_account_security SET mfa_status=0 WHERE user_id=<id>;
# UPDATE ums_user SET user_status=1 WHERE username='smoke01';
```
Expected: 冒烟通过项：注册/登录/登出/黑名单秒级/刷新轮换/防爆破锁/账号不存在与密码错同提示。未验证项（留批2+）已在上文注明。

---

### Task 16: 文档收尾 — spec 纠偏 + 设计模式说明 + CLAUDE.md

**Files:**
- Modify: `docs/superpowers/specs/2026-08-07-user-center-design.md`
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/plans/2026-08-07-user-center-batch1-auth.md`（本文件，标记完成勾选）

**Interfaces:** 无（文档）

- [ ] **Step 1: spec 纠偏**

`2026-08-07-user-center-design.md`：
1. §4.3 白名单行改为含 `/auth/mfa/verify`：

```markdown
      /auth/login /auth/register /auth/refresh /auth/mfa/verify /actuator/** /error
```

2. §5.2.1 请求行（挑战凭证反转，本次已直接落地 spec，此处核对一致性）：

```markdown
POST /auth/mfa/verify {tempToken, code, deviceInfo}
```

（去 account/password；5.2 登录 mfa=1 分支签发 5min 挑战凭证随 403 MFA_REQUIRED 返回，verify GETDEL 单次消费 + 验 OTP）

3. §8.3 注释改为「新库建表见 `sql/user.sql`（已含该列 + `idx_refresh_token_hash` + `idx_user_current`）」并在 ALTER 语句保留（存量库用）。

- [ ] **Step 2: CLAUDE.md 更新**

1. 技术栈 Redis 行补充「依赖：spring-data-redis + Lettuce」已存在则跳过；Redis 用途句「仅作 accessToken jti 吊销黑名单」→「双用途：jti 吊销黑名单 + MFA 挑战凭证 tempToken（5min TTL、GETDEL 单次消费、瞬态，非会话/业务缓存）」；确认 `jwt.secret` 用法有一行说明。（**注：Redis 行本次设计变更已直接落地 CLAUDE.md，此处核对即可。**）
2. 包结构补 `auth/`、`device/` 两个能力包 + `common/auth`（JwtUtil/UserContext/PasswordEncoder/TotpUtil/JtiBlacklistService/ChallengeTokenService，注明 ChallengeTokenService 键域 `mfa:*`、GETDEL 原子单次消费）+ `common/interceptor`（TokenAuthInterceptor，标注「拦截器（待实现）」→ 已实现）。
3. 认证管道说明：TokenAuthInterceptor/WebMvcConfig 描述 + 白名单 `/auth/login, /auth/register, /auth/refresh, /auth/mfa/verify, /actuator/**, /error`。
4. MFA 二次验证说明（5.2.1 反转后）：verifyMfa 不再验密码（密码因子已在登录步骤 5 校验，tempToken 即通过证明），verify 仅验 OTP；GETDEL 原子单次消费防重放，OTP 错 = 挑战已消费（重试须重新登录）；登录 mfa=1 分支签发 5min 挑战凭证（`SETEX mfa:{tempToken} {userId}:{deviceId} 300`）随 403 MFA_REQUIRED 携带 MfaChallengeVO 返回，DB 零写入。
5. `ResultCode` 章节补列批1 新增 7 码及 HTTP 归属：`TOKEN_EXPIRED(401)`、`DEVICE_KICKED(401)`、`MFA_CHALLENGE_EXPIRED(401)`、`ACCOUNT_LOCKED(403)`、`ACCOUNT_DISABLED(403)`、`MFA_REQUIRED(403)`、`ACCOUNT_DELETED(410)`。
6. `R<T>` 章节补 `fail(ResultCode, String, T data)` 数据重载；`BizException` 补可选 `Object payload`（MFA_REQUIRED 走此通道携带 MfaChallengeVO；payload 为 null 行为与现状一致）。
7. DTO/VO 定义补：`MfaVerifyDTO`（`{tempToken, code, deviceInfo}`，无 account/password，userId 由挑战绑定解出）+ `MfaChallengeVO`（`{tempToken, expiresIn}`，仅随 403 MFA_REQUIRED 返回一次）。
8. 已知缺陷与待办表：`createUserId/updateUserId 无用户上下文` 🟠 → ✅ 已修复（批1 UserContext + MetaObjectHandler）；新增行「认证主链批1 已落地（注册/登录/刷新/登出/MFA 挑战凭证验证 + jti 黑名单）」，批2-6 状态不变。

- [ ] **Step 3: 勾选本文件全部任务复选框**

- [ ] **Step 4: 全量回归**

Run: `mvn test`
Expected: BUILD SUCCESS，全部测试绿。

- [ ] **Step 5: 最终提交**

```bash
git add docs/superpowers/specs/2026-08-07-user-center-design.md CLAUDE.md docs/superpowers/plans/2026-08-07-user-center-batch1-auth.md
git commit -m "docs(auth): 批1 收尾 — spec 白名单/MFA 挑战凭证反转 + CLAUDE.md 包结构/Redis 双用途/待办更新 + plan 标记完成"
```

---

## 验证清单（spec → task 覆盖）

| spec 章节/需求 | 覆盖任务 |
|---|---|
| 依赖 jjwt/jbcrypt/spring-data-redis | Task 1 |
| DDL 8.1 列宽放宽 / 8.2 空值 NULL 化 / 8.3 refresh_token_hash+索引 | Task 2 |
| ResultCode 401/403/410 系（7 码，含 MFA_CHALLENGE_EXPIRED）+ 错误数据通道（R.fail data / BizException payload） | Task 3 |
| 密码 BCrypt（PasswordEncoder） | Task 4 |
| UserContext ThreadLocal + 清除 | Task 5 |
| JWT HS256 + type/userId/userType/jti/deviceId/exp + kid 预留 | Task 6 |
| TOTP RFC 6238 + 自实现 Base32 | Task 7 |
| jti 吊销黑名单 + MFA 挑战凭证（Redis SETEX/EXISTS/DEL + GETDEL 原子消费，双用途） | Task 8 |
| MetaObjectHandler 审计人填充（缺陷 5） | Task 9 |
| 会话行属主 DeviceService（createSession/轮换/失效） | Task 10 |
| 认证管道 TokenAuthInterceptor + 白名单 + UserContext 填充 | Task 11 |
| DTO 校验（jakarta） | Task 12 |
| 注册/登录/刷新/登出/MFA 挑战凭证验证门面 + 防爆破 + 事务 | Task 13 |
| 认证端点 | Task 14 |
| 认证闭环冒烟 | Task 15 |
| 文档纠偏 + CLAUDE.md | Task 16 |
| 注销后缀释放 / 冻结注销会话吊销（批5）、改密吊销（批3） | 后续批次（DeviceService.invalidateAllByUser 已备） |

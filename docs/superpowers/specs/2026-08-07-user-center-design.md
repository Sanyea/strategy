# 用户中心设计文档

> 日期：2026-08-07
> 分支：AI
> 范围：用户中心架构设计（认证/个人资料/账号安全/第三方绑定/设备管理/后台用户管理）
> 状态：设计草案已确认，实现分批

---

## 1. 背景与现状

现有基础：

- 5 张表已完成建表：`ums_user`（用户主表）、`ums_user_profile`（扩展信息）、`ums_user_auth`（第三方绑定）、`ums_user_account_security`（账号安全）、`ums_user_login_device`（登录设备）
- 实体 / PO / Mapper / Service 脚手架全部就绪：`domain`、`po`、`mapper`、`service` + `impl` 各 5 组，继承自定义 DIP 抽象（`IService` → `MpBaseServiceImpl`）
- common 层 DIP 抽象（IWrapper / IBasePage / BaseController / R / 异常体系）已完成并经代码审查修复
- **缺失**：Controller 层、认证体系、业务逻辑（注册/登录/资料/安全/绑定/设备）、审计人字段填充（无用户上下文）

已知关联缺陷（`docs/code-review/2026-08-04-common-layer-dip-review.md`）：

- 缺陷 5：`createUserId`/`updateUserId` 恒 NULL（无 MetaObjectHandler 无用户上下文）
- 缺陷 10：逻辑删除 + 唯一键 → 删号后用户名/手机/邮箱永久占用
- 缺陷 11：空手机号/邮箱撞唯一键
- 缺陷 12：`ext_info` JSON 列无类型处理器（Jackson 3 命名空间问题）
- 缺陷 14：`R.fail(404)` 但 HTTP 200（设计内由异常体系修复，现 BaseController 已改为抛 `BizException`）

## 2. 目标与非目标

### 目标

1. 完整认证闭环：注册 / 登录 / 刷新 / 登出，双 Token（JWT access + 会话 refresh）
2. 用户自服务：个人资料查询与修改、改密、绑定/解绑手机邮箱、MFA、第三方账号绑定、登录设备管理
3. 后台用户管理：分页查询、状态管理（冻结/解冻/注销）、重置密码
4. 审计人字段真实填充（UserContext → MetaObjectHandler）
5. 全程沿用 DIP 分层与统一异常/响应体系，零 Spring Security 依赖

### 非目标（本期不做）

- 支付密码（`pay_password`）功能化：字段预留，业务后续
- 登录频率限制（防爆破走 `passwordErrorCount` + `lockTime`）
- RBAC 权限模型（后台仅按 `userType` 分级，见 5.7）
- 微服务拆分；Redis 仅承载两类瞬态认证数据：accessToken jti 吊销黑名单（秒级冻结）+ MFA 挑战凭证 tempToken（5min TTL、GETDEL 单次消费），不存会话/业务缓存（refresh 会话仍落库；tempToken 属瞬态挑战凭证，非会话缓存）
- 短信/邮箱验证码下发渠道接入（接口预留，通道后续）

## 3. 总体架构

### 3.1 分层

```
Controller（薄，只做参数接收/校验/VO 转换）
    ↓
门面聚合 Service（能力包内，编排多个 base service，承载跨表事务与业务规则）
    ↓
base service（现有 5 个 UmsXxxService，纯数据访问层，继承 IService<Domain>）
    ↓
MpBaseServiceImpl（MP 桥接：IWrapper→QueryWrapper，实体↔PO）
    ↓
BaseMapper<PO>
```

- 现有 5 个 base service 降为数据访问层，不承载业务规则
- 门面 service 注入共享 `TransactionTemplate`（`common/config/TransactionConfig`）编排跨表写——门面非 `AbstractBaseService` 子类，够不到其 `doInTransaction` 钩子，跨表事务经注入的框架 Bean 单一收口
- 会话行（`ums_user_login_device`）读写**收口 `DeviceService`**，认证与设备管理共用，单一表属主

### 3.2 模块包结构

```
com.sanye.strategy
├── auth/                      # 能力包：认证
│   ├── controller/AuthController
│   ├── service/AuthService            （门面）
│   └── dto/    RegisterDTO / LoginDTO / RefreshDTO / MfaVerifyDTO / TokenVO / MfaChallengeVO
├── profile/                   # 能力包：个人资料
│   ├── controller/ProfileController
│   ├── service/ProfileService          （门面: user + profile）
│   └── dto/    ProfileUpdateDTO / UserInfoVO / ProfileUpdateVO
├── security/                  # 能力包：账号安全
│   ├── controller/SecurityController
│   ├── service/SecurityService          （门面: user + security）
│   └── dto/    ChangePasswordDTO / BindPhoneDTO / BindEmailDTO / SetupMfaDTO
├── bind/                      # 能力包：第三方绑定
│   ├── controller/BindController
│   ├── service/BindService              （门面: user + auth）
│   └── dto/    BindAuthDTO / AuthBindingVO
├── device/                    # 能力包：设备管理
│   ├── controller/DeviceController
│   ├── service/DeviceService            （门面: user + login_device，会话行属主）
│   └── dto/    DeviceVO / KickDeviceDTO
├── admin/                     # 能力包：后台用户管理
│   ├── controller/AdminUserController
│   ├── service/AdminUserService          （门面: user + security + profile）
│   └── dto/    AdminUserQueryDTO / AdminUserVO / AdminUserStatusDTO
├── common/
│   ├── auth/                  JwtUtil / UserContext / PasswordEncoder(BCrypt 封装) / TotpUtil / ChallengeTokenService
│   ├── interceptor/           TokenAuthInterceptor
│   └── config/                WebMvcConfig（注册拦截器）/ TransactionConfig（共享 TransactionTemplate Bean）
```

## 4. 认证设计

### 4.1 双 Token

| Token | 形态 | claims/内容 | TTL | 存储 |
|-------|------|-------------|-----|------|
| accessToken | JWT（HS256） | `type: ACCESS, userId, userType, jti, deviceId, exp` | 30 min | 客户端内存/Header |
| refreshToken | 不透明随机串（`SecureRandom` 32B） | 无 | 14 天 | `ums_user_login_device.refresh_token_hash` |

- `jti` = 会话行 id。吊销会话 = 失效对应会话行
- accessToken 无状态校验（JWT 验签），请求不打库；refreshToken 服务端按 `refresh_token_hash` 查会话行，校验是否有效（`is_current=1`、`expire_time` 未过、非逻辑删除）
- 区分两种 Token：accessToken 为 JWT 三段（`.`,分隔，`eyJ...`），且验签后强制校验 `type=ACCESS`；refreshToken 为 32B 不透明串，无结构，二者格式天然互斥、互不冒充
- refreshToken 只存 SHA-256 哈希（Hex）于 `refresh_token_hash`，不存明文
- 每个登录会话 = 一行 `ums_user_login_device`；同账号多设备 = 多会话行
- **jti 吊销黑名单（秒级冻结）**：Redis `SETEX jti:{jti} 1 {exp}`（TTL = 剩余 exp）。吊销操作（登出/踢设备/改密/冻结/注销）写黑名单，拦截器验签后 `EXISTS jti:{jti}` 命中即拒——accessToken 即时失效，不等 TTL 过期。记录随 TTL 自动过期，无手工清理
- **MFA 挑战凭证（tempToken）**：登录 MFA 分支签发 32B 随机 tempToken，`SETEX mfa:{tempToken} {userId}:{deviceId} 300`（5min TTL，绑定账号 + 设备）；verify 时 `GETDEL mfa:{tempToken}` 原子单次消费（防重放），命中即删、不存在/已消费/过期返回空。记录随 5min TTL 自动过期，无手工清理。存于 `ChallengeTokenService`（`common/auth`，键域 `mfa:*`），与 `JtiBlacklistService`（键域 `jti:*`）并列，均用自动装配 `StringRedisTemplate`（Lettuce）。**Redis 仅承载这两类瞬态认证数据**（见 2 非目标）

### 4.2 JWT 与密钥

- 依赖：`io.jsonwebtoken:jjwt-api` / `jjwt-impl` / `jjwt-jackson`（0.12.x）
- HS256 对称签名，密钥 ≥ 32 字节，从配置注入（`jwt.secret`），环境变量/密管覆盖，不进代码不入库
- 算法/密钥演进：`JwtUtil` 内做 signKey 前缀版本（`kid` claim），轮换时新旧密钥并存验证

### 4.3 认证管道（TokenAuthInterceptor）

```
请求
  ↓
WebMvcConfigurer 注册 TokenAuthInterceptor (HandlerInterceptor)
  ↓
preHandle:
  白名单路径 → 放行
      /auth/login /auth/register /auth/refresh /auth/mfa/verify /actuator/** /error
  解析 Authorization: Bearer <accessToken>
    ├─ 缺失/格式错/验签失败/过期 → 抛 401（BizException + ResultCode.UNAUTHORIZED）
    ├─ 验签后校验 type claim：≠ ACCESS → 抛 401（拒绝 refreshToken/异类 Token 冒充）
    ├─ 校验 jti 黑名单：Redis EXISTS jti:{jti} → 命中抛 401（TOKEN_EXPIRED / DEVICE_KICKED）
    └─ 成功 → 取 claims(userId/userType/jti/deviceId) 填充 UserContext
        （不逐请求查 userStatus——冻结/注销在签发时校验，即时吊销走黑名单）
  填充 UserContext（ThreadLocal: userId/userType/jti/deviceId）
  ↓
Controller/Service 业务层从 UserContext 取当前用户
  ↓
afterCompletion:
  清除 ThreadLocal（防线程池复用泄漏）
```

- 白名单外默认要求登录；白名单由 `WebMvcConfig` 集中维护
- `GlobalExceptionHandler` 已按 `ResultCode.code` 映射 HTTP 状态（`resolveHttpStatus`），401 已覆盖，无需补分支
- `ResultCode` 新增：`TOKEN_EXPIRED(401)`、`ACCOUNT_LOCKED(403)`、`ACCOUNT_DISABLED(403)`、`ACCOUNT_DELETED(410)`、`DEVICE_KICKED(401)`、`MFA_REQUIRED(403)`、`MFA_CHALLENGE_EXPIRED(401)`（`UNAUTHORIZED(401)` 已存在，不新增）
- 可达性：拦截器经 jti 黑名单抛 `TOKEN_EXPIRED`/`DEVICE_KICKED`（秒级吊销）；`ACCOUNT_LOCKED`（登录步骤 4 校验 lockTime）、`ACCOUNT_DISABLED`/`ACCOUNT_DELETED`（登录/刷新/verify 校验 user_status）、`MFA_REQUIRED`（登录步骤 5 mfa_status=1，携带挑战凭证）、`MFA_CHALLENGE_EXPIRED`（verify 步骤 GETDEL 消费失败）在签发前抛——拦截器零 DB 查询，无状态成立

### 4.4 UserContext

- `ThreadLocal<UserContext>`，静态方法 `set/get/clear`
- 内容：`userId`、`userType`、`jti`、`deviceId`
- 业务层经 `UserContext.get().getUserId()` 取操作人
- **审计联动**：`MybatisPlusConfig.MetaObjectHandler` 改造，插入/更新时从 `UserContext` 填 `createUserId`/`updateUserId`；无上下文（如后台批量脚本）填 NULL 兜底

### 4.5 密码与 TOTP

- 密码哈希：BCrypt，库选 `org.mindrot:jbcrypt`（纯库，零 Spring 依赖，契合 DIP 零框架风格）。封装 `PasswordEncoder`：
  - `String encode(String raw)`
  - `boolean matches(String raw, String encoded)`
- 注册/改密/重置统一走 `PasswordEncoder`；`salt` 字段废弃不再写入（BCrypt 自带盐，字段留作旧数据兼容）
- MFA：TOTP（RFC 6238），JDK 内置 `HmacSHA1`；JDK 无内置 Base32——自实现 RFC 4648 Base32 编解码（`TotpUtil` 内），不引第三方库。`mfa_secret` 存 Base32 密钥，校验 30s 时间窗口 ±1
- **双因子分两道校验点**：密码因子在登录步骤 5 校验（tempToken 即其通过证明）；OTP 因子在 verify 步骤校验。防爆破计数 `passwordErrorCount` 两道共用（密码错 +1 / OTP 错 +1），清零只发生在 verify OTP 通过后的成功事务

## 5. 业务流程与时序

### 5.1 注册

```
POST /auth/register {username, password, phone?, email?, nickname?, userType=普通}
  ↓ AuthService.register
  1. 校验用户名格式/唯一（base UmsUserService.count）
  2. 密码策略校验 + PasswordEncoder.encode
  3. UmsUserService.insert（新建用户，registerChannel/registerClientIp/registerDeviceId 落库）
  4. 初始化空行：UmsUserAccountSecurityService.insert、UmsUserProfileService.insert
  5. DeviceService.createSession(userId, deviceInfo)  → 会话行
  6. 签发 access + refresh → TokenVO
  事务：3-5 经共享 TransactionTemplate 同事务
```

### 5.2 登录

```
POST /auth/login {account, password, deviceInfo}
  ↓ AuthService.login
  1. account 判型：手机/邮箱/用户名 → UmsUserService.getOne（唯一标识）
  2. 用户不存在 或 密码错误 → 同一提示「账号或密码错误」（防枚举）；不区分提示
  3. userStatus 校验：冻结/注销 → 403/410（ACCOUNT_DISABLED / ACCOUNT_DELETED），不参与密码计数（注销 = user_status=CANCELLED，见 5.7/8.1）
  4. lockTime 校验：`lockTime != null && now < lockTime`（锁 30min）→ 抛 403（ACCOUNT_LOCKED），不查密码；超时自动解锁，计数保留待下次错误重累计
  5. 密码校验：
     - 错 → security.passwordErrorCount +1；达阈值(如 5) 置 lockTime = now+30min；返「账号或密码错误」（本轮统一提示，下轮进步骤 4）
     - 对 → 查 mfa_status：
            ├─ 0 未开启 → 清 passwordErrorCount=0 + lockTime=NULL；继续步骤 6-8 发凭证（现状不变）
            └─ 1 已开启 → 生成 32B 随机 tempToken，Redis `SETEX mfa:{tempToken} {userId}:{deviceId} 300`（ChallengeTokenService.issue）→ 抛 403（MFA_REQUIRED，`BizException` 携带 `MfaChallengeVO{tempToken, expiresIn=300}`）；**不清计数**（密码因子已对，OTP 未验，清零推迟到 5.2.1 步骤 5）
  6. DeviceService.createSession → 会话行（置旧 is_current 会话保留，新会话为当前）
  7. 更新 user.lastLoginTime / lastLoginIp / lastLoginDeviceId
  8. 签发双 Token
  事务：5(成功清零)-7 经共享 TransactionTemplate 同事务（清计数/会话行/lastLogin 一致落库，防半写）。**mfa=1 分支 DB 零写入**（不清计数/不建会话/不签 access/refresh Token），tempToken 写 Redis 不属 DB 事务，由 Redis `SETEX` 自身原子性保证
```

### 5.2.1 MFA 二次验证（OTP，挑战凭证）

```
POST /auth/mfa/verify {tempToken, code, deviceInfo}
  ↓ AuthService.verifyMfa
  1. `GETDEL mfa:{tempToken}`（ChallengeTokenService.consume）原子单次消费：
     - 不存在 / 已消费 / 过期 → 401（MFA_CHALLENGE_EXPIRED「二次验证凭证已失效，请重新登录」）
     - 解析绑定 {userId, deviceId}；与请求 deviceInfo.deviceId 不一致 → 401（防跨设备复用）
  2. 按 userId 查用户：不存在 → 401（防御分支，理论上挑战签发时已验用户存在）
  3. lockTime 校验：锁定中 → 403（ACCOUNT_LOCKED），不验 OTP
  4. userStatus 校验：冻结/注销 → 403/410（签发后 5min 内状态可变，复检防御）
  5. TotpUtil.verify(mfa_secret, code)（30s 时间窗口 ±1）：
     - 错 → passwordErrorCount +1（与密码共用防爆破，错 5 次锁 30min）；提示「验证码错误」；挑战已消费，重试须重新登录
     - 对 → 清 passwordErrorCount=0 + lockTime=NULL
  6. DeviceService.createSession(userId, deviceInfo) → 会话行
  7. 更新 user.lastLoginTime / lastLoginIp / lastLoginDeviceId
  8. 签发双 Token
  事务：5(成功清零)-7 经共享 TransactionTemplate 同事务（DB 写）；GETDEL（步骤 1，Redis）在事务外，由 GETDEL 原子性保证单次消费
  安全：密码因子已在登录步骤 5 校验，tempToken 即其通过证明；verify 仅剩 OTP 因子；
        tempToken 5min 短 TTL + GETDEL 原子单次消费 + 绑定 userId/deviceId → 防重放、防跨设备复用；
        OTP 错 = 挑战已消费，无 90s OTP 有效窗重放面（先消费后验码）
```

### 5.3 刷新

```
POST /auth/refresh {refreshToken, deviceId}
  ↓ AuthService.refresh
  1. 按 refresh_token_hash 查会话行：不存在/已失效/deviceId 不符 → 401（TOKEN_EXPIRED）
  2. 校验 userStatus ∈ {冻结, 注销} → 抛 403/410（签发前把关，防冻结用户续 token）
  3. 校验 jti 黑名单：EXISTS → 401（会话已吊销，不续发）
  4. 轮换（经 DeviceService）：作废旧 refreshToken，新 refreshToken 的 SHA-256 哈希写回会话行（复用或更新行）
  5. 签发新 accessToken（type=ACCESS）；DEL jti 黑名单（新 token 新 exp，清旧吊销记录）
  防重放：旧 refresh 失效即不可再用
```

### 5.4 登出 / 改密吊销

```
POST /auth/logout → DeviceService.invalidateSession(jti)
  1. 置 is_current=0 / 标记失效（失效 refresh）
  2. Redis SETEX jti:{jti}（即时杀 access）
POST /users/me/password → SecurityService.changePassword
  1. 校验旧密码
  2. 新密码策略 + PasswordEncoder.encode
  3. 更新 user.password
  4. 吊销该账号全部会话行（经 DeviceService 查/失效全部，踢所有设备，含当前）+ 全部会话 jti 写黑名单
  说明：改密需重新登录；设备列表清空由 login_device 失效体现
```

### 5.5 个人资料

```
GET  /users/me        → ProfileService.getMyInfo：UserContext.userId → user + profile → UserInfoVO（脱敏）
PUT  /users/me        → ProfileService.updateMyInfo：更新 user 基础字段（nickname/avatar/gender/birthday/remark）
GET  /users/me/profile → 扩展信息（profile 行）
PUT  /users/me/profile → 扩展信息更新（address/occupation/education/signature/ext_info）
字段权限：手机/邮箱/身份证不能经此接口改（走安全接口）
```

### 5.6 设备管理

```
GET  /users/me/devices            → DeviceService.listMyDevices：查 user 全部会话行 → DeviceVO
DELETE /users/me/devices/{id}      → DeviceService.kickDevice：仅允许踢本 user 非当前设备（自己当前设备走登出）
  → invalidateSession + Redis SETEX jti:{jti}（被踢设备下一请求 401 → DEVICE_KICKED）
```

### 5.7 后台用户管理

```
GET  /admin/users/page            → AdminUserService.page：条件分页（username/phone/userStatus/userType/时间范围）
PUT  /admin/users/{id}/status      → AdminUserService.changeStatus：冻结/解冻（切 user_status）/ 注销（user_status=CANCELLED 终态 + 唯一标识后缀释放，不可逆）；冻结/注销 → 经 DeviceService 查该 user 全部会话、jti 写黑名单，秒级封禁
POST /admin/users/{id}/reset-password → AdminUserService.resetPassword：生成随机密码 + PasswordEncoder + 吊销全部会话（含 jti 黑名单）
权限：AdminUserController 入口校验 UserContext.userType ∈ {运营, 超级管理员}（轻量 `UserContext.requireUserType(...)`，不引入 RBAC）
```

## 6. DTO / VO 与脱敏

- DTO 校验：`jakarta.validation`（`@NotBlank`/`@Pattern`/`@Size`），Controller `@Valid`
- VO 字段白名单，敏感字段永不映射：
  - `password` / `salt` / `payPassword` / `paySalt` / `mfaSecret` / `credential`（第三方凭证）一律不进 VO
  - `idCardNo` 加密落库，VO 仅返脱敏串（`110***********1234`）
  - **例外：tempToken（`MfaChallengeVO`）**——5min 短时效、GETDEL 单次消费、绑定账号/设备的瞬态挑战凭证，刻意随 403 MFA_REQUIRED 一次性返回；不属「永久凭证白名单」范畴，`mfaSecret` 仍禁进 VO
- `MfaChallengeVO`（`auth/dto`）= `{tempToken, expiresIn}`，仅 403 MFA_REQUIRED 响应体携带
- `UserInfoVO` 组合 user + profile 必要字段，禁止直接序列化 domain 实体

## 7. 错误处理与安全边界

- 认证失败统一 `BizException` + `ResultCode`，`GlobalExceptionHandler` 映射 HTTP 状态（401/403/410）
- 登录不区分「用户不存在/密码错误」，防账号枚举；注册接口暴露用户名/手机/邮箱占用（可接受，注册场景天然可探测，仅限注册）
- 密码策略：≥8 位，含字母与数字，注册/改密/重置统一校验
- 防爆破：`passwordErrorCount`（连续错误累加）+ `lockTime`（阈值锁定期）
- refresh 轮换防重放：旧 refresh 一次性作废
- JWT 密钥不进代码；`ext_info` 等扩展字段禁止存敏感数据（注释约束）
- 全局异常日志不打印 token/密码/身份证明文；`R` 响应不回落完整实体
- **错误带数据通道**：`BizException` 增 `Object payload`（可选）+ `R.fail(ResultCode, String, T data)` 重载 + `GlobalExceptionHandler` payload 非空时透传进 `data`。单 throw 路径不变，Controller 零改动；`payload` 为 null 时行为与现状完全一致。MFA_REQUIRED 走此通道携带 `MfaChallengeVO`；tempToken 为瞬态凭证，仅 403 响应返回一次，日志禁打明文
- 吊销即时生效：登出/踢设备/改密/冻结/注销写 Redis jti 黑名单，accessToken 秒级失效（不等 TTL 过期）
- 敏感操作（改密/绑定手机/解绑/MFA）需验旧凭证或验证码（本期接口预留校验点，验证码通道后续接入）

## 8. 数据库调整（DDL）

### 8.1 删号后唯一标识复用（缺陷 10）

**决策：允许复用。** 注销行（`user_status=CANCELLED`）仍占 DB 唯一键，注销时对唯一标识追加后缀释放。

后缀 `#del#{id}` 最长 24 字符（`#del#` 5 + 雪花 ID 19），列宽预留后缀余量，`LEFT(原值, 列宽-24)` 截断防溢出：

```sql
-- 后缀格式：{原值}#del#{id}；列宽 = 原业务上限 + 24 后缀余量
ALTER TABLE ums_user
  MODIFY username VARCHAR(120) NOT NULL,          -- 业务上限 50 + 后缀余量，预留未来
  MODIFY phone    VARCHAR(48)  DEFAULT NULL,      -- 业务上限 20 + 后缀余量
  MODIFY email    VARCHAR(180) DEFAULT NULL;      -- 业务上限 100 + 后缀余量

-- 注销释放唯一键（与注销状态更新同事务）
UPDATE ums_user SET
  user_status = 0,                                -- CANCELLED 单一注销语义（终态，不可逆）
  username = CONCAT(LEFT(username, 96), '#del#', id),
  phone    = CONCAT(LEFT(phone, 24),    '#del#', id),   -- phone 为 NULL 时 CONCAT 结果 NULL，天然不占键
  email    = CONCAT(LEFT(email, 156),   '#del#', id)
WHERE id = ? AND deleted = 0;
```

- **注销语义（单一）**：`user_status=CANCELLED`（终态，不可逆）+ 唯一标识后缀化，二者同事务；`deleted` 保持表逻辑删除位独立（注销时不动，仍=0）
- CANCELLED 行仍可查（`@TableLogic` 不过滤）——`ACCOUNT_DELETED(410)` 可达、后台按 `user_status=CANCELLED` 筛选可见
- `deleted=1` 仅用于彻底清理/归档（行从业务查询消失）
- 重新注册同名/同手机/同邮箱：唯一键已被后缀释放，可直接复用
- 残留风险：后缀 `{name}#del#{id}` 可被同名新注册撞上（需 `#` 合法 + 知 19 位雪花 id，低概率）；命中唯一键 → 409 CONFLICT，提示重试即可

### 8.2 空手机/邮箱撞唯一键（缺陷 11）

**决策：空值 NULL 化。** 唯一键允许 NULL（MySQL 唯一键对 NULL 不去重）：

```sql
-- 列宽已在 8.1 放宽；此处仅改默认值 '' → NULL
ALTER TABLE ums_user
  MODIFY phone    VARCHAR(48) DEFAULT NULL,
  MODIFY email    VARCHAR(180) DEFAULT NULL,
  MODIFY phone_country_code VARCHAR(12) DEFAULT '+86';

-- 存量回填：'' → NULL（否则旧行仍占 uk_phone('','+86') / uk_email('') 唯一键，缺陷 11 未根治）
UPDATE ums_user SET phone = NULL WHERE phone = '';
UPDATE ums_user SET email = NULL WHERE email = '';
```

- `uk_phone(phone, phone_country_code)`、`uk_email(email)` 保留
- 业务层统一：空串入参归一化为 NULL 再落库；按手机/邮箱登录前先判空

### 8.3 会话表补充 refresh_token_hash + 索引（缺陷 4）

`ums_user_login_device` 加列存 refreshToken 哈希（SHA-256 Hex，非明文），刷新流程按此列查会话行（缺陷 4/7）；并补设备列表联合索引：

```sql
ALTER TABLE ums_user_login_device
  ADD COLUMN refresh_token_hash CHAR(64) DEFAULT NULL COMMENT 'refreshToken SHA-256 哈希（Hex，非明文）',
  ADD KEY idx_user_current (user_id, is_current),          -- 登出/吊销按 jti(主键) 查已覆盖；此索引便于设备列表
  ADD KEY idx_refresh_token_hash (refresh_token_hash);     -- 刷新按 hash 查会话行
```

> 注：新库建表见 `sql/user.sql`（`ums_user_login_device` 已含该列 + 索引）；此为存量库升级用一次性 DDL。MySQL 8.0 不支持 `ADD COLUMN IF NOT EXISTS`，重复执行需人工判断。

> **MFA 挑战凭证无 DDL**：tempToken 驻留 Redis（5min TTL 自动过期），不新增 DB 列、无迁移脚本（不同于 `refresh_token_hash` 需列）。实现勿误建列。

## 9. 开放缺陷联动

| 缺陷 | 设计内处理 | 落地批次 |
|------|-----------|---------|
| 5 审计人字段空 | UserContext + MetaObjectHandler 改造 | 批1 |
| 10 唯一标识复用 | user_status=CANCELLED + 后缀释放 + 列宽放宽 | 批5（注销功能）/批1（列宽 DDL） |
| 11 空手机/邮箱撞唯一键 | 空值 NULL 化 + 归一化 | 批1（DDL） |
| 12 `ext_info` JSON | 自定义 TypeHandler（Jackson 3）或应用层 JSON 工具 | 批6 |
| 会话过期残留 | refresh 过期自动失效；定期清理 `login_device`（`expire_time < now` 且 `is_current=0`，经 DeviceService 清理） | 批4 |
| access 吊销（jti 黑名单） | Redis `SETEX` 随 TTL 自动过期，无清理任务 | 批1 |

## 10. 设计模式说明

### 10.1 门面模式（Facade）— 能力包聚合服务

**角色说明：**

| 角色 | 类 | 职责 |
|------|-----|------|
| 门面 | `AuthService` / `ProfileService` / `SecurityService` / `BindService` / `DeviceService` / `AdminUserService` | 对外暴露粗粒度业务方法，编排多个 base service，承载跨表事务与规则 |
| 子系统 | 现有 5 个 `UmsXxxService`（base service） | 单表数据访问，纯 CRUD |
| 客户 | 各能力包 Controller | 只依赖门面，不感知内部编排 |

**优点：** 客户端与子系统解耦，跨表业务收口一处，事务边界清晰，Controller 保持瘦；能力包可独立测试（门面单测替换 base service 为桩）。
**缺点：** 多一层抽象；门面可能退化为"上帝对象"——每个门面职责限定单一能力包控制（不做大杂烩），门面间可互相依赖（如 AuthService 依赖 DeviceService）。

```
Controller → AuthService(Facade) → UmsUserService ┐
                                 → UmsUserAccountSecurityService ┤ (Subsystems)
                                 → UmsUserProfileService        ┤
                                 → DeviceService                ┘
```

### 10.2 责任链模式（Chain of Responsibility）— 认证管道

**角色说明：**

| 角色 | 类 | 职责 |
|------|-----|------|
| Handler | `HandlerInterceptor`（TokenAuthInterceptor） | 白名单放行 → Token 解析 → 状态校验 → UserContext 填充，链上一环 |
| Client | Spring MVC 请求分派 | 经 `WebMvcConfig` 注册，请求依次过拦截器 |
| 处理器出口 | `GlobalExceptionHandler` | 链上抛错统一转 `R<T>` + HTTP 状态码 |

**优点：** 认证与业务解耦，白名单/鉴权逻辑集中，易扩展（后续加日志/限流拦截器即挂新链节）；契合 Spring MVC 既有机制，零框架侵入。
**缺点：** 拦截器对控制器参数/返回值不可见（如需注入处理，改走 `HandlerMethodArgumentResolver` 补 `@LoginUser` 参数注入，本期不做）；过滤链内耗时不可忽略（JWT 验签）。

```
Request → [Whitelist] → [JWT parse] → [status check] → [UserContext] → Controller
```

### 10.3 模板方法模式（复用现有服务层）

**角色说明：**

| 角色 | 类 | 职责 |
|------|-----|------|
| 抽象模板 | `AbstractBaseService<T>` | 定义流程骨架：insert/update/delete/批量/saveOrUpdate + before/after 钩子 + `doInTransaction` 事务钩子；子类只实现 doXxx |
| 具体实现 | `MpBaseServiceImpl<P,M,T>` → `XxxServiceImpl` | 实现 doXxx（MP 桥接）+ 可选覆写钩子 |
| 事务钩子 | `doInTransaction(Supplier<R>)` | 批量入口统一经此钩子：默认透传无事务，桥接层覆写为共享 TransactionTemplate |

**优缺点：**
- 优点：横切关注点（校验/字段填充）集中模板一处，子类不重复；批量/单条流程统一，事务入口单一；基类零框架依赖可单测。
- 缺点：继承链多一层；钩子覆写需防与模板默认逻辑冲突；模板粒度较粗（批量逐行聚合，字段级审计粒度待定）。

**UML 类图（ASCII）：**
```
XxxServiceImpl（toPO/toEntity，业务）
    ↑ extends
MpBaseServiceImpl<P,M,T>   — doXxx 实现，注入 TransactionTemplate/BaseMapper
    ↑ extends
AbstractBaseService<T>     — 模板方法 + before/after 钩子 + doInTransaction
    ↑ implements
IService<T> ← IBaseService<T>
```

**现状：** 批量入口经 `doInTransaction` 钩子执行（桥接层以共享 `TransactionTemplate` 实现）；门面非 `AbstractBaseService` 子类、够不到该钩子，跨表写经注入的共享 `TransactionTemplate`（`common/config/TransactionConfig`）编排，事务 Bean 单一收口。单表审计填充经 `MetaObjectHandler`（`MybatisPlusConfig`）插入/更新钩子完成。本设计不新增模板方法模式实例，沿用现状。

## 11. 分阶段实施计划

| 批次 | 内容 | 涉及 |
|------|------|------|
| 批1 认证主链 + DDL | 依赖（jjwt/jbcrypt/spring-data-redis）+ DDL（8.1 列宽放宽 + 8.2 空值 NULL 化 + 回填 + 8.3 会话表列/索引）+ `JwtUtil`/`UserContext`/`PasswordEncoder`/`TotpUtil` + `JtiBlacklistService`/`ChallengeTokenService`（自动装配 StringRedisTemplate，不建 RedisConfig）+ `TokenAuthInterceptor`（含 jti 黑名单校验）+ `WebMvcConfig` + `AuthController`/`AuthService`（注册/登录/刷新/登出/MFA verify——挑战凭证签发与消费，吊销写黑名单）+ `R.fail` 数据重载 + `BizException` payload + `ResultCode` 401/403/410 系 + `MetaObjectHandler` 审计填充 | auth 包 + common + sql |
| 批2 个人中心 | `ProfileController`/`ProfileService`（查/改 me + profile）+ 脱敏 `UserInfoVO` + DTO 校验 | profile 包 |
| 批3 账号安全 | 改密/重置/绑定解绑手机邮箱/MFA 开关（`SecurityController`/`SecurityService` + `TotpUtil.generateSecret` 挑战验证） | security 包 |
| 批4 设备+第三方绑定 | `DeviceController`/`DeviceService`（会话行属主实现）+ `BindController`/`BindService` | device + bind 包 |
| 批5 后台用户管理 | `AdminUserController`/`AdminUserService` + `requireUserType` 轻量鉴权 + 注销后缀释放逻辑 | admin 包 |
| 批6 收尾 | `ext_info` TypeHandler + 会话清理任务 + 字段级审计（若定方案） | common + 各包 |

每批独立可测：批1 认证主链可跑通注册→登录→鉴权→登出闭环，后续批次增量挂载。

## 12. 风险与后续

- **验证码/第三方 OAuth 实际通道**：本期接口预留校验点与 `ums_user_auth` 表结构，微信/支付宝授权回调、短信下发属外部集成，接入时按 `BindService` 扩展
- **MFA 恢复码**：TOTP 开启建议下发一次性恢复码（`mfaSecret` 旁另存），本期预留字段，批3 可含
- **密码算法演进**：`PasswordEncoder` 封装 BCrypt，旧 `salt` 字段兼容读；升级 Argon2 只换封装实现
- **会话清理**：`login_device` 增长需定期清理（Spring `@Scheduled` 或外部任务，经 DeviceService），批4 挂
- **审计人字段**：批1 起 MetaObjectHandler 从 UserContext 填充；无用户上下文（定时任务/初始化脚本）落 NULL，不阻断

# User Center 设计 + 服务层日志 Code Review 报告

> 日期：2026-08-07
> 分支：AI
> 范围：commit `a4aabaa`（`docs/superpowers/specs/2026-08-07-user-center-design.md`，377 行设计文档）+ 工作区未提交改动（`AbstractBaseService.java` 新增 `@Slf4j` + 8 条 `log.info` + `size()` 工具方法；`CLAUDE.md` 新增日志/待办说明）
> 方式：10 独立审查角度 × 各 8 候选 → 去重（28）→ 4 簇 1 票 3 态验证（26 保留）→ 缺口扫荡（+6）→ 上限 15 报告
> 结论：26 CONFIRMED/PLAUSIBLE 保留，3 REFUTED；按严重度报告 15 条

---

## 审查流程结构

### Phase 0 — 收集 diff

- 提交范围：`git diff HEAD~1...HEAD`（commit a4aabaa，设计文档）
- 工作区范围：`git diff HEAD`（AbstractBaseService 日志改动 + CLAUDE.md）
- 两者合入审查范围；无 upstream（AI 分支未配置）

### Phase 1 — 候选收集（10 个独立角度 × 各 ≤8 候选）

| 角度 | 内容 |
|------|------|
| A 逐行扫描 | 逐行读 diff，追问每行的输入/状态/时序/平台错误；null 解引用、条件反转、错误吞异常 |
| B 删除行为审计 | diff 为纯新增；转而审计新增代码破坏的不变量、DDL 变更删除的旧行为 |
| C 跨文件追踪 | 追踪模板方法调用方；`@Slf4j` 抽象基类字段冲突；spec 引用现有类是否属实 |
| D 语言陷阱 | Java/Spring/Lombok/MP：null 解引用、BCrypt 抛异常、Lombok 静态字段、JWT/TOTP |
| E 包装/模板正确性 | 模板方法钩子与日志顺序；批量聚合语义；门面层依赖 |
| Reuse | 复用现有工具（BeanCopyUtils 内联 size 惯用法） |
| Simplification | 8 条近似日志去重、`size()` 防御、spec 过度设计 |
| Efficiency | 日志热路径、批外循环、逐请求 DB 查询 |
| Altitude | 日志层级、删号后缀方案深度、批次顺序 |
| Conventions | CLAUDE.md 逐条核对：日志契约（L192）、设计模式三件套（约束2）、禁止凭空编造 API |

### Phase 2 — 验证（1 票 3 态）

- 去重同行/同机制候选，保留失败场景最具体者
- 4 个验证簇（各 1 个验证代理）：
  - 代码日志簇（C1-C8，读 AbstractBaseService/MpBaseServiceImpl/CLAUDE.md/impl）
  - 认证设计簇（D1-D4/D8/D12/D13/D17，读 spec + ResultCode + GlobalExceptionHandler）
  - 会话属主簇（D9/D10/D15/D16/D20）
  - 删号/DDL 簇（D5-D7/D11/D14/D18/D19，读 spec + sql/auth.sql）
- 判定：CONFIRMED（引行 + 具体错误输出）/ PLAUSIBLE（机制真、触发不定）/ REFUTED（引证行证伪）
- 召回模式：单个非 REFUTED 票即保留，不因不确定而丢弃

### Phase 3 — 缺口扫荡

- 新审查者持已验证清单重读 diff 与外围文件，只找未列缺陷
- 重点：移动/提取代码丢守卫、配置默认翻转、DDL 与代码错位、spec 引用不存在代码、会话/令牌字段缺失
- 新增 6 条：refreshToken 无存储列、门面够不到 `doInTransaction`、密码错误计数不重置、注销双语义、MFA 校验缺失、注册 DTO 缺设备字段

---

## 结果统计

| 阶段 | 数量 |
|------|------|
| 10 角度候选（去重后） | 28 |
| 验证保留 | 26（20 CONFIRMED / 6 PLAUSIBLE） |
| REFUTED | 3（D17 时钟偏移 / D18 kid 轮换 / D19 六包拆分） |
| 扫荡新增 | 6 |
| 报告上限 | 15 |

---

## 严重度分级

| 级别 | 数量 | 说明 |
|------|------|------|
| 🔴 严重（代码） | 2 | 已提交日志改动产生矛盾审计输出（成功=result=false）；批量结果语义误导 |
| 🔴 严重（设计） | 7 | 事务收口点不可达、refresh 无存储列、无状态矛盾、吊销失效、锁定不生效、注销双语义、MFA 失效 |
| 🟠 高 | 4 | 密码计数不重置、批次顺序倒置、后缀 UPDATE 顺序未定、钩子抛错丢日志 |
| ⚪ 约定 | 2 | JDK Base32 凭空编造（CLAUDE.md 约束 4）、日志硬编码「成功」措辞 |

---

## 发现明细（最严重优先）

### 🔴 1. 日志硬编码「成功」而 result 可为 false（代码，已提交）

- 文件：`src/main/java/com/sanye/strategy/common/base/AbstractBaseService.java:49`（同型 58/67/76/87/96/107/116）
- 验证：CONFIRMED
- 摘要：8 条 `log.info` 全部硬编码「成功」，同时打印 `result` 可 false——每条失败路径输出自相矛盾的成功行
- 触发：`doDeleteById` = `baseMapper.deleteById(id) > 0`，不存在 id 返回 false → 日志「删除成功 id=999, result=false」；insert/update 同理（MP 影响行数 0）。审计按「成功」检索收集假成功
- 修复建议：删掉「成功」措辞，操作标签 + result 表达结果
- 状态：✅ 已修复（2026-08-07）— AbstractBaseService 8 条日志改「新增/更新/删除/新增或更新」操作标签 + result，不硬编码成功

### 🔴 2. 批量日志报输入 size、delete 与 insert/update 聚合语义不一致（代码，已提交）

- 文件：`src/main/java/com/sanye/strategy/common/base/AbstractBaseService.java:116`
- 验证：CONFIRMED
- 摘要：批量日志打 `size(集合)` = 输入条数而非实际写入行数；`doDeleteBatch` 以 OR（任一成功）聚合，`doInsertBatch/doUpdateBatch` 以 AND（全部成功）聚合
- 触发：deleteBatch 10 个 id 中 9 个已逻辑删除 → `deleteByIds` 返 1 → 「批量删除成功 size=10, result=true」暗示全删；updateBatch 10 中 1 个不存在 → result=false 报整批失败。日志 size 是输入，非受影响行
- 修复建议：size 改报逐行聚合的实际处理数；delete 聚合语义与 insert/update 对齐
- 状态：✅ 已修复（2026-08-07）— 批量模板日志改 size/affected/result（affected 实际受影响行，result=affected==size）；doXxxBatch 改 int 返回、模板逐行聚合；doDeleteBatch 直接返 deleteByIds 结果

### 🔴 3. 门面够不到 `doInTransaction`（设计）

- 文件：`docs/superpowers/specs/2026-08-07-user-center-design.md:62`
- 验证：CONFIRMED（扫荡）
- 摘要：spec 3.1/10.3 称门面是跨表事务收口点（经 `doInTransaction`），但该钩子是 `AbstractBaseService` 的 protected 方法，门面（AuthService 等）非其子类，调用不到
- 触发：`AuthService.register`（5.1「事务：3-5 同事务（doInTransaction）」）需原子写 user+security+profile+session，但 protected 钩子不可达；单条 `insert()` 本身无事务，步骤 5 失败留下已提交的 user 行 + 孤儿 security/profile 行——部分注册
- 修复建议：门面自持事务（`TransactionTemplate` Bean）或门面继承某可注入事务的服务基类；spec 需明确事务实现载体
- 状态：✅ 已修复（2026-08-07）— TransactionConfig 定义共享 `TransactionTemplate` Bean（PROPAGATION_REQUIRED）；MpBaseServiceImpl 批量 doInTransaction 改注入该 Bean；spec 3.1/5.1/5.2/10.3 门面跨表事务落定

### 🔴 4. refreshToken 无存储列（设计）

- 文件：`docs/superpowers/specs/2026-08-07-user-center-design.md:106`
- 验证：CONFIRMED（扫荡）
- 摘要：refreshToken 定义为 SecureRandom 32B 不透明串「存会话行」，但 `ums_user_login_device`（sql/auth.sql L72-93）与 `UmsUserLoginDevicePO` 无 token/哈希列，8 节 DDL 也未加
- 触发：`POST /auth/refresh {refreshToken, deviceId}` 服务端无列可比对，refresh 无法实现；或退化为可猜的 19 位行 id（违背 SecureRandom 32B），知道 id 即可铸会话
- 修复建议：`ums_user_login_device` 加 `refresh_token_hash` 列（存哈希非明文），spec 补 DDL
- 状态：✅ 已修复（2026-08-07）— `ums_user_login_device` 加 `refresh_token_hash CHAR(64)` + `idx_refresh_token_hash`（sql/auth.sql）；UmsUserLoginDevice/PO 补字段；spec 8.3 补 DDL + 存量升级说明

### 🔴 5. 「请求不打库」与逐请求 userStatus 查询矛盾（设计）

- 文件：`docs/superpowers/specs/2026-08-07-user-center-design.md:109`
- 验证：CONFIRMED
- 摘要：4.1 称 accessToken「无状态校验，请求不打库」，4.3 拦截器逐请求「查用户状态：userStatus ∈ {冻结, 注销}」；claims 无 userStatus、无缓存、Redis 不在范围（非目标）
- 触发：每个非白名单请求多一次 `ums_user` SELECT，无状态声明不成立，中等 QPS 即乘 DB 负载；冻结/注销用户与 claims 双源可能漂移
- 修复建议：状态入 token（短 TTL）或在 token 签发/刷新时校验，接受最长 exp 的陈旧窗口；二选一并在 spec 言明
- 状态：✅ 已修复（2026-08-07）— 改选 jti 黑名单（Redis SETEX）：拦截器不再逐请求查库，冻结/注销/登出即时杀 access；spec 4.1/4.3/5.x/7 落定

### 🔴 6. 登出/踢设备/改密不吊销 access JWT（设计）

- 文件：`docs/superpowers/specs/2026-08-07-user-center-design.md:108`
- 验证：CONFIRMED
- 摘要：登出/踢设备/改密只失效会话行，access JWT（30min TTL）仅验签；拦截器不查会话有效性；`DEVICE_KICKED(401)` 不可达
- 触发：登出或管理员踢设备后，被偷/已发的 accessToken 仍通过拦截器 30 分钟；spec 声明了 `DEVICE_KICKED(401)`（4.3）但全文档无流程抛它，无黑名单/吊销机制
- 修复建议：接受「吊销仅作用于 refresh」的窗口并言明，或加 jti 黑名单/短 TTL 折中
- 状态：✅ 已修复（2026-08-07）— jti 黑名单即时吊销：登出/踢设备/改密/冻结/注销写黑名单，access 秒级失效，DEVICE_KICKED(401) 可达；spec 5.4/5.6/5.7 落定

### 🔴 7. refresh 无 jti 却按 jti 查会话（设计）

- 文件：`docs/superpowers/specs/2026-08-07-user-center-design.md:194`
- 验证：CONFIRMED
- 摘要：5.3 步骤 1「查会话行（jti）」，但请求体 `{refreshToken, deviceId}` 无 jti；refreshToken 是独立 32B 随机串，jti=行 id，spec 未言明二者关系
- 触发：refresh 查找键（jti）无输入来源，只能按存储的 refreshToken 值查，但 spec 未写该查找方式——流程无法按文实现
- 修复建议：明确「按 refresh_token_hash 列查会话行」，删除按 jti 的说法
- 状态：✅ 已修复（2026-08-07）— spec 5.3 步骤 1 改「按 refresh_token_hash 查会话行」，步骤 2 轮换写新哈希，删 jti 查找说法

### 🔴 8. lockTime 只写不读（设计）

- 文件：`docs/superpowers/specs/2026-08-07-user-center-design.md:182`
- 验证：CONFIRMED
- 摘要：登录步骤 3 达阈值置 `lockTime`，但步骤 4 与拦截器只查 `userStatus ∈ {冻结, 注销}`，从不读 `lockTime`；`ACCOUNT_LOCKED(403)` 不可达
- 触发：5 次错密码后 lockTime 已置，攻击者仍可无限试密码；声明的 `ACCOUNT_LOCKED(403)`（4.3）永远抛不出
- 修复建议：登录步骤 4 校验 `lockTime`（未过 → 抛 ACCOUNT_LOCKED）；并重置计数见第 10 条
- 状态：✅ 已修复（2026-08-07）— 5.2 登录步骤 4 加 lockTime 校验（未过抛 ACCOUNT_LOCKED），步骤 3 冻结/注销提前拦截且不参与计数；spec 4.3 可达性注释同步

### 🔴 9. 注销双语义：`user_status=0` vs `deleted=1`（设计）

- 文件：`docs/superpowers/specs/2026-08-07-user-center-design.md:280`
- 验证：CONFIRMED（扫荡）
- 摘要：schema/枚举用 `user_status=0`（`UserStatusEnum.CANCELLED`）表注销，8.1 却说注销 = `deleted=1` + 唯一标识后缀；拦截器「userStatus ∈ {冻结, 注销}」与后台筛选无法一致表达注销
- 触发：changeStatus 注销置 `deleted=1`，`user_status` 仍 NORMAL；下一请求 `getById` 因 `@TableLogic` 返 null → `ACCOUNT_DELETED(410)` 不可达、状态分支误判；按 userStatus 筛选的后台列表永不显示已注销用户
- 修复建议：定单一注销语义（推荐 `user_status=CANCELLED`，`deleted` 保持表逻辑删除位独立），spec 统一
- 状态：✅ 已修复（2026-08-07）— 注销 = user_status=CANCELLED（终态不可逆）+ 后缀释放同事务；deleted 独立（注销不动）；ACCOUNT_DELETED(410) 可达、后台按状态筛选可见；spec 5.2/5.7/8.1 统一

### 🟠 10. passwordErrorCount 成功不重置（设计）

- 文件：`docs/superpowers/specs/2026-08-07-user-center-design.md:182`
- 验证：CONFIRMED（扫荡）
- 摘要：错误计数只增不减，「连续错误」退化为终身计数
- 触发：错 4 次后登录成功，再来 1 次笔误即达阈值置锁；且锁永不因成功登录解除
- 修复建议：登录成功路径清零计数并清 `lockTime`
- 状态：✅ 已修复（2026-08-07）— 5.2 登录步骤 5 密码对 → 清 passwordErrorCount=0 + lockTime=NULL，计数不终身累积

### 🟠 11. `MFA_REQUIRED(403)` 无触发点（设计）

- 文件：`docs/superpowers/specs/2026-08-07-user-center-design.md:142`
- 验证：CONFIRMED（扫荡）
- 摘要：ResultCode 增 `MFA_REQUIRED(403)`，但 5.2 登录流程无 TOTP 校验步骤
- 触发：启用 MFA 的用户登录，密码通过直接发 token，无 OTP 验证 → MFA 零保护，`MFA_REQUIRED(403)` 永不可达
- 修复建议：登录流程加「mfaStatus=1 → 校验 OTP」分支（响应 MFA_REQUIRED + 挑战），再接 createSession
- 状态：✅ 已修复（2026-08-07）— 5.2 登录步骤 5 加 mfa_status=1 分支（抛 MFA_REQUIRED，通过前不发任何凭证）+ 新增 5.2.1 /auth/mfa/verify（密码 + OTP 双因子，通过后清计数/建会话/发凭证）

### 🟠 12. 批次顺序倒置：批5 后缀释放依赖批6 列宽（设计/计划）

- 文件：`docs/superpowers/specs/2026-08-07-user-center-design.md:366`
- 验证：CONFIRMED
- 摘要：批5 跑注销后缀释放 UPDATE，批6 才放宽列宽；50 字符用户名 + 24 字符 `#del#`+id 后缀 = 74 > 现 `VARCHAR(50)`
- 触发：批5 的 `CONCAT(LEFT(username,96),'#del#',id)` 在满长用户名上溢出 `VARCHAR(50)` → 严格模式 error 1406 / 非严格截断污染标识；批1-4 又在 `phone DEFAULT ''` 下撞唯一键（缺陷 11）
- 修复建议：8.1/8.2 DDL 前移进批1，批6 只留清理任务/字段审计
- 状态：✅ 已修复（2026-08-07）— 11 节批序调整：8.1/8.2/8.3 DDL 并入批1，批6 只留 ext_info/会话清理/字段审计；9 联动表批次同步

### 🟠 13. 后缀 UPDATE `WHERE deleted=0` 顺序未定（设计）

- 文件：`docs/superpowers/specs/2026-08-07-user-center-design.md:277`
- 验证：CONFIRMED
- 摘要：8.1 后缀释放 UPDATE 带 `WHERE deleted=0`，spec 未定其与逻辑删除在同事务内的先后；若 `deleted=1` 先执行，UPDATE 匹配 0 行，什么都不释放
- 触发：注销先逻辑删除再后缀 → `WHERE deleted=0` 命中 0 行，用户名/手机保持原值，`uk_username/uk_phone` 永久占用（缺陷 10 静默复现），重注册失败且无关联报错
- 修复建议：spec 钉死后缀 UPDATE 先于逻辑删除（同一 SQL 完成：置 deleted=1 + 后缀化，或先 UPDATE 再 removeById），并补已逻辑删除行的回填
- 状态：✅ 已修复（2026-08-07，由 9 消解）— 注销改 user_status=CANCELLED + 后缀单条原子 UPDATE，不再置 deleted，无先后窗口；deleted=1 仅归档路径（遗留见 8.1）

### ⚪ 14. 「JDK 内置 Base32」凭空编造（设计，CLAUDE.md 约束 4）

- 文件：`docs/superpowers/specs/2026-08-07-user-center-design.md:157`
- 验证：CONFIRMED
- 摘要：4.5 称 TOTP 用「JDK 内置 HmacSHA1 + Base32，不引第三方库」；JDK 有 HmacSHA1 与 `java.util.Base64`，无 `java.util.Base32`
- 触发：`TotpUtil` 按「内置 Base32」前提实现，要么需要未声明的第三方编解码库，要么编译不过；实现者按文找不到不存在的 API
- 修复建议：spec 明示自实现 Base32（或声明小依赖库）
- 状态：✅ 已修复（2026-08-07）— 4.5 改为 JDK 内置 HmacSHA1 + 自实现 RFC 4648 Base32，不引第三方库

### 🟠 15. 钩子抛错丢操作日志（代码，已提交）

- 文件：`src/main/java/com/sanye/strategy/common/base/AbstractBaseService.java:49`
- 验证：CONFIRMED
- 摘要：日志在 `afterXxx` 钩子之后；钩子抛错则日志不执行，而单条写已提交（单条操作不在 `doInTransaction` 内）
- 触发：子类覆写 `afterInsert` 发领域事件，发布器抛错 → `insert()` 在 `log.info` 前中断，但 `doInsert` 已提交 → 落库无操作汇总日志，调用方见 500——恰违背 CLAUDE.md「钩子被覆写会静默丢基类日志」的立意
- 修复建议：日志放钩子前或 `finally`；或与事务/钩子错误路径解耦
- 状态：✅ 已修复（2026-08-07）— 操作汇总日志整体暂移除，钩子抛错不再丢日志；操作/审计日志 + 请求/WEB 日志后续统一设计（CLAUDE.md 待办）

---

## 已验证但超出 15 上限（按序备查）

| # | 文件:行 | 验证 | 问题 |
|---|---------|------|------|
| C5 | AbstractBaseService:39 | CONFIRMED | `@Slf4j` 在抽象基类 → 5 个 service 全落同一 category，按 service 路由/过滤日志不可行 |
| C6 | AbstractBaseService:49 | CONFIRMED | CLAUDE.md L192 契约「entityClass/result/id」：insert/update/saveOrUpdate 缺 id，deleteById 缺 entityClass |
| C8 | AbstractBaseService:217 | CONFIRMED | afterXxx javadoc 仍邀钩子记日志，与「日志在模板」决策矛盾，诱子类双记/丢基类日志 |
| D6 | spec:288 | CONFIRMED | 8.2 空值 NULL 化无回填，存量 `''` 行仍占 `uk_phone('','+86')`/`uk_email('')` |
| D8 | spec:142 | CONFIRMED | `ResultCode.UNAUTHORIZED(401)` 已存在（ResultCode.java:32），GlobalExceptionHandler 已按 code 映射 HTTP 状态；spec「新增 UNAUTHORIZED/缺 401 分支」不实 |
| D9 | spec:184 | CONFIRMED | 登录 5.2 步骤 5-6 无事务（对比注册显式声明），会话行与 lastLogin 可能半写 |
| D10 | spec:63 | CONFIRMED | 「会话行收口 DeviceService」未执行：refresh/改密/后台注销/清理直写 `login_device` |
| D12 | spec:156 | CONFIRMED | BCrypt.checkpw 对遗留哈希抛 IllegalArgumentException，`PasswordEncoder.matches` 无回退 → 旧账号无法登录 |
| D14 | spec:356 | CONFIRMED | 10.3 模板方法节缺「角色说明 + 优缺点 + UML」三件套（CLAUDE.md 约束 2） |
| D11 | spec:274 | PLAUSIBLE | 被删行后缀 `{name}#del#{id}` 可被同名新注册撞上（需 `#` 合法 + 知 19 位雪花 id，低概率） |
| D15 | spec:127 | PLAUSIBLE | 注册/登录走白名单无 UserContext，登录步骤 6 改 user 行时 updateUserId 仍 NULL（窄缺口） |
| D16 | spec:136 | PLAUSIBLE | ThreadLocal 仅 afterCompletion 清理，无异步/防御性清空（当前全同步，未实证泄漏） |
| D20 | spec:63 | PLAUSIBLE | Auth→Device 门面耦合/属主倒置（spec 10.1 已明示接受，权衡非缺陷） |
| C2 | AbstractBaseService:49 | PLAUSIBLE | `entity.getClass()` 对 null 实体 NPE（仅子类/fake doXxx 测试路径可达，当前生产不可达） |
| C7 | AbstractBaseService:202 | PLAUSIBLE | `size()` 复现 BeanCopyUtils:60 内联惯用法；空值防御仅护日志格式化 |

**上限外状态（2026-08-07）：**

| # | 结论 |
|---|------|
| C5 / C6 / C2 | ✅ 连带消解 — 模板操作日志整体移除，`@Slf4j` / 日志契约 / `getClass()` 均不再存在 |
| C8 | ✅ 已修复 — 钩子 javadoc「日志记录、事件发布等」→「事件发布等」 |
| C7 | ✅ 已澄清 — `size()` 现用于批量 result 判定（`affected==size`），非仅日志格式化，保留合理 |
| D6 | ✅ 已修复 — 8.2 补存量 `''` → NULL 回填 |
| D8 | ✅ 已修复 — 4.3 删 UNAUTHORIZED 新增声明（已存在），401 已按 code 映射 |
| D9 | ✅ 连带消解 — 5.2 重写已加「事务：5(成功清零)-7 同事务」 |
| D10 | ✅ 已修复 — 5.3/5.4/5.7/9 节会话行读写显式经 DeviceService |
| D11 | ✅ 已文档化 — 8.1 补残留风险说明，唯一键冲突走 409 CONFLICT |
| D12 | ✅ 已修复 — 10.3 补角色说明 + 优缺点 + UML 三件套 |
| D15 / D16 / D20 | ⚪ 接受/已文档化 — 白名单无 UserContext 填 NULL（4.4）、ThreadLocal afterCompletion 清理、Auth→Device 耦合为 10.1 明示权衡 |

## REFUTED

| # | 候选 | 证伪依据 |
|----|------|---------|
| D17 | JWT 时钟偏移 | 30min TTL 下秒级漂移无实质影响，设计阶段非缺陷 |
| D18 | kid 密钥轮换过度设计 | 低成本标准安全模式（版本前缀 + kid），可辩护 |
| D19 | 六能力包过度设计 | 门面为跨表事务边界、DeviceService 单属主，有据可依，非过度 |

---

## 修复优先级建议

1. **代码（已提交，先改）**：第 1 条删「成功」措辞；第 2 条批量 size 改实际处理数 + delete 聚合语义对齐；第 15 条日志移钩子前/finally
2. **设计事务基石**：第 3 条定门面事务载体（TransactionTemplate/继承）；第 4 条 `login_device` 加 `refresh_token_hash` 列 + DDL
3. **认证闭环**：第 5 条无状态声明二选一；第 6 条吊销窗口言明/黑名单；第 7 条 refresh 按哈希列查
4. **账号安全**：第 8 条 lockTime 生效 + 第 10 条成功清零；第 11 条登录补 OTP 分支
5. **删号/DDL**：第 9 条注销语义统一；第 12 条 DDL 前移批1；第 13 条后缀先于逻辑删除 + 存量回填
6. **约定**：第 14 条 Base32 落实法；C8 javadoc 同步「日志在模板」决策

## 修复状态（2026-08-07）

| # | 内容 | 状态 | 落地 |
|---|------|------|------|
| 1 | 日志硬编码「成功」 | ✅ 已修复 | AbstractBaseService 日志改操作标签 + result |
| 2 | 批量 size/聚合语义 | ✅ 已修复 | 批量日志 size/affected/result；doXxxBatch 改 int 返回，模板逐行聚合 |
| 3 | 门面够不到 doInTransaction | ✅ 已修复 | TransactionConfig 共享 TransactionTemplate Bean；spec 3.1/5.1/5.2/10.3 落定 |
| 4 | refreshToken 无存储列 | ✅ 已修复 | login_device 加 refresh_token_hash 列 + 索引；PO/实体补字段；spec 8.3 DDL |
| 5 | 「请求不打库」vs 逐请求查 userStatus | ✅ 已修复 | jti 黑名单（Redis SETEX）替代逐请求查库，拦截器零 DB；spec 4.1/4.3 落定 |
| 6 | 登出/踢设备/改密不吊销 access | ✅ 已修复 | 吊销操作写 jti 黑名单，access 秒级失效，DEVICE_KICKED 可达 |
| 7 | refresh 按 jti 查 | ✅ 已修复 | spec 5.3 改按 refresh_token_hash 查会话行 |
| 8 | lockTime 只写不读 | ✅ 已修复 | 5.2 登录加 lockTime 校验，ACCOUNT_LOCKED 可达 |
| 9 | 注销双语义 | ✅ 已修复 | user_status=CANCELLED 单语义，deleted 独立，410 可达 |
| 10 | passwordErrorCount 成功不重置 | ✅ 已修复 | 登录成功清零计数 + lockTime |
| 11 | MFA_REQUIRED 无触发点 | ✅ 已修复 | 5.2 加 mfa 分支 + 5.2.1 verify（双因子），MFA_REQUIRED 可达 |
| 12 | 批次顺序倒置 | ✅ 已修复 | 8.1/8.2/8.3 DDL 前移批1，批6 只留清理/审计 |
| 13 | 后缀 UPDATE 顺序未定 | ✅ 已修复（由 9 消解） | 注销改 CANCELLED + 后缀单条原子 SQL，无先后窗口 |
| 14 | JDK 内置 Base32 凭空编造 | ✅ 已修复 | 4.5 改自实现 RFC 4648 Base32 |
| 15 | 钩子抛错丢操作日志 | ✅ 已修复 | 模板日志整体移除，后续统一审计日志设计 |

---

*验证方法：10 角度候选经 4 簇 1 票 3 态验证，CONFIRMED 需引用确切行与具体错误输出；MP 行为经 `MpBaseServiceImpl` 源码核实；`ResultCode`/`GlobalExceptionHandler` 现有能力经源码核实。*

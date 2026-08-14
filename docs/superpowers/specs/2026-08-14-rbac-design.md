# RBAC 权限管理批设计

日期：2026-08-14
分支：AI

## 背景与现状

认证主链批1 已落地（注册/登录/刷新/登出/MFA + jti 黑名单）。RBAC 现状部分完成：

- **已完成（DB）**：`ums_role` / `ums_permission` / `ums_user_role` / `ums_role_permission` 表 + 4 内置角色 seed（`sql/rbac.sql`）
- **已完成（Java）**：`UmsRole` / `UmsUserRole` 实体 + 契约、注册默认绑定 NORMAL_USER、JWT `roles` claim 快照、`UserContext.roleCodes`、`UmsUserRoleServiceImpl`（读生效角色码 + 单绑）
- **缺失**：`UmsPermission` / `UmsRolePermission` Java 侧三件套、接口鉴权、RBAC 管理 API、数据权限

本批补齐上述缺失。

## 范围决策（已确认）

1. **接口鉴权** — 注解静态权限码 `@RequiresPermission`，JWT 新增 `perms` 快照 claim，拦截器比对，零 DB 查询
2. **权限数据补全** — `UmsPermission` / `UmsRolePermission` 实体 + PO + Mapper + Service
3. **RBAC 管理 API** — 角色/权限/绑定/批量/状态/克隆/导入导出/有效期/审计/调试 + 权限变更自动踢人 + 角色到期定时踢
4. **数据权限** — 本期仅 1/2 级（全部数据 / 仅本人），方案 C（领域层 `IWrapper` 装配），3/4/5 级留扩展位（需部门表 + 数据域表，后续模块）

**已否决/取消：** 权限缓存主动失效（Redis 不承载业务缓存）→ 改为「权限变更后批量踢受影响用户，重登生效」。

## 架构

模块落位（DDD 分层 + 新 rbac 能力包）：

```
interfaces/rbac/         RbacRoleController / RbacPermissionController / RbacUserRoleController
                         / RbacRolePermissionController / RbacQueryController + dto/ + vo/
application/rbac/        RbacManageService（管理面门面，跨表事务 + 变更后自动 evict）
                         PermissionSyncService（注解扫描注册 + 手动同步）
                         RbacAuthzService（生效权限查询 / 调试）
domain/rbac/             UmsPermission / UmsRolePermission 实体 + repository 契约
                         UmsRole / UmsUserRole 扩展授权/续费/批量方法
infrastructure/security/ RequiresPermission 注解 + NoPermissionRequired 注解 + PermissionInterceptor
                         JwtUtil 加 perms claim；DataScopeBuilder（方案 C 数据权限装配）
infrastructure/config/   PermissionScanRegistrar（启动扫描）
```

## 接口鉴权管道

### 注解

```java
@Retention(RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresPermission {
    String value();   // 权限码，三段式 模块:资源:操作，如 "system:role:manage"
}

@Retention(RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface NoPermissionRequired {}   // 显式豁免鉴权注解，用于 /rbac/** 内仅需登录的接口
```

- `@RequiresPermission`：方法级优先，类级兜底（整个 Controller 需权限）
- `@NoPermissionRequired`：拦截器**优先识别**，标注类/方法跳过权限校验（仅需登录）。不硬编码路径前缀，保持一致性
- **权限码格式校验**：`PermissionScanRegistrar` 扫描到 `@RequiresPermission` 时校验——至少三段 `模块:资源:操作` 各段非空（`system:manage` 非法，资源路径不允许为空），字符集**按段**（模块/操作 `[a-z][a-z0-9-]+` 禁冒号，资源段 `[a-z][a-z0-9-]+(:[a-z][a-z0-9-]+)*`，见「权限点模型」）。不符合 → 启动失败（fail-fast，防误写）

**无注解规则：** 默认放行（仅需登录，兼容现有 auth 接口）；**例外收紧 `/rbac/**`** —— `/rbac/**` 无 `@RequiresPermission` 且无 `@NoPermissionRequired` 的方法默认 403，防管理接口漏标注解。`RbacQueryController` 类标 `@NoPermissionRequired`（当前用户查询，仅需登录）。

### PermissionInterceptor（责任链新环，注册于 TokenAuthInterceptor 之后）

```
handler 非 HandlerMethod → 放行（静态资源）
类/方法含 @NoPermissionRequired → 放行
无 @RequiresPermission → /rbac/** 拒 403；其余放行
角色含 SUPER_ADMIN → 直通（硬兜底，防权限点误删锁死超管）
JWT perms claim 含该权限码 → 放行
否则 → 403 FORBIDDEN（新增 ResultCode.FORBIDDEN）
```

零 DB 查询。

### JWT 变更

`roles` 保留，新增 `perms`（权限码数组，签发时联表并集去重）。SUPER_ADMIN 因直通，perms claim 可为空。`jti` 仍是 String（RFC 7519）。

**体积控制：** 配置 `jwt.perm-claim-max`（默认 500）。权限码数超限 → ERROR 日志告警 + 该 token `perms` 置空（safe-degrade：非 SUPER_ADMIN 全拒权限接口，宁拒勿越权）。**UX 兜底：** 权限码数量监控提前告警（接近上限即告警）；前端可提示「权限过多，请联系管理员精简角色」；运维手册说明 safe-degrade 触发条件与处置。不引入「部分降级」（带部分码会引入复杂度，否决）。

## 权限点模型

- 三段式 `模块:资源:操作`。**资源可为多级路径，用冒号分隔**（如 `rbac:debug`），故 `system:rbac:debug:manage` 合法 = 模块 `system` + 资源路径 `rbac:debug` + 操作 `manage`。解析规则：首个冒号前=模块，末段=操作，中间=资源路径；**资源路径不允许为空**（`system:manage` 非法，至少三段）
- **权限码字符集按段校验**（防解析歧义）：模块、操作仅 `[a-z][a-z0-9-]+`（**不允许冒号**）；资源路径 `[a-z][a-z0-9-]+(:[a-z][a-z0-9-]+)*`（冒号仅资源段多级分隔）。扫描时按段校验，禁止 `sys:tem:role:manage` 这类模块段含冒号的歧义码
- 操作级细分只需把 `manage` 拆开（`create/update/delete/assign`），不破坏现有码
- `permission_type=4`（接口）由注解扫描自动注册，`parent_id=0`，手工可挪菜单下
- 非接口类资源（目录/菜单/按钮）仍 SQL seed 手工配

**管理 API 权限码清单：**

| 权限码 | 覆盖接口 |
|--------|---------|
| `system:role:manage` | 角色 CRUD / 状态 / 克隆 / 导入导出 |
| `system:role:assign` | 角色-权限绑定（独立码，与权限资源管理分离，最小权限） |
| `system:permission:manage` | 权限资源 CRUD / 树 / 同步 / 状态 |
| `system:user:role:manage` | 用户-角色绑定 / 解绑 / 续期 |
| `system:rbac:debug:manage` | 调试 + evict（受全局开关 `rbac.debug-enabled` 控制，生产关） |

**系统数据强制约定：** 内置/seed/注解扫描自动注册的权限数据 `create_user_id` **必须为 NULL**（不引入 is_system 字段）。seed 脚本与 `PermissionScanRegistrar` 代码强制保证，管理面过滤据此放行系统数据（见「两套数据权限」）。

### 权限注册

- **启动扫描**：`PermissionScanRegistrar` 扫 `@RequiresPermission`，**只做新增**（INSERT IGNORE 按 `uk_permission_code`）+ **复活恢复**（已存在 `status=0` 但本次命中 → `UPDATE SET status=1 WHERE status=0` 幂等恢复，覆盖误删后恢复场景）。不做残留停用——残留保持 `status=1` 直到手动同步，**发布流程强制执行手动同步**（运维步骤，见开发注意事项 3）
- **手动同步** `POST /rbac/permissions/sync`：默认执行；`?dryRun=true` 仅返回差异（新增/复活/残留预览）不执行，给管理员确认影响面。残留标 `status=0` 停用（有角色绑定不物理删，不自动解绑）——**残留停用同「权限停用」规则，自动触发绑定该权限角色的用户 evict；复活恢复同「权限启用」规则，同样触发 evict**（对称）；状态变更用 `UPDATE ... WHERE status != target` 幂等，防并发覆盖；同实例 ReentrantLock 串行，集群部署补 Redis 锁（TODO）

## 管理 API 面

### RbacRoleController — 码 `system:role:manage`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/rbac/roles/page` | 分页（管理面过滤，见「两套数据权限」） |
| GET | `/rbac/roles/{id}` | 详情（含权限码列表） |
| POST | `/rbac/roles` | 新增 |
| PUT | `/rbac/roles/{id}` | 改（内置角色禁改 role_code/is_built_in） |
| DELETE | `/rbac/roles/{id}` | 删（内置禁删，有关联角色禁删） |
| PUT | `/rbac/roles/{id}/status` | 启停（不删关联，即时收回权限；**停用自动踢该角色下用户**） |
| POST | `/rbac/roles/clone` | 克隆（角色 + 全套权限；role_code = 源 + `_COPY` + 序号，**超长截断源段**，截断后查重保证唯一） |
| GET | `/rbac/roles/export` | JSON 导出（角色 + 权限绑定） |
| POST | `/rbac/roles/import` | JSON 导入（按 role_code 匹配：已存在跳过，`?overwrite=true` 覆盖；**含未注册权限码 → 忽略 + 告警，不整体失败**，导入报告列忽略项；**overwrite=true 且权限绑定发生变化 → 触发该角色下用户 evict**） |

### RbacPermissionController — 码 `system:permission:manage`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/rbac/permissions/tree` | 目录/菜单/按钮/接口资源树 |
| POST | `/rbac/permissions` | 新增资源 |
| PUT | `/rbac/permissions/{id}` | 改 |
| DELETE | `/rbac/permissions/{id}` | 删（有角色绑定禁删，先解绑） |
| PUT | `/rbac/permissions/{id}/status` | 启停（**停用自动反查绑定角色 → 用户 → 踢下线**） |
| POST | `/rbac/permissions/sync` | 手动扫描同步（`?dryRun=true` 预览） |

### RbacUserRoleController — 码 `system:user:role:manage`

| 方法 | 路径 | 说明 |
|------|------|------|
| PUT | `/rbac/users/{id}/roles` | 单用户覆盖多角色（带 begin/end；**变更自动踢该用户**） |
| POST | `/rbac/users/roles` | 批量用户授同一角色（带 begin/end；**变更自动踢受影响用户**） |
| DELETE | `/rbac/users/{id}/roles/{roleId}` | 解绑（**自动踢该用户**） |
| PUT | `/rbac/users/{id}/roles/{roleId}/renew` | 单角色续期（改 end_time；**自动踢该用户**） |
| GET | `/rbac/user-roles/expiring` | 即将/已过期绑定分页 |
| POST | `/rbac/user-roles/renew` | 批量续期（**自动踢受影响用户**） |
| GET | `/rbac/users/{id}/roles` | 用户角色列表 |

### RbacRolePermissionController — 码 `system:role:assign`

| 方法 | 路径 | 说明 |
|------|------|------|
| PUT | `/rbac/roles/{id}/permissions` | 覆盖绑定（勾选 UI，全量替换；**自动踢该角色下用户**） |
| POST | `/rbac/roles/{id}/permissions` | 增量绑定（**自动踢该角色下用户**） |
| DELETE | `/rbac/roles/{id}/permissions` | 批量回收（**自动踢该角色下用户**） |
| GET | `/rbac/roles/{id}/permissions` | 角色当前权限集 |

### RbacQueryController — 类标 `@NoPermissionRequired`（当前用户，仅需登录）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/rbac/my/permissions` | 我的权限码集（合并多角色去重） |
| GET | `/rbac/my/menu-tree` | 我的目录/菜单树（前端渲染） |

### 调试与主动失效 — 码 `system:rbac:debug:manage` + 开关 `rbac.debug-enabled`（生产关）

开关关闭时 Controller 整体禁用，**返回 403（非 404，防暴露接口存在）+ 记录访问日志**。**注意：自动 evict 属管理写操作链路（RbacManageService 内部触发），不受 `rbac.debug-enabled` 控制**，仅人工调试接口受开关约束。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/rbac/users/{id}/effective-permissions` | 实际生效权限（合并多角色、过滤禁用/过期） |
| GET | `/rbac/users/{id}/check?permission=x` | 指定用户校验权限（排查） |
| POST | `/rbac/users/{id}/evict` | 踢单用户（写 jti 黑名单） |
| POST | `/rbac/evict-batch` | 按角色批量踢（`?mode=sync` 小量同步 / `?mode=async` 大量返回 taskId 后台执行） |

**evict 实现：** 目标用户集 → 联查 `ums_user_login_device` 活动会话取 jti → 每批 `SETEX jti:{jti} (expire_time - now)`（**取 token 剩余 TTL**，已过期会话跳过，黑名单不膨胀）。异步任务注册表内存 `ConcurrentHashMap` + 独立线程池，`GET /rbac/evict/tasks/{taskId}` 查进度；**任务完成后从 Map 清理**（保留最近 100 条 / 24h，超限逐出，防内存泄漏）。

## 权限变更自动触发 evict（P0 闭环）

生产链路**不依赖人工调 evict-batch**。`RbacManageService` 管理写操作成功后自动触发受影响用户 evict：

| 管理操作 | 受影响用户集 |
|---------|------------|
| 角色权限覆盖 / 增量 / 回收 | 该角色下在线用户 |
| 角色停用 / **启用** | 该角色下用户（对称，恢复也踢——已登录用户 token 无/有权限码，需重登同步） |
| 用户角色覆盖 / 解绑 / 续期 / 批量授 / 批量续期 | 该用户 / 受影响用户集 |
| 权限停用 / **启用 / 复活恢复** | 反查绑定该权限的角色 → 角色下用户（对称，启用/复活也踢） |

**执行模型（独立于业务事务）：**

```
业务事务提交成功 → 事务同步 afterCommit 触发 evict（不参与业务回滚）
受影响用户数 ≤ rbac.evict-async-threshold（默认 50）→ 同步执行
受影响用户数 > 阈值 → 提交到独立线程池异步执行，返回 taskId
```

**taskId 可见性：** 管理写操作响应 VO 增加可空字段 `evictTaskId`（仅异步时有值，同步为 null），管理员可据此查 `GET /rbac/evict/tasks/{taskId}` 进度；审计日志同步记录 taskId。

**失败处理：** evict 独立线程执行，失败重试 3 次（指数退避）→ 仍失败转审计失败状态 + ERROR 告警（被踢用户集持久到审计 oper_desc），提供补偿接口（`POST /rbac/users/{id}/evict` 单踢 / `evict-batch` 重踢）兜底。Redis 抖动不导致业务回滚，权限变更已生效，受影响的只是踢人延迟。

调试接口 `POST /rbac/users/{id}/evict` 保留作人工兜底。

## 角色到期自动 evict（定时任务）

`ums_user_role` 有 `begin_time` / `end_time`，JWT 快照在登录时生成，token 有效期内角色到期 → 过期授权仍有效至 TTL（安全窗口）。补定时任务：

- **`RbacRoleExpiryScheduler`**：周期 1min（`rbac.expiry-scan-interval-minutes`），**只扫已过期**绑定（`end_time <= now` 且非 null）→ 受影响用户 evict（复用 evict 能力），**收回过期授权**。**不提前扫**（提前踢会在角色真正到期前反复踢——重登新 token 仍含该角色，体验受损）；「即将到期提醒」属另一功能，不与权限收回混用
- `begin_time` 到达**不主动踢**：缺权限是安全默认，用户 refresh/重登自然获得新角色，不引入无谓踢人
- 周期执行幂等：全量扫已过期绑定，会话黑名单 TTL 取剩余，已过期会话 TTL=0 跳过——重复执行无重复踢实际效果（可记录 lastScanTime 做增量减负载，本期不做）
- **审计**：定时任务触发的 evict **同样写审计**——`oper_type=EVICT_USER`，oper_desc 注明来源「角色到期定时任务」，批量记录**一条摘要**（被踢用户数、触发原因、时间），不逐用户记录
- **集群策略**：本期单实例部署，幂等重复执行无害；集群部署需分布式锁/单实例选举保证同刻仅一实例执行（TODO）
- 运维手册提示：定时任务依赖应用在线，批量到期场景仍以主动踢人为主

## 写操作约定

- 批量/覆盖/跨表操作走 `TransactionTemplate`（复用 `infrastructure/config/TransactionConfig` 共享 Bean）
- **审计日志独立事务**（REQUIRES_NEW）：业务失败也记录尝试（oper_desc 记失败上下文），写审计失败不影响主流程（at-least-once 语义，失败降级日志告警）。`ums_oper_log` 不继承 SimpleBaseEntity（无 deleted/update_time），独立 PO + Mapper 直写
- **审计记变更快照**：oper_desc 存 旧→新 关键字段（如用户角色集 diff、角色权限集 diff、begin/end 变更），`oper_type` 区分新增/修改/删除/授权/导入/导出 + 新增 EVICT_USER 踢下线类型。evict 接口强制写审计（被踢用户 ID、触发来源、批量 taskId、**失败状态**，失败状态入 `status` + `error_msg`）
- **字段对齐现有 `ums_oper_log` 表**（oper_log.sql 字段齐全：user_id/username/oper_module/oper_action/oper_desc/oper_type/request_method/request_uri/request_params/request_body/response_code/response_msg/cost_time/oper_ip/user_agent/status/error_msg），不新增字段
- **覆盖绑定不引入逻辑删除**（ums_user_role / ums_role_permission 物理删除关系表约定，逻辑删除 + 唯一键会永久占用唯一键，见 CLAUDE.md）；历史经审计变更快照可查，不新建历史表
- 批量授角色分批提交（100/批），失败集返回；覆盖绑定时解旧绑新，`begin < end` 校验

## 内置角色保护

- `is_built_in=1`：禁删、禁改 role_code、禁停用 SUPER_ADMIN
- 内置角色**可改权限集合**（非铁板）；例外：SUPER_ADMIN 禁清空权限（保直通兜底）
- API 层强制 + 前端隐藏操作

## 数据权限（方案 C，本期 1/2 级）

```java
public interface DataScopeBuilder {
    // 查询方声明归属列（如 "user_id"、"create_user_id"），按当前用户角色推导行条件
    <T> IWrapper<T> applyDataScope(IWrapper<T> wrapper, String ownerColumn);
}
```

规则：`UserContext.roleCodes` → 查 `ums_role.data_scope`（**实时** DB 查询）→ 任一 role `data_scope=1` → 无条件；否则全部 `=2` → `wrapper.eq(ownerColumn, ctx.userId)`。空角色/无上下文 → 仅本人（安全默认）。

**性能权衡：** 本期权限点/角色量小，直接实时查询。压测后如需，可引入 Caffeine 短 TTL（~1min）本地缓存 + data_scope 变更时主动失效（TODO，不本期做）。业务表落地在 strategy 后续模块。

### 两套数据权限（文档区分，防混淆）

| 维度 | 管理面过滤 | 业务 DataScopeBuilder |
|------|-----------|---------------------|
| 作用域 | RBAC 角色/权限分页 | 业务表行查询 |
| 规则 | 硬编码 `create_user_id IS NULL OR create_user_id = userId`（内置/seed/注解扫描数据 `create_user_id` 强制 NULL，必须可见；SUPER_ADMIN 绕过） | `ownerColumn = userId`（data_scope=1 绕过） |
| 模型 | 谁创建看谁 + 系统数据放行 | data_scope 驱动 |
| 时效 | 实时 | 实时 |

> **⚠️ 管理面过滤仅限 RBAC 角色/权限分页，禁止复用业务表**。它与 DataScopeBuilder 是两套机制，文档/注释/TODO 反复强调。

**SUPER_ADMIN 绕过实现：** 在 DAO 层封装统一过滤方法（RBAC 分页查询契约内），`UserContext.roleCodes` 含 SUPER_ADMIN 则跳过 `create_user_id` 过滤；不散落各 Controller。

## 功能权限 vs 数据权限生效模型（反复对比强调）

| 模型 | 载体 | 生效时效 | 变更生效方式 |
|------|------|---------|-------------|
| 功能权限（perms/roles） | JWT 快照 | 滞后 ≤ accessToken TTL（30min） | 管理写操作自动踢人（jti 黑名单）或 30min 自然过期；高敏变更必须踢人 |
| 数据权限（data_scope） | 实时 DB 查询 | 即时 | 改 `ums_role.data_scope` 下请求即生效 |

每相关接口 javadoc 必须写明所属模型，防混淆。

## 配置项

| 配置 | 默认 | 说明 |
|------|------|------|
| `jwt.perm-claim-max` | 500 | perms claim 权限码上限，超限 safe-degrade |
| `rbac.debug-enabled` | false（生产） | 调试/evict Controller 全局开关 |
| `rbac.evict-async-threshold` | 50 | 受影响用户数阈值，超则异步 |
| `rbac.expiry-scan-interval-minutes` | 1 | 角色到期扫描定时任务周期 |

## 开发注意事项（非阻断，编码时落实）

1. **权限码格式校验**：`PermissionScanRegistrar` 扫描时**按段**校验——模块/操作 `[a-z][a-z0-9-]+`（禁冒号），资源路径 `[a-z][a-z0-9-]+(:[a-z][a-z0-9-]+)*`，至少三段各段非空，不符合启动失败
2. **手动同步 dry-run**：`?dryRun=true` 仅返回差异不执行，默认执行，管理员确认影响面
3. **启动扫描不做残留停用**：残留权限保持 status=1 直到手动同步；**发布流程强制执行手动同步**（运维步骤）
4. **角色克隆长度**：`源_COPY_序号` 超长截断源段，截断后查重保证唯一
5. **角色导入权限码兼容**：JSON 含未注册权限码 → 忽略 + 告警（不整体失败），导入报告列忽略项
6. **afterCommit 重启丢任务**：运维手册提示发布时避免大批量权限变更；重启后补偿脚本（扫描审计失败/未完成 evict 的变更记录重新触发），本期不做
7. **SUPER_ADMIN 绕过**：DAO 层统一过滤方法封装，不散落
8. **debug 关闭行为**：返回 403（非 404 防暴露）+ 访问日志
9. **oper_log 字段**：对齐现有表，不新增字段，变更快照入 oper_desc
10. **权限码解析边界**：资源路径不允许为空（`system:manage` 非法），至少三段，扫描时校验

## 测试策略

- 单测：PermissionInterceptor（SUPER_ADMIN 直通、claim 缺失、403、/rbac/** 无注解拒、@NoPermissionRequired 豁免）；DataScopeBuilder 条件装配（1/2/空角色/无上下文）；覆盖绑定事务回滚；时间窗过滤（begin/end）；同步 evict TTL 取剩余时间；权限码超限 safe-degrade；权限同步「复活」恢复 status + 复活触发 evict；权限码按段格式校验（模块/操作禁冒号）
- 集成：注册→授权→请求鉴权→数据权限装配端到端；管理写操作自动 evict 闭环（改权限→用户被踢→重登新快照生效；evict 失败重试 + 审计失败状态）；异步 evict taskId 查询 + 写操作响应 evictTaskId；角色到期定时扫描踢人

## 非目标（迭代 todo）

- 3/4/5 级数据权限 — 需 `ums_dept` + `ums_role_data_scope` 表
- 操作级权限码进一步细分（`system:role:create` 等）
- 异步任务持久化（本期内存注册表，重启丢任务；生产大批量变更需规避）
- 权限导入导出跨环境加密
- DataScopeBuilder 短 TTL 本地缓存（压测后按需）
- 权限同步分布式锁（集群部署时补）
- **角色到期定时任务集群分布式锁**（本期单实例 + 幂等重复无害；集群部署须保证同刻仅一实例执行）
- 「即将到期提醒」功能（与权限收回踢人分离，另设）

## 待办记档（CLAUDE.md「📝 备忘」行，本期不实现）

1. 管理面 `create_user_id` 过滤禁止复用业务表（系统数据强制 NULL 放行规则）
2. evict-batch 异步任务内存实现重启丢任务，生产大批量变更需规避
3. 功能权限快照 vs 数据权限实时两模型差异，文档/注释反复强调

## 实现偏离（Task 14 回写）

本批实现与设计文档的实际出入（仅记档，不回改实现）：

1. **createRole/updateRole 持久化 dataScope 需显式转换**：`RoleDTO.dataScope` 为 `Integer` 码值、`UmsRole.dataScope` 为 `DataScopeEnum`，字段类型不符，`BeanCopyUtils`（`BeanUtils.copyProperties`）同名不同型静默丢弃——两个方法均显式 `DataScopeEnum.valueOf(dto.getDataScope())` 转换补上，非法码值告警（新增按 DB 默认全部数据、修改保持原值）。
2. **assignRolesBatch 时间窗经 5-arg `assignRole(userId, roleId, assignerId, begin, end)` 重载应用**：spec 只约定「批量授角色带 begin/end」，实现复用 `UmsUserRoleService` 既有 5-arg 重载逐用户应用时间窗，未新增契约方法。
3. **`evictTaskId` 未上抛到管理写响应**：spec 约定「管理写响应 VO 增加可空字段 evictTaskId」，实现中门面写方法返回类型（void/boolean/int/Long/SyncReport）均不含该字段——taskId 仅落在审计 `oper_desc` 与 `EvictTaskRegistry` 注册表，可经 `GET /rbac/evict/tasks/{taskId}` 查询。响应面是否回显 taskId 留待前端需要时追加。
4. **SUPER_ADMIN 权限校验 wildcard `*` 匹配**：`RbacAuthzService.effectivePermissions` 对 SUPER_ADMIN 返回 `["*"]` 通配标记、`checkPermission` 命中 `*` 即放行——与 `PermissionInterceptor` 的 SUPER_ADMIN 直通语义对齐（spec 仅描述拦截器直通，未定义调试端通配表示，实现以 `*` 收口）。
5. **RbacDebugController 开关关闭返回 403**：spec 硬约束「关闭返回 403 防暴露」，实现不用 `@ConditionalOnProperty`，类常装配 + 每端点 `ensureEnabled()` 校验，关闭时 403 + 访问日志（操作人）。
6. **evict-async-threshold 默认值双份冗余**：`@Value("${rbac.evict-async-threshold:50}")` 兜底 + `application.yaml` 显式 50（与 spec 默认一致）；该字段非 final，无 Spring 上下文的纯单测默认 0（所有 evict 误判异步）——`RbacManageServiceTest` 经 `ReflectionTestUtils.setField` 注入 100 做阈值分流断言。
7. **`evictTaskId` 上抛** — 已裁决「记档延期」接受：管理写响应不回显 evictTaskId，维持现状（仅审计 oper_desc + 注册表可查），前端需要时再追加。
8. **续期/批量续期不踢人** — 已裁决「保持去踢」：renewUserRole/renewBatch 权限不变化（仅 end_time 原地变更），不触发 evict；已过期用户由到期调度器踢，重登自然获得续期角色。

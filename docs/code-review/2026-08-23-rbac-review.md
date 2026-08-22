# RBAC 权限体系 Code Review 报告

> 日期：2026-08-23
> 分支：AI
> 范围：`ea66438..870d4ad`（RBAC 完整权限体系 + sql/k8s 清单，121 文件，+11026 行）
> 方式：子代理审查，五个重点角度（data_scope 合理性 / 权限快照一致性 / 事务边界 / SQL 注入 / 审计 diff）
> 结论：0 Critical / 7 Important / 12 Minor；其中 2 项经用户决策定稿为延后/收窄处置（见「处置决策」）

---

## 严重度分级

| 级别 | 数量 | 说明 |
|------|------|------|
| 🔴 严重 | 0 | 无 SQL 注入点、无越权路径、无凭证泄露 |
| 🟠 高 | 7 | 正确性 / 数据完整性 / 安全默认值 / 过度设计 |
| 🟡 中 | 12 | 魔法值 / N+1 / 口径不一致 / 约定差异 |

---

## 审查重点结论

| 重点 | 结论 |
|------|------|
| SQL 注入 | 无风险。全部 Mapper XML 一律 `#{}` 参数绑定，排序字段白名单（`SORT_FIELDS`）+ 非法即 400，`last("FOR UPDATE")` 固定片段，`inSql/apply/exists` 危险操作符零使用 |
| 权限快照一致性 | 设计闭环：管理写操作统一走 `manageWrite`（事务提交 → evict → 审计），evict 阈值分流（同步/异步）+ Redis 抖动指数退避重试 + 失败审计兜底；仅 TTL 兜底方向与过期清理两个边角问题 |
| 事务边界 | 业务事务 / evict / 审计三段分离正确；`OperLogService` REQUIRES_NEW 独立事务，业务回滚也留痕、审计失败降级不阻断 |
| 审计 diff | **实现缺失且与定稿文档不符**——经用户决策移交 web 日志层面解决，见「处置决策 1」 |
| data_scope | 无部门表前提下属中度过度设计（死代码 + 静默降级）——经用户决策收窄，见「处置决策 2」 |

---

## 处置决策（用户定稿，2026-08-23）

### 决策 1：审计字段 diff 暂时忽略，改为 web 日志层面解决

- 原问题：`DiffUtils` 不存在，`RbacManageService` 审计仅静态文本 desc；`OperLogReq` 的 `requestMethod/requestUri/userAgent` 无调用方传值，`ums_oper_log` 的 `request_params/request_body/response_code/cost_time` 列闲置
- **处置**：本批不实现字段 diff；「改了什么」的追溯移交 web 日志层面（日志系统阶段 0，见 `docs/superpowers/specs/2026-08-17-log-system-design.md` 请求轨 + traceId 关联），`ums_oper_log` 维持「谁在何时做了什么动作」的动作级审计
- **遗留动作**：CLAUDE.md「本批字段 diff 在门面就地生成」表述与现状不符，需修正对齐（`doGetOldSnapshot` 钩子与 `DiffUtils` 记档保留，待 web 日志落地后复评）

### 决策 2：data_scope 收窄——仅实现管理员全部权限，其余皆为自身

- 原问题：`DataScopeEnum` 定义 5 级，3/4/5 依赖不存在的部门表；`DataScopeBuilder.applyDataScope` 只实现 ALL/SELF，DEPT/DEPT_AND_BELOW/CUSTOM **静默降级为 SELF 无任何告警**（配置与行为背离）；且 `applyDataScope` 生产代码零调用，整条消费链路为死代码
- **处置**：近期无部门表规划，data_scope 行为收窄为二分——SUPER_ADMIN → ALL（全部数据），其余所有角色 → SELF（仅本人）；消除静默降级（3/4/5 级写入入口拒绝或显式映射为 SELF 并告警），`DataScopeEnum` 与 DDL 注释同步收窄说明
- **遗留动作**：部门/数据域表落地时再恢复 3/4/5 级语义，届时 `DataScopeBuilder` 按实时模型（每次请求查 `ums_role.data_scope`）扩展

---

## 🟠 高（Important）

### 1. 审计字段 diff 未实现，与定稿文档不符

- 文件：`application/rbac/RbacManageService.java`、`application/rbac/OperLogService.java`、`application/rbac/OperLogReq.java`
- 处置：见「处置决策 1」（移交 web 日志层面，本批不实现）

### 2. 全字段覆盖 + DTO 可选字段 → null 覆盖风险

- 文件：`application/rbac/RbacManageService.java`（`updateRole` / `updatePermission`）
- `BeanCopyUtils.copy(dto, role, ignore...)` 原地覆盖，Spring `BeanUtils.copyProperties` 会复制 null 值。`RoleDTO.roleName` 无 `@NotBlank`，前端 PUT 只传 `roleCode + dataScope` 时：`role_name` 被覆盖为 null → DB NOT NULL 违例，请求 500 回滚；`remark` 等可空列被静默清空
- 修复方向：DTO 更新语义改为「非 null 才覆盖」，或对必填字段补校验

### 3. 授角色不校验 roleId 存在性 + 幂等语义不一致

- 文件：`application/rbac/RbacManageService.java`（`assignRolesBatch` / `replaceUserRoles` / `assignRole`）
- `ums_user_role` 无外键，可对不存在的 roleId 成功插入绑定（联表查角色时该行静默消失，数据已脏）
- `assignRole` 走普通 `insert`（非 INSERT IGNORE），批量授权中任一用户已有该角色即 `DuplicateKeyException` 整批回滚——与 `replaceRoles` 的 `insertIgnoreBatch` 幂等语义不一致
- 修复方向：授权前批量校验 roleId 存在且启用；统一幂等语义

### 4. data_scope 静默降级 + 死代码

- 文件：`infrastructure/security/DataScopeBuilder.java`、`domain/enums/DataScopeEnum.java`、`sql/rbac.sql`
- 处置：见「处置决策 2」（收窄为 SUPER_ADMIN → ALL、其余 → SELF）

### 5. `rbac.debug-enabled: true` 默认值不安全

- 文件：`src/main/resources/application.yaml`
- 基础配置默认 `true`，注释自认「生产必须 false」，但当前无 prod profile 覆盖。调试端点含踢任意用户（`/rbac/users/{id}/evict`）与任意用户权限查询（信息暴露）
- 修复方向：基础配置默认 `false`，dev profile 显式开启（secure-by-default）

### 6. evict 黑名单 TTL 兜底方向反了

- 文件：`application/rbac/EvictService.java`（`remainingTtlSeconds`）
- `expireTime == null` 时回落到 accessToken TTL（30min）：黑名单 30min 过期后，该会话 refreshToken 仍可经 refresh 签发带**旧权限快照**的新 accessToken——兜底应取保守大值（会话剩余期）而非最小值
- 当前 `createSession` 恒填 `expireTime=now+14d` 不可达，属潜在缺陷；另注释「TTL=token 剩余 exp」与实际「会话剩余期」语义不符（行为正确、注释误导）

### 7. 过期绑定永不清理

- 文件：`application/rbac/RbacRoleExpiryScheduler.java`
- 只踢人不删除/标记过期行：`ums_user_role` 过期绑定永久留存，每分钟全量重扫 + OFFSET 分页随时间退化，重复 SETEX 浪费 Redis 写
- 修复方向：踢人后物理删除过期行（关系表物理删除约定内），或扫描游标前移避免重复处理

---

## 🟡 中（Minor）

1. **`SUPER_ADMIN` 魔法字符串散落 6+ 处**（RbacManageService / RbacAuthzService / DataScopeBuilder / PermissionInterceptor / RbacQueryController / AuthService）——收口常量
2. **N+1 查询多处**：`toUserRoleVO` 循环内 `getById`、`effectivePermissions`/`loadPermCodes` 逐角色查询、`exportRoles` 逐角色查权限码——角色量小可接受，记档
3. **`OperLogService` 每次 `record` new 一个 `TransactionTemplate`**（可初始化一次复用）；`username` 列落 `String.valueOf(userId)` 数字而非账号快照，与表注释「操作用户账号快照」不符
4. **`estimateTotal` 口径不一致**：`countUserIdsByRoleId` 数全部绑定，实际 evict 只踢联表在线会话用户——仅影响同步/异步分流决策，不影响正确性
5. **`updatePermissionStatus` 用户集采集在事务之前**：读写间隙并发新增绑定会漏踢（30min TTL 兜底，风险低）
6. **`cloneRole` do-while 查重无循环上限**；`importRoles` 逐条独立事务，中途失败已导入项不回滚且报告无失败条目
7. **`createPermission` 的 `permissionCode` 无三段式格式校验**（`PermissionCodeValidator` 只在注解扫描路径使用），手工可建任意格式权限码
8. **`expiring` 分页 `total` 为下界估算**（已注释说明，前端需知悉）
9. **Swagger 路径在白名单免认证**（`/v3/api-docs/**`、`/swagger-ui/**`）——dev 可接受，生产部署须注意
10. **`UmsPermission.status` 复用 `RoleStatusEnum`**，命名易混淆（资源状态 vs 角色状态）
11. **`EvictTaskRegistry` 溢出淘汰兜底分支可能移除 PENDING/RUNNING 任务记录**（任务仍执行，仅进度不可查）
12. **`OperTypeEnum.EVICT_USER=9` 与 `oper_log.sql` 表注释（只列到 8）口径差异**——代码已自注，建议顺手补 DDL 注释

---

## 亮点

1. **安全默认**：`PermissionInterceptor` 对 `/rbac/**` 未标注解接口默认 403（防漏标注记）；SUPER_ADMIN 禁停用/禁清空权限/内置角色禁删改守卫收口门面一处；perms claim 超上限 safe-degrade 置空（宁拒勿越权）
2. **evict 黑名单 TTL 取会话剩余期**（正常路径）：彻底封死会话——防黑名单过期后经 refresh 用旧快照复活
3. **权限同步防误停用**：启动只新增/复活，残留停用强制手动 `POST /rbac/permissions/sync` + dry-run 预览，`DuplicateKeyException` 捕获防并发重复注册
4. **审计事务隔离正确**：REQUIRES_NEW 独立事务，失败路径同样审计（error_msg 入失败上下文）
5. **物理删除关系表**（`ums_user_role`/`ums_role_permission`）避开逻辑删除 + 唯一键冲突，设计决策有充分论证
6. **测试覆盖较全**：EvictServiceTest（分页/重试/中断恢复）、PermissionSyncServiceTest、RbacManageServiceTest、PermissionInterceptorTest、DataScopeBuilderTest、RbacPermissionTreeTest
7. **「功能权限 JWT 快照 vs 数据权限实时」两模型差异**在 CLAUDE.md、javadoc、注释中反复强调，防混淆意识到位

---

## 结论

**总体评价：架构质量良好，无 Critical，可合并；建议按下列优先级处理 Important 项。**

优先级排序（处置决策已定稿者除外）：

1. ~~审计 diff~~ → 处置决策 1（移交 web 日志层面，本批不动）
2. null 覆盖（Important 2）
3. roleId 校验 + 幂等统一（Important 3）
4. debug 默认值翻转（Important 5）
5. ~~data_scope~~ → 处置决策 2（收窄为管理员 ALL / 其余 SELF）
6. evict TTL 兜底方向（Important 6）
7. 过期绑定清理（Important 7）

已知局限（evict-batch 内存实现重启丢失、集群无分布式锁）已在代码与文档中如实记档，不计入缺陷。

关键文件：`src/main/java/com/sanye/strategy/application/rbac/RbacManageService.java`、`application/rbac/EvictService.java`、`application/rbac/OperLogService.java`、`infrastructure/security/DataScopeBuilder.java`、`infrastructure/interceptor/PermissionInterceptor.java`、`src/main/resources/application.yaml`。

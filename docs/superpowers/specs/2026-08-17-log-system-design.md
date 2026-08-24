# Web 日志系统架构设计

> 日期：2026-08-17
> 状态：设计定稿（阶段0 未实施）
> 关联：CLAUDE.md「已知缺陷与待办」— 操作/审计日志 + 请求/WEB 日志统一设计

## 一、目标与范围

### 1.1 目标

日志贯穿「产生 → 采集 → 传输 → 存储 → 检索 → 告警」全链路，同时满足三类诉求：

1. **实时排障**：按 traceId / userId / bizId 关联整条请求链路
2. **成本控制**：冷热分离、分级保留、采样限速
3. **合规审计**：审计日志隔离、不可篡改、分级授权

### 1.2 范围

**IN（框架内）**：结构化输出、分类路由、脱敏、链路 ID、采样限速、缓冲重试、分层保留、归档恢复、索引模型、告警规则、字段 diff 引擎、不可篡改通道、操作者身份。

**OUT（边界外邻居）**：

- 指标监控（Prometheus / Micrometer metrics）——时序域，不混日志
- APM 性能剖析
- 告警通知渠道（钉钉/邮件）——框架只发事件，渠道外部
- 业务字段语义——框架不知道 userId 含义
- 审计业务规则（哪些操作要记）——业务层决定，框架只给通道

### 1.3 原则（铁律）

- 业务只写本地文件，**不阻塞、不直写 ES/MinIO**
- 脱敏产生端为主，落盘即安全
- info 全量、错误 100%、访问可采样
- 业务永不阻塞，日志至少留本地

---

## 二、总体架构

### 2.1 链路

```
Logback(应用内, 本地文件·结构化 JSON) → Vector(DaemonSet) → Kafka(已部署·缓冲硬化) → 按 category 路由 → ES(热检索) / MinIO(温存·归档·审计 WORM)
                                                              ↓（Kafka 故障降级）
                                                    Vector 直出 ES/MinIO（过渡/兜底，恢复后切回）
```

### 2.2 组件映射

| 层 | 组件 | 要点 |
|----|------|------|
| 产生端 | Logback + `logstash-logback-encoder` | JSON 结构化、分类分文件、脱敏产生端、错误独立 |
| 链路 ID | **Micrometer Tracing** | Boot 4 `io.micrometer.tracing`，自动入 MDC，W3C `traceparent` 传播 |
| 采集 | Vector DaemonSet | K8S hostPath 读文件，磁盘 buffer + checkpoint 续采 |
| 传输 | Kafka 主链路 | 已部署，代码未接入；Vector 直出 ES/MinIO 为接入前过渡与 Kafka 故障降级，切换只改 Vector output 配置一处 |
| 存储 | ES 热 + MinIO 温/归档 | ES ILM 热删；冷归档 = ES snapshot repo → MinIO |
| 检索 | ES | traceId/userId/bizId 关联 |
| 审计 | `ums_oper_log` 视图副本 + 事件轨权威链 | diff + target + trace + operatorType，MinIO Object Lock |
| 告警 | 后置 | Kafka 接入前简单方案，接入后消费流，不查询 ES 实时告警 |

> 部署连接信息（ES/MinIO/Kafka dev 端点与凭据）见 CLAUDE.md「技术栈」章节，本文档不重复维护，避免双份漂移。

---

## 三、逻辑三轨·物理一

**逻辑三轨**（消费语义隔离），**物理一条管道**（一套采集/传输/存储集群，逻辑隔离靠 `category` 字段 + 路由配置）。

| 轨 | 分类 | 特性 | 存储落点 |
|----|------|------|----------|
| **请求轨** | 接入访问、中间件 | 高吞吐、可采样、短保留 | ES `request-*` |
| **业务轨** | 业务、错误 | 全量、中长保留 | ES `business-*` |
| **事件轨** | 安全事件、操作审计 | 低频、高价值、WORM | ES `event-*` + MinIO WORM bucket |

物理一后果（明确接受）：审计不可篡改保障点从「产生端独立文件」移到「存储层 WORM + 采集及时投递」。本地缓冲窗（Vector 未投递前）存在，接受条件：事件量小、投递快、MinIO 侧锁死。

---

## 四、日志分类（6 类）与保留策略

### 4.1 分类表

| 类 | 触发 | 消费方 | ES 热 | MinIO 温 | 归档/删 | 采样 |
|----|------|--------|-------|----------|---------|------|
| 接入访问 | 请求 | 运维排查 | 3~7 天 | 30 天 | 删 | 可采样 |
| 业务 | 业务操作 | 业务排查 | 7~15 天 | 60 天 | 90 天 | 全量 |
| 错误 | 系统异常 | 故障定位 | 15~30 天 | 90 天 | 90 天 | 全量 |
| 中间件 | 慢查询/依赖 | 性能排查 | 7 天 | 30 天 | 30 天 | 可采样 |
| **安全事件** | 认证/授权/威胁尝试 | 告警引擎 | 30 天 | 180 天+ | 关键事件 WORM | 全量 |
| **操作审计** | 业务变更 | 合规审计 | 30 天 | 180 天+ | **版本锁，不删** | 全量 |

### 4.2 安全事件子类型（`fields.securityType`）

- `authn`：登录成功/失败、MFA 结果、token 校验失败
- `authz`：越权 403、权限不足访问
- `account`：冻结/锁定/解锁/注销
- `credential`：改密/绑定/重置（凭据变更）
- `anomaly`：异常 IP、风控触发

### 4.3 边界重叠双记规则

关键安全操作（改密/MFA 绑定等）既属操作审计（业务 diff）也属安全事件（凭据变更）：**双轨各记**——审计记字段 diff，安全记事件，traceId 关联。其余按域单记。

---

## 五、组件设计

### 5.1 产生端

- Logback 结构化 JSON 输出，`logstash-logback-encoder`
- 分类分文件（6 类），错误独立 appender
- Micrometer Tracing 自动注入 traceId 到 MDC
- 脱敏产生端完成（见第六章）
- 限制单条日志大小，禁止大报文完整输出

### 5.2 采集与传输

- Vector DaemonSet，每节点一个，hostPath 读应用日志文件
- **禁用 emptyDir**（Pod 重建清空丢日志）；hostPath 保留
- 磁盘 buffer + checkpoint 续采（acknowledgements），Vector 重启不丢
- 目标链路：Vector(file source) → Kafka → 消费端写 ES/MinIO；Kafka 提供缓冲硬化、削峰、ES 故障期间缓存（消费端形态见待决事项）
- Kafka 接入前过渡 / Kafka 故障降级：Vector source → 多 sink（`elasticsearch` + `aws_s3`，endpoint 指 MinIO，`force_path_style`）；切换只改 Vector output 配置一处

### 5.3 存储

- **热**：ES，ILM 按分类自动轮转热删
- **温**：MinIO，保存 30~90 天，可回捞
- **冷归档**：ES snapshot repository → MinIO（单节点 ES 无 warm/cold tier，冷归档走 snapshot 而非 ES 温冷 phase）
- **审计/安全**：事件轨独立 ES index + 独立 MinIO bucket（Object Lock/WORM）

### 5.4 检索

- ES 全文检索，按 traceId 关联调用链
- 错误日志独立索引，延长生命周期
- 索引生命周期自动化（ILM），避免人工维护

### 5.5 治理与告警

- 告警只消费 error/warn + 安全事件流，**不直接查询 ES 做实时告警**
- Kafka 接入后：流式处理（Kafka + 脚本/Flink）触发告警
- 权限控制：审计日志分级授权访问
- 成本控制：采样、限速、磁盘水位告警、日志质量治理

---

## 六、脱敏框架

### 6.1 分层双保险

| 道 | 位置 | 职责 | 边界 |
|----|------|------|------|
| 第一道 | 产生端字段级排除为主 | 已知敏感字段结构性排除/掩码 | 主防线，管已知、命名规范字段 |
| 第二道 | 传输端 PII 正则兜底 | 捕获未知、动态、自由文本泄露的 PII | 只跑请求/业务轨，**禁碰事件轨结构化字段** |

- 字段黑名单主 + PII 正则兜底，正则**不能替代**黑名单（正则误杀/漏杀；嵌套深层 Vector 解析成本高）
- 自由文本偶然打印身份证靠正则救场，不能指望它挡住业务主动输出

### 6.2 敏感字段两级语义

| 语义 | 适用 | 策略 |
|------|------|------|
| **剔除** | 凭据类：`password\|secret\|token\|hash\|salt` | 值完全移除，`{"field":"passwordHash","op":"changed"}` 占位 |
| **掩码** | PII 类：phone/email/idcard | 部分显示保统计（手机号掩中段、IP 掩末段） |

### 6.3 IP 分级策略（产生端，按 `securityType` 分支）

| 事件类型 | IP 处理 | 理由 |
|----------|---------|------|
| 高威胁安全事件（authz 越权 / anomaly 风控 / account 锁定） | **完整原始 IP 保留**，进 WORM | 攻击溯源取证刚需 |
| 普通登录事件（登录成功/普通失败） | 末段掩码（`192.168.1.***`），保留网段抹主机位 | 兼顾统计与隐私 |
| 请求轨接入访问 | 末段掩码 | 非安全分析场景 |

IP 掩码不是通用脱敏规则，属安全事件域业务策略，**产生端完成**，Vector 不做动态修改（高威胁事件若在 Vector 掩码会丢原始取证 IP）。

### 6.4 配置单一源

- 一份敏感字段黑名单配置，日志脱敏与审计 diff **共用同一份**
- 修改一处，普通日志、审计 diff 同时生效，避免配置漂移
- 白名单例外：个别业务字段审计需完整留存时，黑名单之上增加白名单覆盖

---

## 七、审计设计

### 7.1 字段 diff

- `DiffUtils` 纯 POJO diff：忽略 id/审计字段（createTime/updateTime/createUserId/updateUserId/deleted），忽略 new 为 null 或与 old 相同（兼容部分更新）
- 输出 schema：

```json
[
  {"field": "roleName", "old": "运营", "new": "运营专员"},
  {"field": "roleIds", "op": "add", "ids": [1,2]},
  {"field": "roleIds", "op": "remove", "ids": [3]}
]
```

- 关联集 diff（物理删除关联表，无行快照）：变更前集合 vs 变更后集合，记 added/removed
- `change_diff` 存 JSON 字符串（规避 Spring Boot 4 Jackson 3 与 MP Jackson 2 冲突）

### 7.2 操作对象元数据

- `target_entity`：实体/表名（如 `ums_user`）
- `target_id`：被操作数据主键 ID
- target 元数据是 diff 前置——无 target_id 无法关联变更，必须同批

### 7.3 traceId 关联

- `trace_id` 从当前 trace/MDC 取，**不信任调用方传**（同 userId/IP 防伪造原则）
- 审计记录通过 traceId 联动 ES/MinIO 业务日志，一键串联整条请求链路

### 7.4 操作者类型

- `operator_type`：1 人工用户（有 UserContext）、2 系统后台任务（无 UserContext）
- **逻辑推导不手传**：UserContext 有值 → 1，空 → 2

### 7.5 敏感字段审计（密码剔除）

核心语义：**敏感字段审计「发生」，非审计「内容」**。密码修改的"内容"就是"哈希值被替换"这个事实，值不可读、泄露无益有害。

| 层 | 处理 |
|----|------|
| 值剔除 | 凭据字段值永不出现在任何日志/diff，`changed` 占位 |
| 操作级事件 | 安全事件 `credential.change` 单记：谁/何时/IP/目标账号/验证结果 |
| 合规满足 | 修改事实 + 操作者 + 时间 + target，等保"修改留痕"达标 |

### 7.6 WORM 权威链

- `ums_oper_log` 是**视图副本**（MySQL 侧可被 DBA 手动改删，不能作合规证据源）
- **权威证据**：`audit.log` → Vector → MinIO Object Lock（WORM）
- MySQL 侧减损：应用账号仅 INSERT/SELECT 权限，禁 DML/truncate
- 审计 180 天+，不自动删除

---

## 八、存储策略核心要点

1. **冷热分离 + 分级保留**：每分类独立保留周期（见 4.1）
2. **审计特殊处理**：独立索引/独立 bucket，WORM 或版本锁，保留策略不与其它日志共用
3. **成本控制**：访问层采样、单实例限速、本地磁盘滚动清理、磁盘水位告警
4. **容灾降级**：
   - 管道任意环节挂 → 上游缓冲 → 本地文件兜底 → 恢复补发
   - ES 故障：Kafka 缓存，恢复后继续消费
   - Kafka 故障：Vector 切直出 ES/MinIO + 本地文件留存，恢复后切回补发
   - 机器销毁：MinIO 已同步日志，审计独立 WORM 保存

---

## 九、反模式禁止（写入约束）

- ❌ 脱敏全部交给 Vector，业务只输出明文
- ❌ 审计 diff 独立维护敏感字段清单，与日志脱敏配置分离
- ❌ 安全事件全部 IP 掩码，丢失攻击溯源原始地址
- ❌ 传输端脱敏作合规证据依据（明文磁盘窗口期，快照/备份/磁盘镜像均可能捕获明文）
- ❌ Vector 正则处理安全事件、审计事件的结构化敏感字段
- ❌ 业务直写 ES/MinIO

---

## 十、阶段规划

| 阶段 | 内容 | 状态 |
|------|------|------|
| 0 | 产生端：Tracing + logback JSON 分文件 + 脱敏 + 审计 DDL/代码改造 | **未开始** |
| 1 | Vector 采集 + Kafka 传输 + 消费写 ES/MinIO（Kafka 接入前以直出过渡） | 可部署 |
| 2 | ES 检索 + ILM | 已部署（ES 8.13.4，ILM 策略待配置） |
| 3 | MinIO 归档 + 审计锁 | 已部署（MinIO，Object Lock 待配置） |
| 4 | Kafka 硬化传输 | 已部署，代码未接入 |

---

## 十一、待决事项

1. 告警具体方案（Kafka 已部署，接入前的过渡方案与接入后的消费方案）
2. 字段 diff 触发范围明细（RBAC 管理面 9 类 UPDATE 已盘点，见下）——**已定稿**（2026-08-24，随阶段0 diff 复活落地）：执行计划 Task 9 共 13 处——9 类 UPDATE（逐字段 diffBean / 关联集 diffIdSet）+ 权限集 GRANT/REVOKE 4 处（added/removed 权限 id）+ CREATE/DELETE 仅补 target；见 `docs/superpowers/plans/2026-08-23-log-phase0-producer.md`
3. K8S Vector 资源与配置细节
4. IPv6 掩码规则（`192.168.1.***` 仅覆盖 IPv4，IPv6 掩码位数待定）
5. Kafka 消费端形态：Vector kafka source 二段式 vs 独立消费者进程
6. Kafka 日志 topic 规划（按三轨分 topic 或统一 topic + category 路由，SASL 凭据经环境变量注入参照 CLAUDE.md 模式）

### 附：字段 diff 范围（RBAC 管理面）

| 操作 | target | diff 内容 |
|------|--------|-----------|
| `updateRole` | `ums_role` id | roleCode/roleName/dataScope/sortOrder/remark/status 逐字段 |
| `updateRoleStatus` | `ums_role` id | status old→new |
| `updatePermission` | `ums_permission` id | 字段逐项 |
| `updatePermissionStatus` | `ums_permission` id | status |
| `renewUserRole` | `ums_user_role` bindId | end_time old→new |
| `renewBatch` | `ums_user_role` bindIds | 每条 end_time |
| `replaceUserRoles` | `ums_user_role` userId | roleIds 集合 added/removed |
| `assignRolesBatch` | `ums_user_role` 每 userId | 每用户 added roleIds |
| `removeUserRole` | `ums_user_role` userId | removed roleId |

- CREATE/DELETE 无 diff，补 target 元数据
- 权限集 GRANT/REVOKE 记 added/removed 权限 id
- evict/定时扫描不 diff
- 批量逐条（等保要变更内容），desc 存摘要
- **门面就地 diff**（`getById` 已有旧值），`doGetOldSnapshot` 模板钩子留待全量 update 审计扩展

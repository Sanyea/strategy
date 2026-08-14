# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 核心约束

1. **严格遵循阿里巴巴 Java 开发规范**（命名、注释、异常处理、日志、集合操作等），代码风格以阿里规范为准。
2. **设计模式实现必须附带说明**，包括：
   - 角色说明（每个参与类的职责）
   - 优缺点分析
   - UML 类图/时序图（用 Mermaid 或 ASCII 图描述）
3. **不确定的 API 定义主动询问** — 不熟悉的第三方库或框架 API，先调用 Context7 查询最新文档，不凭空编造。发现接口定义不明确时，停下来向用户确认，不猜测。
4. **禁止凭空编造 API** — 所有调用的方法、类、注解必须在官方文档或项目已有代码中存在依据。
5. **注释与过时 API 规范**：
   - 方法内部单行注释：在被注释语句**上方另起一行**，使用 `//`，与代码对齐
   - 方法内部多行注释：使用 `/* */`，与代码对齐
   - **禁止行尾注释**（被注释语句后同行追加 `//` 或 `/* */`）
   - **禁止使用过时（`@Deprecated`）的类或方法**

## 工作方式（模型分工）

按任务类型区分思考与执行强度：

| 任务类型 | 模式 | 说明 |
|---------|------|------|
| 整体方案、架构推演、设计决策、技术选型 | **深度思考**（慢思考，充分权衡） | 走查方案可行性、多方案对比、推演边界与异常路径、评估架构演进方向，得出结论后再动手 |
| 代码编写、文件修改、批量执行任务 | **快速模型**（高效执行） | 在已定方案/架构指导下直接落地：写代码、改文件、跑批量操作，避免反复试探 |

**流程约定：**
- 遇到新需求或架构调整：先用深度思考梳理整体方案与分层设计，确认后再进入快速执行。
- 纯增量/机械性修改（已知方案下的编码、重构、批处理）：直接走快速模式，不重复推演。
- 快速执行中若发现方案假设不成立或接口定义不明，回到深度思考确认，不硬猜。

## 分支与提交规范

- **AI 开发与提交仅在 `AI` 分支进行**：代码编写、文件修改、`git commit` 一律在 `AI` 分支执行，禁止直接在 `dev`（及 `main`）分支开发或提交。
- **合并到 `dev` 需用户确认**：`AI` 分支开发完成后，等待用户确认当前分支开发无误，再由 AI 将 `AI` 分支合并到 `dev`。
- 合并后由用户决定是否删除 `AI` 分支或新建后续 `AI` 分支，AI 不擅自操作。

## 构建与运行

```bash
# 构建（跳过测试）
mvn clean package -DskipTests

# 运行全部测试
mvn test

# 运行单个测试类
mvn test -Dtest=StrategyApplicationTests

# 以 dev 配置启动（默认）
mvn spring-boot:run

# 以 test 配置启动
mvn spring-boot:run -Dspring-boot.run.profiles=test
```

> 注：`mvnw` 缺少 `.mvn/wrapper/maven-wrapper.properties`，当前不可用。统一使用本机 Maven：
> - Maven 安装目录：`D:\Tool\apache-maven-3.8.8`
> - 本地仓库：`D:\Tool\apache-maven-3.8.8\localRepository`（依赖缓存所在目录，可在 `D:\Tool\apache-maven-3.8.8\conf\settings.xml` 中调整）
> - 命令：将 Maven 加入 `PATH` 后直接使用 `mvn`，或调用 `D:\Tool\apache-maven-3.8.8\bin\mvn`

## 技术栈

- Java 21，Spring Boot 4.1.0，Maven
- MyBatis-Plus 3.5.15（`com.baomidou:mybatis-plus-spring-boot4-starter`）— ORM，支持代码生成。**须用 boot4-starter 匹配 Spring Boot 4**：boot3-starter 的自动装配在 Boot 4 下 `@ConditionalOnSingleCandidate(DataSource)` 评估过早，SqlSessionFactory 不创建
- MySQL，通过 `mysql-connector-j`
- Redis（spring-data-redis + Lettuce）— 仅承载两类瞬态认证数据：accessToken jti 吊销黑名单（秒级冻结）+ MFA 挑战凭证 tempToken（5min TTL、GETDEL 单次消费），不承载会话/业务缓存
- JWT（jjwt 0.12.6）— accessToken HS256 对称签名（签发与验签均显式钉死 `Jwts.SIG.HS256`，不随密钥长度推断），密钥 ≥32 字节从 `jwt.secret` 注入（`application.yaml`，生产经 `JWT_SECRET` 环境变量覆盖，不入库不进代码），TTL 30min；`jti` claim 为 String（RFC 7519，jjwt 拒绝数值型 jti），`String.valueOf(jti)` 落串、消费方 `Long.valueOf(claims.get("jti", String.class))` 还原会话行 id
- Lombok（maven-compiler-plugin 中配置了注解处理器）
- Spring Boot Actuator，用于健康检查/监控端点

## 架构

### DDD 分层设计

项目采用 **DDD 分层架构（分层优先）**：`interfaces → application → domain → infrastructure`，外加 `common` 共享内核。依赖方向单向向下，层内按能力（user/auth/device 等）分子包。当前系统为单一「用户中心」有界上下文；后续 strategy 业务新增上下文时，在各层内追加对应能力子包。

- **interfaces**（接口层）：Controller + 入参 DTO + 出参 VO。只做参数接收/校验/装配，业务全部下沉 application。依赖 application + common。
- **application**（应用层）：应用服务（用例编排、跨表事务、门面）。依赖 domain + common。
- **domain**（领域层）：实体（`domain/user/entity`）、业务枚举（`domain/enums`）、数据访问契约（`domain/user/repository`，原 service 接口）。纯 POJO，零框架依赖。
- **infrastructure**（基础设施层）：持久化（PO/Mapper/桥接实现）、安全（JWT/密码/OTP/UserContext）、Redis、拦截器、配置。实现 domain 契约，框架耦合收口于此。
- **common**（共享内核）：DIP 抽象（`common/base`、`common/model`）、统一响应/异常、工具。零业务语义，被各层依赖。

**核心原则（DIP 不变）：**
- Controller 依赖 `IBaseService<T>`（顶层接口），不依赖任何框架类型
- application 服务依赖 domain 的数据访问契约（原 `UmsXxxService` 接口），不直接使用 MyBatis-Plus 的 `QueryWrapper` / `Page`
- MP 行为耦合收口两处：`MpBaseServiceImpl`（CRUD 桥接，`common/base`）+ `infrastructure/config` 的枚举 `DeleteFlagEnumTypeHandler`（实体基类层枚举持久化适配）；枚举映射为声明式例外，见「实体与持久化对象（PO）双层分离」
- DTO 组合分页参数，禁止 DTO 继承分页对象

### 自定义抽象层（零框架依赖，common/base 与 common/model）

#### 查询条件抽象

| 接口/类 | 职责 |
|---------|------|
| `IQueryCondition` | 条件元数据：`getColumn()` + `getOperator()` + `getValue()` |
| `IWrapper<T>` | 查询条件构建接口，链式调用，与 ORM 无关 |
| `AbstractWrapper<T>` | 条件存储基类，维护 `List<IQueryCondition>` |
| `DefaultQueryWrapper<T>` | 基础查询实现：eq/ne/gt/ge/lt/le/like/between/in/orderBy 等；复杂逻辑（or/and/nested/apply/exists/notExists/select/last）继承自 `AbstractWrapper` |

`IWrapper<T>` 方法签名覆盖：比较（eq/ne/gt/ge/lt/le）、模糊（like/notLike/likeLeft/likeRight）、范围（between/notBetween）、空值（isNull/isNotNull）、集合（in/notIn/inSql/notInSql）、分组聚合（groupBy/having）、排序（orderByAsc/orderByDesc/orderBy）、逻辑（or/and/nested/apply）、存在性（exists/notExists）、其他（select/last）。

`IQueryCondition` 子类型（内部类）：`Condition`（普通）、`OrCondition`、`NestedCondition`（递归包含子条件列表）、`ExistsCondition`、`AndCondition`、`ApplyCondition`。

#### 分页抽象

| 接口/类 | 职责 |
|---------|------|
| `IBasePage<T>` | 分页契约：`getRecords()`/`getTotal()`/`getSize()`/`getCurrent()`，默认方法 `offset()`/`getPages()`/`convert()` |
| `BasePage<T>` | 基础分页实现，存储 records/total/size/current |
| `BasePageDTO<T>` | 扩展 `BasePage`，添加工厂方法 `of()` 和业务方法 `isHasMore()` |

### 服务层（数据访问契约 → domain，桥接实现 → infrastructure）

#### 类层次

```
IBaseService<T>                    ← 接口（common/base）：10 个 CRUD 方法，依赖 IWrapper/IBasePage
    ↑
IService<T>                        ← 接口（common/base）：+ 4 个批量方法
    ↑ (implements)
AbstractBaseService<T>             ← 抽象类（common/base）：模板方法 + doXXX 抽象钩子，零 MP 依赖
    ↑ (extends)
MpBaseServiceImpl<P, M, T>         ← MP 桥接实现（common/base）：IWrapper→QueryWrapper，实体↔PO 转换，框架耦合唯一处
    ↑ (extends)                      （P extends BasePO，M extends BaseMapper<P>，T extends SimpleBaseEntity）
XxxServiceImpl                     ← 业务 Service 实现（infrastructure/persistence/impl，实现 toPO/toEntity）
```

| 角色 | 类 | 位置 | 职责 |
|------|-----|------|------|
| 顶层抽象 | `IBaseService<T>` | common/base | 定义 CRUD 契约，10 个方法 |
| 批量契约 | `IService<T>` | common/base | + `insertBatch`/`updateBatch`/`deleteBatch`/`saveOrUpdateBatch` |
| 模板骨架 | `AbstractBaseService<T>` | common/base | 纯 POJO：模板方法（beforeInsert 等钩子）+ doXXX 抽象方法 + `doInTransaction` 事务钩子，零框架依赖 |
| MP 适配器 | `MpBaseServiceImpl<P, M, T>` | common/base | 实现 doXXX，IWrapper→QueryWrapper，IBasePage→Page，实体↔PO 转换（toPO/toEntity） |
| 数据访问契约 | `XxxService`（接口） | domain/user/repository | 业务侧依赖入口，继承 `IService<XxxEntity>` |
| 业务实现 | `XxxServiceImpl` | infrastructure/persistence/impl | 继承 MpBaseServiceImpl，实现 toPO/toEntity，添加业务逻辑 |

#### IBaseService<T> 方法定义

```java
boolean insert(T entity);
boolean updateById(T entity);
boolean deleteById(Serializable id);
T getById(Serializable id);
List<T> listByIds(Collection<? extends Serializable> ids);
T getOne(IWrapper<T> queryWrapper);
List<T> list(IWrapper<T> queryWrapper);
long count(IWrapper<T> queryWrapper);
IBasePage<T> page(IBasePage<T> page, IWrapper<T> queryWrapper);
boolean saveOrUpdate(T entity);
```

#### IService<T> 方法定义

```java
// 继承 IBaseService<T> 的全部方法，新增：
boolean insertBatch(Collection<T> entityList);
boolean updateBatch(Collection<T> entityList);
boolean deleteBatch(Collection<? extends Serializable> idList);
boolean saveOrUpdateBatch(Collection<T> entityList);
```

#### AbstractBaseService<T> 模板方法

```java
// 子类实现 doXXX（零 MP 依赖）
protected abstract T doGetById(Serializable id);
protected abstract List<T> doListByIds(Collection<? extends Serializable> ids);
protected abstract T doGetOne(IWrapper<T> wrapper);
protected abstract List<T> doList(IWrapper<T> wrapper);
protected abstract long doCount(IWrapper<T> wrapper);
protected abstract IBasePage<T> doPage(IBasePage<T> page, IWrapper<T> wrapper);
protected abstract boolean doInsert(T entity);
protected abstract boolean doUpdateById(T entity);
protected abstract boolean doDeleteById(Serializable id);
protected abstract boolean doSaveOrUpdate(T entity);
protected abstract int doInsertBatch(Collection<T> entityList);                 // 返回实际新增行数
protected abstract int doUpdateBatch(Collection<T> entityList);                 // 返回实际受影响行数
protected abstract int doDeleteBatch(Collection<? extends Serializable> idList); // 返回实际受影响行数
protected abstract int doSaveOrUpdateBatch(Collection<T> entityList);            // 返回实际受影响行数

// 事务钩子（纯 POJO 声明，默认透传无事务；桥接层覆写为具体事务实现）
protected <R> R doInTransaction(Supplier<R> action) { return action.get(); }

// 钩子方法（子类按需覆盖）
protected void beforeInsert(T entity) {}                     protected void afterInsert(T entity) {}
protected void beforeUpdate(T entity) {}                     protected void afterUpdate(T entity) {}
protected void beforeInsertBatch(Collection<T> list) {}      protected void afterInsertBatch(Collection<T> list) {}
protected void beforeUpdateBatch(Collection<T> list) {}      protected void afterUpdateBatch(Collection<T> list) {}
protected void beforeDelete(Serializable id) {}              protected void afterDelete(Serializable id) {}
protected void beforeDeleteBatch(Collection<? extends Serializable> ids) {}  protected void afterDeleteBatch(Collection<? extends Serializable> ids) {}
protected void beforeSaveOrUpdate(T entity) {}               protected void afterSaveOrUpdate(T entity) {}
protected void beforeSaveOrUpdateBatch(Collection<T> list) {}  protected void afterSaveOrUpdateBatch(Collection<T> list) {}
```

**事务架构（设计 B：事务抽象钩子）：**

- `AbstractBaseService<T>` 纯 POJO，零框架依赖（不依赖 MyBatis-Plus，也不依赖 Spring）
- 批量入口（insertBatch/updateBatch/deleteBatch/saveOrUpdateBatch）经 `doInTransaction()` 钩子执行，默认透传（无事务）
- `MpBaseServiceImpl` 覆写 `doInTransaction`，用注入的共享 `TransactionTemplate` 编程式事务实现，任一操作失败整体回滚
- 共享 `TransactionTemplate` Bean 定义于 `infrastructure/config/TransactionConfig`（基于 `PlatformTransactionManager`）；门面聚合 Service（application 层）非 `AbstractBaseService` 子类、够不到 `doInTransaction` 钩子，跨表事务经注入该 Bean 编排（注册/登录等），事务 Bean 单一收口
- 替换事务实现（JTA/Atomikos/Seata）只换 `PlatformTransactionManager` Bean 或覆写钩子；禁用则保持基类默认透传
- 单元测试可直接实例化子类覆写钩子装假事务，无需启动 Spring

**日志（暂移除）：** 模板方法操作汇总日志（原 `@Slf4j` `log.info` 8 条）暂整体移除——钩子抛错会丢日志、且与操作/审计职责重叠。后续统一设计「操作/审计日志」（实体 + 字段前后值 + 业务结果）与「请求/WEB 日志」（全局异常已带请求方法/URI）两套方案，见「已知缺陷与待办」。`AbstractBaseService` 现保持纯 POJO 零日志依赖。

#### MpBaseServiceImpl<P extends SimpleBasePO, M extends BaseMapper<P>, T extends SimpleBaseEntity>（common/base）

- 继承 `AbstractBaseService<T>`
- 通过 `@Autowired` 注入 `M baseMapper`（Mapper 操作 PO）
- 抽象钩子 `toPO(T)` / `toEntity(P)`：实体↔PO 转换，子类实现（通常用 `BeanCopyUtils`）
- 实现所有 `doXXX` 方法：先转 PO，再委托给 MyBatis-Plus `BaseMapper` 方法，结果转回实体
- **插入主键回填**：`doInsert`/`doSaveOrUpdate`（新增分支）/`doInsertBatch` 经私有 `insertEntityWithBackfill` 委托，插入成功后把 MP 在 PO 上生成的雪花主键回填到实体（`entity.setId(po.getId())`）——否则业务侧 `entity.getId()` 为 null（注册经 initSecurity/initProfile/createSession 引用 user.id 触发 NOT NULL 违例，冒烟发现）
- 核心转换：`IWrapper<T>` → `QueryWrapper<P>`（遍历条件列表，switch 操作符映射）
- 分页转换：`IBasePage<T>` → `Page<P>`（传入），`IPage<P>` → `IBasePage<T>`（回填 + convert）

**toMpWrapper 完整操作符映射：**

| IWrapper 方法 | 操作符 | MP QueryWrapper 方法 |
|---------------|--------|---------------------|
| eq/ne/gt/ge/lt/le | eq/ne/gt/ge/lt/le | `qw.eq/ne/gt/ge/lt/le(column, value)` |
| like/notLike/likeLeft/likeRight | like/notLike/likeLeft/likeRight | `qw.like/notLike/likeLeft/likeRight(column, value)` |
| between/notBetween | between/notBetween | `qw.between/notBetween(column, val1, val2)` |
| isNull/isNotNull | isNull/isNotNull | `qw.isNull/isNotNull(column)` |
| in/notIn | in/notIn | `qw.in/notIn(column, values[])` |
| inSql/notInSql | inSql/notInSql | `qw.inSql/notInSql(column, sql)` |
| groupBy | groupBy | `qw.groupBy(columns[])` |
| having | having | `qw.having(sqlHaving, params[])` |
| orderByAsc/orderByDesc | orderByAsc/orderByDesc | `qw.orderByAsc/orderByDesc(Arrays.asList(columns))` |
| orderBy | orderBy | `qw.orderBy(true, asc, columns[0], columns[1..])`（空列跳过） |
| or() | or（挂载标记） | 标记下一条 WHERE 段条件，映射时 `qw.or()`；前导/末尾/非 WHERE 段前的 or() 自动消除（`AbstractWrapper` pendingOr 标记 + 桥接层 anyWhere 守卫） |
| and(sql, params) | and | `qw.apply(sql, params)`（MP 无 `and(String,Object...)`，原始 SQL AND 等价 apply，`{n}` 占位绑定） |
| nested(consumer) | nested | `qw.nested(w -> ...)` 递归映射子条件，括号包裹，可含 or()；无 WHERE 段子组（空/仅投影）整组忽略 |
| apply(sql, params) | apply | `qw.apply(sql, params)` |
| exists(sql, params) | exists | `qw.exists(sql, params)` |
| notExists(sql, params) | notExists | `qw.notExists(sql, params)` |
| select(columns) | select | `qw.select(true, Arrays.asList(columns))`（列名须为数据库字段名，用于排除敏感列；空投影抛 `IllegalArgumentException`） |
| last(sql) | last | `qw.last(sql)`（原样追加，仅限固定片段如 LIMIT/FOR UPDATE，禁止拼接用户输入；空片段抛 `IllegalArgumentException`） |

> 注意：操作符字符串与值形状为双份维护（AbstractWrapper 写、toMpWrapper 读），`groupBy/orderByAsc/orderByDesc` 的列名以哨兵字符串作为 `getColumn()`。比较/模糊操作符（eq/ne/gt/ge/lt/le/like*）传 null 视为未提供，条件跳过；`in/notIn` 传单 `Collection` 自动展平。`and/apply/exists/notExists` 的 SQL 参数用 `{0}{1}` 占位符绑定，由 MP 转义，禁止直接拼接用户输入；占位符缺参或原始 SQL 为空时构建期抛 `IllegalArgumentException`（fail-fast）。详见「已知缺陷与待办」。

### interfaces 层

#### BaseController<T, S, Q, V>（interfaces/common）

```java
public abstract class BaseController<
    T,                          // 实体（Domain 类）
    S extends IBaseService<T>,  // 顶层 Service 接口
    Q,                          // 请求 DTO（无父类约束）
    V                           // 响应 VO（无父类约束，不继承 Serializable）
>
```

**子类必须实现的抽象方法：**
- `toVO(T entity): V` — 实体 → VO 转换
- `buildQuery(Q query): IWrapper<T>` — DTO → 查询条件
- `buildPage(Q query): IBasePage<T>` — 从 DTO 提取分页参数

**BaseController 提供标准端点：**

| 端点 | 方法 | 说明 |
|------|------|------|
| `GET /{id}` | `getById(@PathVariable Long id): R<V>` | 单条查询 |
| `GET /page` | `page(@Valid Q query): R<IBasePage<V>>` | 分页查询 |

**子类自行实现：** `POST /`（新增）、`PUT /{id}`（更新）、`DELETE /{id}`（删除）。
原因：增删改含业务逻辑（密码加密、状态校验、权限检查），不适合通用模板。

#### 查询 DTO 设计

- DTO **组合**分页参数（`page`/`size`/`sortField`/`sortOrder`），**禁止继承**分页对象
- `Q` 无父类约束，分页载体由 `buildPage(Q)` 从 DTO 字段提取
- 对比：组合优于继承，DTO 与分页载体松耦合

#### 统一响应 R<T>

```java
package com.sanye.strategy.common.response;

public class R<T> {
    int code;           // 状态码，取自 ResultCode（200=成功，4xx 客户端错误，5xx 服务端错误）
    String message;     // 提示信息
    T data;             // 响应数据
    String timestamp;   // 响应时间，ISO-8601格式（Instant.now().toString()）

    static <T> R<T> ok(T data);                    // 成功
    static <T> R<T> ok();                          // 成功（无数据）
    static <T> R<T> fail(ResultCode resultCode);            // 失败（枚举默认提示语）
    static <T> R<T> fail(ResultCode resultCode, String message);  // 失败（自定义提示语）
    static <T> R<T> fail(ResultCode resultCode, String message, T data);  // 失败（自定义提示语 + 数据载荷，如 MFA 挑战凭证）
}
```

- 状态码枚举 `ResultCode`（`common.response`）：HTTP 语义对齐，分 2xx 成功 / 4xx 客户端错误 / 5xx 服务端错误三段，含 `isSuccess()`/`isClientError()`/`isServerError()` 分类谓词与 `of(int)` 查找。新增业务码在此扩展，禁止业务层散落魔法数字。
- 批1 新增 7 码（HTTP 归属）：`TOKEN_EXPIRED(401)`、`DEVICE_KICKED(401)`（本批仅注册状态码，批4 踢设备流程使用）、`MFA_CHALLENGE_EXPIRED(401)`、`ACCOUNT_LOCKED(403)`、`ACCOUNT_DISABLED(403)`、`MFA_REQUIRED(403)`、`ACCOUNT_DELETED(410)`。
- `timestamp` 使用 `java.time.Instant.now().toString()` 生成 ISO-8601 标准时间字符串（如 `2026-08-03T10:30:00.123Z`），不走毫秒时间戳。

#### 异常体系

| 类 | 职责 |
|----|------|
| `BizException`（`common/exception`） | 业务可控错误信号，携带 `ResultCode` + 可选 `Object payload`（`MFA_REQUIRED` 走此通道携带 `MfaChallengeVO`；payload 为 null 行为与现状一致），业务层抛出不需逐层声明 |
| `GlobalExceptionHandler`（`@RestControllerAdvice`） | 全局收敛：异常 → `R<T>` + 对应 HTTP 状态码，日志带请求方法/URI |

映射约定：
- `BizException` → 状态码推导 HTTP 状态（4xx/5xx 与 HTTP 语义对齐）；状态码缺失时防御性按 500
- 参数校验（`@Valid` / `ConstraintViolation` / `Bind` / 缺参 / 请求体解析）→ 400，错误信息取 `getAllErrors()`（含字段 + 类级别）
- 方法不支持 / 媒体类型不支持 / 文件超限 → 405 / 415 / 413
- 唯一键 / 数据完整性冲突 → 409；静态资源 → 404
- 兜底 `Exception` → 500，记完整堆栈，客户端只见通用提示「系统繁忙，请稍后重试」

约定：**业务可控错误抛 `BizException`**，不可控异常由兜底分支记录完整堆栈，禁止 catch 吞异常。项目不使用权限框架，暂无 401/403 异常处理（接入时补充）。

### 实体与持久化对象（PO）双层分离

遵循阿里开发规范，实体层（半充血模型）与持久化层（PO）分离，ORM 耦合全部隔离在 PO 层：

| 层级 | 基类 | 位置 | 说明 |
|---|---|---|---|
| 实体层（纯 POJO，零框架依赖） | `SimpleBaseEntity` | common/base | 全部领域实体继承：`id`、`deleted`（`DeleteFlagEnum`）、`createTime`、`updateTime` |
| 实体层 | `BaseEntity` extends `SimpleBaseEntity` | common/base | 核心业务表（用户、订单、商品等）：+`createUserId`、`updateUserId` |
| PO 层（MP 注解，Mapper 操作对象） | `SimpleBasePO` | common/base | 对应 `SimpleBaseEntity`：`@TableId(ASSIGN_ID)`、`@TableLogic`、`@TableField(fill)` |
| PO 层 | `BasePO` extends `SimpleBasePO` | common/base | 对应 `BaseEntity`：+审计人字段自动填充 |

**转换：** `MpBaseServiceImpl<P, M, T>` 桥接层通过抽象钩子 `toPO(T)` / `toEntity(P)` 完成实体↔PO 互转，子类用 `BeanCopyUtils.copy()`（`common.util`）实现。

**包位置：** 实体在 `domain/user/entity`，PO 在 `infrastructure/persistence/po`，一一对应（`UmsUser` ↔ `UmsUserPO`）。

**业务枚举：** 状态/类型/标记字段（`gender`、`userStatus`、`isVip`、`identityType` 等）抽成 `domain/enums` 包下枚举类，`@EnumValue` 标注映射码（Integer 或 String），MyBatis-Plus 自动完成 枚举↔DB 互转。实体与 PO **共用同一枚举类**，`BeanCopyUtils` 同名同类型直接复制，无额外转换。查找枚举用静态 `valueOf(code)`；String 码枚举（如 `IdentityTypeEnum`）因与内置 `valueOf(String)` 签名冲突，改用 `valueOfCode(code)`。

**实体基类层枚举（约定式解耦）：** 仅 `DeleteFlagEnum`（`common/base`，`SimpleBaseEntity`/`SimpleBasePO` 的 `deleted` 字段）走约定式彻底解耦——枚举只实现纯接口 `IPersistEnum<T>`（`getPersistValue()` 返回映射码，零框架依赖），**不使用** `@EnumValue`。持久化适配由 `infrastructure/config/DeleteFlagEnumTypeHandler` 承担：写库取 `getPersistValue()`，读库经 `DeleteFlagEnum.valueOf(code)` 还原；通过 `mybatis-plus.type-handlers-package` 扫描 + `@MappedTypes` 注册，PO 层字段声明与上层业务代码完全不动。新增实体基类层枚举时沿用此约定（实现 `IPersistEnum` + 补一个 TypeHandler）。

**自动填充：** `MetaObjectHandler` 已注册（`infrastructure/config/MybatisPlusConfig`），插入时填充 PO 的 `createTime`/`updateTime` 与 `deleted`（`DeleteFlagEnum.NOT_DELETED`，对应 PO 的 `@TableField(fill = INSERT)` 约定，不填则插入显式 NULL 触发 NOT NULL 违例——冒烟发现）；批1 起 `createUserId`/`updateUserId` 从 `UserContext` 填充（`infrastructure/security/UserContext`，`infrastructure/interceptor/TokenAuthInterceptor` 认证通过后已填充用户上下文），无上下文（定时任务/初始化脚本）落 NULL 不阻断。`updateTime` 更新时填充，`updateUserId` 有上下文时填充。

**判断规则：** 系统级表（登录设备、认证绑定、安全日志）继承 `SimpleBaseEntity`。有人工操作、需要追溯操作人的业务表继承 `BaseEntity`。对应 PO 同理选 `SimpleBasePO` / `BasePO`。

**物理删除关系表（约定例外，方案 2）：** `ums_user_role`/`ums_role_permission` 等纯关联表采用**物理删除**（无 `deleted` 列），**不继承** `SimpleBaseEntity`/`SimpleBasePO`——`MpBaseServiceImpl<P,M,T>` 泛型上限 `T extends SimpleBaseEntity` + `SimpleBasePO.@TableLogic deleted` 强制实体必有 `deleted`，物理删除表无法上车统一服务栈。处理三件套：
- 独立 PO（`@TableId(ASSIGN_ID)` + createTime/updateTime `@TableField(fill)`，无 deleted，不继承）
- domain 契约接口（**不继承** `IService`，方法按业务需求定义，如 `getRoleCodesByUserId`/`assignRole`）
- infrastructure 实现直连 Mapper：查走 XML 联表、写走 `BaseMapper.insert/updateById`
- 自动填充仍生效：`MetaObjectHandler.strictInsertFill` 按字段存在性填充，无 deleted 字段自动跳过
- 事务由调用方门面编排（application 层 `TransactionTemplate`），不依赖 `doInTransaction` 钩子

**禁止**为兼容框架给物理删除表补 `deleted` 列——逻辑删除 + 唯一键叠加会永久占用唯一键（解绑角色再绑同角色撞 `uk_*`，同 `ums_user.username`/`phone` 已知缺陷同源）。关系表业务面窄（绑定=行、解绑=删），窄契约足够，不为其拆双服务栈。

`deleted` 字段使用 `DeleteFlagEnum`（`NOT_DELETED=0`，`DELETED=1`），实现 `IPersistEnum<Integer>` 约定式映射，持久化由 `DeleteFlagEnumTypeHandler` 桥接（见上文）。MyBatis-Plus 的 `@TableLogic` 自动过滤已删除行。

### 认证体系（interfaces/auth + application/auth + infrastructure/security + infrastructure/redis，批1 已落地）

认证零 Spring Security：accessToken 无状态 JWT HS256（30min，claims 恒含 `type=ACCESS, userId, roles, jti, deviceId, exp`；`roles` 为生效角色码数组**快照**，签发时经 `ums_user_role` 联表查询、拦截器零 DB 查询，角色变更经 refresh 轮换 / jti 黑名单生效，最长滞后 30min；`jti` 为 String——RFC 7519 规定 jti 为 case-sensitive 字符串、jjwt 0.12.6 拒绝数值型，`JwtUtil` 签发 `String.valueOf(jti)`、拦截器 `Long.valueOf(claims.get("jti", String.class))` 还原会话行 id）；refreshToken 32B 不透明串，仅存 SHA-256 哈希于 `ums_user_login_device.refresh_token_hash`（14 天，轮换防重放）。跨表写（注册/登录成功段）经共享 `TransactionTemplate`（`infrastructure/config/TransactionConfig`）编排。

**防枚举范围（已知局限，批2 缓解）：** 登录「用户不存在」与「密码错误」统一 401「账号或密码错误」——该掩码仅覆盖 缺失用户 vs 密码错误 二分；已存在账号的状态分支（403 冻结/锁定、410 注销）仍会泄露账号存在性。防批量枚举的全局登录限流在批2 落地，批1 不改变该行为。

**认证管道（infrastructure/interceptor/TokenAuthInterceptor + infrastructure/config/WebMvcConfig）：** Spring MVC `HandlerInterceptor`（责任链），`WebMvcConfig` 注册（`/**` 排除白名单）。流程：白名单放行 → `Authorization: Bearer <accessToken>` 解析 → 验签（签发与验签均显式钉死 HS256，解析器注册表收敛单元素，HS384/HS512/RS/ES/PS/EdDSA/none 一律拒）→ `type=ACCESS` 强校验 → Redis `EXISTS jti:{jti}` 黑名单命中即拒（401 `TOKEN_EXPIRED`）→ 填充 `UserContext`（userId/roleCodes/jti/deviceId，ThreadLocal）→ `afterCompletion` 清除。白名单：`/auth/login, /auth/register, /auth/refresh, /auth/mfa/verify, /actuator/**, /error`。拦截器零 DB 查询（不逐请求查 userStatus——冻结/注销在签发时把关，即时吊销走黑名单）。

**Redis 双用途（瞬态认证数据，`infrastructure/redis`，均自动装配 `StringRedisTemplate`、Lettuce，不建独立 RedisConfig）：**
- `JtiBlacklistService`（键域 `jti:*`）：登出/踢设备/改密/冻结/注销写 `SETEX jti:{jti} ttl`，秒级冻结 accessToken。
- `ChallengeTokenService`（键域 `mfa:*`）：登录 mfa=1 分支签发 32B tempToken，`SETEX mfa:{tempToken} {userId}:{deviceId}:{loginType}:{channel} 300`（5min TTL，绑定账号+设备+登入方式+渠道，登入方式/渠道随挑战携带供 verifyMfa 建会话复用、不二次校验）；verify 时 `GETDEL` 原子单次消费（`ValueOperations.getAndDelete`），防重放/双消费竞态。
- 不承载会话/业务缓存（refresh 会话仍落库）。

**MFA 二次验证（挑战凭证反转）：** 登录步骤 5 密码校验对 + `mfa_status=1` → 签发 5min 挑战凭证随 403 `MFA_REQUIRED`（`BizException` payload 携带 `MfaChallengeVO{tempToken, expiresIn}`）返回，**DB 零写入**（不清计数/不建会话/不签 token）；`POST /auth/mfa/verify {tempToken, code, deviceInfo}` 不再验密码（密码因子已在登录步骤 5 校验，tempToken 即通过证明），verify 仅验 OTP——`GETDEL` 原子单次消费（不存在/已消费/过期 → 401 `MFA_CHALLENGE_EXPIRED`），OTP 错 = 挑战已消费（重试须重新登录），deviceId 与挑战绑定比对（防跨设备复用）。

**DTO/VO（interfaces/auth/dto + interfaces/auth/vo）：** `MfaVerifyDTO` = `{tempToken, code, deviceInfo}`（无 account/password，userId 由挑战绑定解出）；`MfaChallengeVO` = `{tempToken, expiresIn}`（仅随 403 MFA_REQUIRED 返回一次）。

### 包结构

```
com.sanye.strategy
├── interfaces/                  # 接口层：Controller + 入参 DTO + 出参 VO
│   ├── common/
│   │   └── BaseController               # Controller 抽象基类
│   ├── auth/                     # 认证接口（批1）
│   │   ├── AuthController
│   │   ├── dto/    RegisterDTO / LoginDTO / RefreshDTO / MfaVerifyDTO
│   │   └── vo/     TokenVO / MfaChallengeVO
│   └── rbac/                     # RBAC 管理接口（本批）
│       ├── controller/  RbacRoleController / RbacPermissionController / RbacUserRoleController
│       │               / RbacRolePermissionController / RbacQueryController / RbacDebugController
│       ├── dto/        RoleDTO / PermissionDTO / UserRoleAssignDTO / UserRoleBatchAssignDTO / RoleCloneDTO
│       │               / RoleImportItem / RolePermissionAssignDTO / RoleQueryDTO / PermissionQueryDTO
│       │               / StatusDTO / UserRoleExpiringQueryDTO / UserRoleRenewDTO / UserRoleRenewSingleDTO
│       └── vo/         RoleVO / PermissionVO / UserRoleVO / EvictTaskVO
│
├── application/                 # 应用层：用例编排、跨表事务、门面
│   ├── auth/
│   │   └── AuthService                  # 认证门面（注册/登录/刷新/登出/MFA 挑战凭证验证）
│   ├── device/
│   │   ├── DeviceService                # 设备/会话门面（ums_user_login_device 属主）
│   │   └── dto/    DeviceInfo
│   └── rbac/                     # RBAC 应用层（本批）
│       ├── RbacManageService            # 管理面门面（跨表事务 + 变更后自动 evict + 审计）
│       ├── PermissionSyncService        # 注解扫描注册 + 手动同步（dry-run 预览）
│       ├── RbacAuthzService             # 生效权限查询 / 调试
│       ├── EvictService / EvictTaskRegistry   # 踢人执行 + 异步任务注册表（内存实现，重启丢任务）
│       ├── OperLogService / OperLogReq  # 审计基础设施（REQUIRES_NEW 独立事务，失败降级）
│       ├── RbacRoleExpiryScheduler      # 角色到期定时扫描踢人
│       └── EvictPlan / SyncReport
│
├── domain/                      # 领域层：纯 POJO，零框架依赖
│   ├── user/
│   │   ├── entity/    UmsUser / UmsUserProfile / UmsUserAccountSecurity / UmsUserAuth / UmsUserLoginDevice / UmsRole
│   │   └── repository/  UmsUserService / UmsUserProfileService / UmsUserAccountSecurityService / UmsUserAuthService / UmsUserLoginDeviceService
│   │                       （数据访问契约接口，继承 common/base 的 IService<T>）
│   │                   UmsRoleService / UmsUserRoleService
│   │                       （UmsUserRoleService 为物理删除关系表自定义契约，不继承 IService，见「物理删除关系表」约定）
│   ├── rbac/                     # RBAC 领域（本批）
│   │   ├── entity/    UmsPermission / UmsRolePermission
│   │   └── repository/  UmsPermissionService（继承 IService<UmsPermission>）
│   │                   / UmsRolePermissionService（物理删除关系表自定义契约，不继承 IService，见「物理删除关系表」约定）
│   └── enums/                  # 业务枚举（UserStatusEnum / GenderEnum / RoleStatusEnum / DataScopeEnum / PermissionTypeEnum / OperTypeEnum / ...；UserTypeEnum 已废弃，用户类型迁移为 RBAC 角色）
│
├── infrastructure/              # 基础设施层：框架耦合收口
│   ├── persistence/
│   │   ├── po/     UmsUserPO / UmsUserProfilePO / UmsUserAccountSecurityPO / UmsUserAuthPO / UmsUserLoginDevicePO
│   │   │           UmsRolePO（继承 BasePO）/ UmsUserRolePO（独立，无 deleted，物理删除关系表）
│   │   │           UmsPermissionPO / UmsRolePermissionPO（独立，无 deleted，物理删除关系表）
│   │   │           UmsOperLogPO（独立，仅插入，无 deleted/update_time）
│   │   ├── mapper/ UmsUserMapper / UmsUserProfileMapper / UmsUserAccountSecurityMapper / UmsUserAuthMapper / UmsUserLoginDeviceMapper
│   │   │           UmsRoleMapper / UmsUserRoleMapper / UmsPermissionMapper / UmsRolePermissionMapper / UmsOperLogMapper
│   │   └── impl/   UmsUserServiceImpl / UmsUserProfileServiceImpl / UmsUserAccountSecurityServiceImpl / UmsUserAuthServiceImpl / UmsUserLoginDeviceServiceImpl
│   │               （继承 common/base 的 MpBaseServiceImpl）
│   │               UmsRoleServiceImpl（继承 MpBaseServiceImpl）/ UmsUserRoleServiceImpl（直连 Mapper，自定义契约）
│   │               UmsPermissionServiceImpl（继承 MpBaseServiceImpl）/ UmsRolePermissionServiceImpl（直连 Mapper，自定义契约）
│   ├── security/   JwtUtil（+perms claim）/ PasswordEncoder / TotpUtil / UserContext（+permCodes）
│   │               RequiresPermission / NoPermissionRequired / PermissionCodeValidator / DataScopeBuilder
│   ├── redis/      JtiBlacklistService（jti:*）/ ChallengeTokenService（mfa:*）
│   ├── interceptor/ TokenAuthInterceptor / PermissionInterceptor（责任链新环，注册于 TokenAuthInterceptor 之后）
│   └── config/     MybatisPlusConfig / DeleteFlagEnumTypeHandler / TransactionConfig / WebMvcConfig
│                   PermissionScanRegistrar / SchedulingConfig
│
└── common/                     # 共享内核：零业务语义，被各层依赖
    ├── base/       IBaseService / IService / AbstractBaseService / MpBaseServiceImpl /
    │               IWrapper / AbstractWrapper / DefaultQueryWrapper / IQueryCondition /
    │               SimpleBaseEntity / BaseEntity / SimpleBasePO / BasePO / DeleteFlagEnum / IPersistEnum
    ├── model/      IBasePage / BasePage / BasePageDTO
    ├── response/   R / ResultCode
    ├── exception/  BizException / GlobalExceptionHandler
    ├── util/       BeanCopyUtils / HashUtil / IpUtils
    ├── annotation/ （预留）
    └── constant/   （预留）

com.sanye.strategy
└── StrategyApplication.java     # Spring Boot 入口
```

Mapper XML 文件位于 `src/main/resources/mapper/`。每个 Mapper 接口有对应的 XML，包含 `<resultMap>` 和 `<sql id="Base_Column_List">`，`<mapper namespace>` 须与 `infrastructure/persistence/mapper` 下的 Mapper 接口全限定名一致。

### 多环境配置

- `application.yaml` 设置激活配置为 `dev`
- `application-dev.yaml` — 开发环境 MySQL 连接（`sys_strategy` 库）
- `application-test.yaml` — 测试环境（最小配置，仅应用名）
- 新增环境时创建 `application-{profile}.yaml` 即可

### 数据库表结构

建表语句使用 `if not exists`，按作用域拆三文件，均可重复执行。所有表在 `sys_strategy` 库中，`utf8mb4` 字符集，`InnoDB` 引擎。主键为 `BIGINT UNSIGNED`，使用雪花算法（`IdType.ASSIGN_ID`）。

- `sql/auth.sql` — 用户中心认证域
- `sql/rbac.sql` — 权限域（角色/权限/关联表）
- `sql/oper_log.sql` — 操作日志（仅插入，无 deleted/update_time）

当前表：
- `ums_user` — 用户主表（继承 BaseEntity，含审计字段；已无 user_type 列——用户类型迁移为 RBAC 角色，注册默认绑定 NORMAL_USER）
- `ums_user_profile` — 用户扩展信息（继承 SimpleBaseEntity，通过 uk_user_id 一对一关联）
- `ums_user_auth` — 第三方登录绑定（继承 SimpleBaseEntity）
- `ums_user_account_security` — 账号安全/密码/MFA（继承 SimpleBaseEntity）
- `ums_user_login_device` — 登录设备追踪（继承 SimpleBaseEntity；`login_type` 登入方式 1-手机号 2-验证码 3-账号密码 4-第三方授权 + `login_channel` 登录渠道 0-未知 1-APP 2-小程序 3-H5 4-PC 5-第三方授权，前端显式传、后端白名单校验，当前仅 H5/PC 渠道 + 账号密码登入）
- `ums_role` — 角色（继承 BaseEntity；低基数列 status/deleted 建索引为既有约定，不按规范删除；data_scope 5 级：1-全部 2-仅本人 3-本部门 4-本部门及以下 5-自定义，3/4/5 依赖后续部门/数据域表；rbac.sql 已 seed 4 个内置角色 SUPER_ADMIN/MERCHANT/OPERATOR/NORMAL_USER）
- `ums_permission` — 权限资源：目录/菜单/按钮/接口（继承 BaseEntity）
- `ums_user_role` — 用户-角色关联（物理删除，无 deleted；续费经 UPDATE end_time 原地变更，不新建行，审计走 ums_oper_log）
- `ums_role_permission` — 角色-权限资源关联（物理删除，无 deleted）
- `ums_oper_log` — 操作日志（仅插入；实体不继承 SimpleBaseEntity——无 deleted/update_time，生成时手工处理）

## 已知缺陷与待办

common 层 DIP 初版经代码审查发现若干缺陷，详见 `docs/code-review/2026-08-04-common-layer-dip-review.md`。改动前先确认目标代码是否触及下列问题，避免在缺陷基线上叠加业务逻辑。

> 注：下列缺陷表中的路径为 DDD 重构**前**位置；重构后按「包结构」章节映射到新位置（common/config→infrastructure/config、common/auth→infrastructure/security 或 redis、common/interceptor→infrastructure/interceptor、service→domain/user/repository + infrastructure/persistence/impl、po/mapper→infrastructure/persistence）。

| 状态 | 缺陷 | 位置 | 影响 |
|------|------|------|------|
| ✅ 已修复 | `saveOrUpdate` 反射取 id 失败 → 永远 INSERT | `MpBaseServiceImpl.extractId` | 改为沿继承链上溯取主键 |
| ✅ 已修复 | 批量方法无事务、忽略逐行结果、恒返 true | `doInTransaction` 钩子 + `doInsertBatch` 等 | 事务钩子集中定义，桥接层以 TransactionTemplate 实现，逐行结果聚合 |
| ✅ 已修复 | 分页未注册拦截器 + pom 缺 `mybatis-plus-jsqlparser` | `infrastructure/config/MybatisPlusConfig` | 注册分页拦截器，`page()` 正常 |
| ✅ 已修复 | `or/and/nested/apply/exists/select/last` 静默空实现 | `AbstractWrapper` + `toMpWrapper` | 复杂操作符全部实现并映射；`or()` 采用待挂载标记（前后缀自动消除），空嵌套组/空投影/空 SQL/缺参占位符构建期 fail-fast，`select()` 支持列投影排除敏感列 |
| ✅ 已修复 | `createUserId`/`updateUserId` 无用户上下文可填 | `infrastructure/config/MybatisPlusConfig` + `infrastructure/security/UserContext` | 批1 `TokenAuthInterceptor` 填充 `UserContext`，`MetaObjectHandler` 从上下文取值；无上下文（定时任务/初始化脚本）落 NULL 不阻断 |
| ✅ 已修复 | `getOne` 多行抛 `TooManyResultsException` | `doGetOne` | 自动追加 `LIMIT 1`（调用方显式 `last()` 时不追加），多行返回第一行 |
| ✅ 已修复 | `orderBy` 空列崩溃 | `toMpWrapper` | 空列跳过，与 orderByAsc/Desc 一致 |
| ✅ 已修复 | `in()` 传 List 变单参绑定 | `DefaultQueryWrapper.in` | 单 `Collection` 参数构建期展平为数组 |
| ✅ 已修复 | 比较/模糊操作符传 null → `= NULL` 永不匹配 | `DefaultQueryWrapper` | null 视为未提供，条件跳过（等价 MP `eq(boolean,...)` 空值防护） |
| 🟠 | 逻辑删除 + 唯一键 → 标识永久占用 | `sql/auth.sql` | 删号后无法重注册用户名/手机号 |
| 🟠 | `ums_user_profile.ext_info` JSON 列无类型处理器 | `UmsUserProfilePO` | 暂以 String 存取（JSON 文本）；MP 的 `JacksonTypeHandler` 基于 Jackson 2（`com.fasterxml.jackson.*`），Spring Boot 4 默认 Jackson 3（`tools.jackson.*`），命名空间不兼容，序列化策略待定 |
| 🟠 暂缓 | 操作/审计日志（操作汇总 + 字段前后值 + 业务结果）+ 请求/WEB 日志统一设计 | `AbstractBaseService` + `MpBaseServiceImpl` + `GlobalExceptionHandler` | 现状：模板方法操作汇总日志已暂移除（见服务层「日志」）；字段级前后值需旧值，旧值仅桥接层可得。已评估字段级方案：A 模板快照钩子 `doGetOldSnapshot`（推荐，单行 diff）、B 桥接层 + `OperationLogger` 组件（可换审计表）、C AOP `@Audited`（违背 DIP 耦合收口，否决）。diff 语义：只报 new 非 null 且与 old 不同字段（兼容部分更新）。批量粒度与请求/WEB 日志方案待定 |
| ✅ 已落地 | 认证主链批1（注册/登录/刷新/登出/MFA 挑战凭证验证 + jti 黑名单） | interfaces/auth + application/auth + application/device + infrastructure/security + infrastructure/redis + infrastructure/interceptor | 双 Token（JWT HS256 显式钉死 + 不透明 refresh 哈希）、TokenAuthInterceptor 白名单/责任链、Redis 双用途（`jti:*` + `mfa:*`）、`R.fail` data 重载 + `BizException` payload 通道、审计人填充（缺陷 5 已修复）。批2-6 状态不变 |
| 📝 备忘 | RBAC 设计三注意事项（本期暂不处理，仅记档） | RBAC 批 + 文档 | ① 管理面过滤**硬编码** `create_user_id` 仅限 RBAC 角色/权限分页（谁创建看谁），**禁止**复用业务表——它与业务数据权限（DataScopeBuilder，data_scope 驱动）是两套机制，文档/注释/TODO 反复强调防误抄。② `POST /rbac/evict-batch` 批量踢异步任务本期**内存实现**（ConcurrentHashMap + 线程池 + taskId），服务重启正在执行的批量踢丢失——生产大批量变更需规避重启风险，后续落库（`ums_evict_task` 表 + 启动续跑）再升级。③ 功能权限与数据权限两模型生效差异：perms/roles 为 **JWT 快照**（变更经踢人或最长 30min accessToken TTL 生效，高敏变更必须踢人）；data_scope 为**实时**（每次请求 `UserContext.roleCodes` → 查 `ums_role.data_scope`，DB 查询即时生效）——开发文档 + 每个相关接口 javadoc 反复对比两模型，防混淆 |

**新增代码注意事项：**
- `saveOrUpdate` 已修复（沿继承链取主键）；批量方法经 `doInTransaction` 事务钩子执行并聚合逐行结果，事务收口于 `MpBaseServiceImpl`，`AbstractBaseService` 保持纯 POJO。
- 复杂查询（OR/嵌套/select 投影）走 `IWrapper` 有效：`or()`/`nested(consumer)`/`and`/`apply`/`exists`/`notExists`/`select`/`last` 均已实现并映射到 MP。`or()` 无参数版本仅支持单层 OR 拼接，OR 组用 `nested(sub -> sub.eq(...).or().eq(...))`。
- 设计模式类新增时必须补齐「角色说明 + 优缺点分析 + UML」三件套（CLAUDE.md 核心约束 2）。
- 操作汇总日志已暂移除，操作/审计日志 + 请求/WEB 日志统一设计待方案确认（字段级 diff 推荐方向：模板快照钩子 `doGetOldSnapshot` + 纯 POJO `DiffUtils`），见待办表。
- RBAC 三注意事项见待办表「📝 备忘」行：管理面 `create_user_id` 过滤禁止复用业务表；evict-batch 异步任务内存实现有重启丢失风险；功能权限快照 vs 数据权限实时两模型差异须文档/注释反复强调。
- **发布运维步骤**：权限启动扫描只做新增/复活（残留停用保持 status=1），**发布后必须手动执行 `POST /rbac/permissions/sync` 停用残留**（spec 开发注意事项 3，防误停用）。

## 代码生成

项目使用 MyBatis-Plus 代码生成器。生成类遵循统一模式（实体/PO 双层 + DDD 分层）：
- Domain 实体：纯 POJO，Lombok `@Data`，继承 `SimpleBaseEntity` / `BaseEntity`，放 `domain/{能力}/entity`
- PO：放 `infrastructure/persistence/po`，继承 `SimpleBasePO` / `BasePO`，`@TableName` + 列映射注解，Mapper 操作对象
- Mapper 接口继承 `BaseMapper<XxxPO>`，放 `infrastructure/persistence/mapper`，配 XML 结果映射
- Service 接口继承 `IService<XxxEntity>`，放 `domain/{能力}/repository`；实现类继承 `MpBaseServiceImpl<XxxPO, XxxMapper, XxxEntity>`，放 `infrastructure/persistence/impl`，实现 `toPO`/`toEntity`（用 `BeanCopyUtils`）
- Controller 继承 `BaseController<XxxEntity, S extends IBaseService<XxxEntity>, Q, V>`，放 `interfaces/{能力}`；应用服务（门面/用例）放 `application/{能力}`

新增表时，生成实体 + PO + mapper/service 三件套，根据表是否需要操作人审计决定继承 `SimpleBaseEntity`/`SimpleBasePO` 还是 `BaseEntity`/`BasePO`。

## DDD 目录结构迁移（未执行代码迁移，仅文档先行）

> 目录结构已按 DDD 分层重构目标更新于「包结构」章节。**实际代码文件尚未移动**——包声明、import、`@MapperScan`、`type-handlers-package`、XML namespace 均需按新结构同步调整后项目才能编译。迁移时必改点：
>
> 1. `infrastructure/config/MybatisPlusConfig` 中 `@MapperScan("com.sanye.strategy.mapper")` → `com.sanye.strategy.infrastructure.persistence.mapper`
> 2. `application.yaml` 中 `mybatis-plus.type-handlers-package: com.sanye.strategy.common.config` → `com.sanye.strategy.infrastructure.config`
> 3. `src/main/resources/mapper/*.xml` 的 `<mapper namespace="com.sanye.strategy.mapper.XxxMapper">` → `com.sanye.strategy.infrastructure.persistence.mapper.XxxMapper`
> 4. 所有移动类的 `package` 声明 + 引用方 `import` 随新路径调整

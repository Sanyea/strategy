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
- Lombok（maven-compiler-plugin 中配置了注解处理器）
- Spring Boot Actuator，用于健康检查/监控端点

## 架构

### DIP 分层设计

项目严格遵循依赖倒置原则（DIP），自定义抽象层完全解耦 ORM 框架。

**核心原则：**
- Controller 依赖 `IBaseService<T>`（顶层接口），不依赖任何框架类型
- Service 层依赖自定义 `IWrapper<T>` / `IBasePage<T>`，不直接使用 MyBatis-Plus 的 `QueryWrapper` / `Page`
- MP 行为耦合收口两处：`MpBaseServiceImpl`（CRUD 桥接）+ `common/config` 的枚举 `DeleteFlagEnumTypeHandler`（实体基类层枚举持久化适配）；枚举映射为声明式例外，见「实体与持久化对象（PO）双层分离」
- DTO 组合分页参数，禁止 DTO 继承分页对象

### 自定义抽象层（零框架依赖）

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

### 服务层

#### 类层次

```
IBaseService<T>                    ← 接口：10 个 CRUD 方法，依赖 IWrapper/IBasePage
    ↑
IService<T>                        ← 接口：+ 4 个批量方法
    ↑ (implements)
AbstractBaseService<T>             ← 抽象类：模板方法 + doXXX 抽象钩子，零 MP 依赖
    ↑ (extends)
MpBaseServiceImpl<P, M, T>         ← MP 桥接实现：IWrapper→QueryWrapper，实体↔PO 转换，框架耦合唯一处
    ↑ (extends)                      （P extends BasePO，M extends BaseMapper<P>，T extends SimpleBaseEntity）
XxxServiceImpl                     ← 业务 Service（实现 toPO/toEntity）
```

| 角色 | 类 | 职责 |
|------|-----|------|
| 顶层抽象 | `IBaseService<T>` | 定义 CRUD 契约，10 个方法 |
| 批量契约 | `IService<T>` | + `insertBatch`/`updateBatch`/`deleteBatch`/`saveOrUpdateBatch` |
| 模板骨架 | `AbstractBaseService<T>` | 纯 POJO：模板方法（beforeInsert 等钩子）+ doXXX 抽象方法 + `doInTransaction` 事务钩子，零框架依赖 |
| MP 适配器 | `MpBaseServiceImpl<P, M, T>` | 实现 doXXX，IWrapper→QueryWrapper，IBasePage→Page，实体↔PO 转换（toPO/toEntity） |
| 业务实现 | `XxxServiceImpl` | 继承 MpBaseServiceImpl，实现 toPO/toEntity，添加业务逻辑 |

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
protected abstract boolean doInsertBatch(Collection<T> entityList);
protected abstract boolean doUpdateBatch(Collection<T> entityList);
protected abstract boolean doDeleteBatch(Collection<? extends Serializable> idList);
protected abstract boolean doSaveOrUpdateBatch(Collection<T> entityList);

// 事务钩子（纯 POJO 声明，默认透传无事务；桥接层覆写为具体事务实现）
protected <R> R doInTransaction(Supplier<R> action) { return action.get(); }

// 钩子方法（子类按需覆盖）
protected void beforeInsert(T entity) {}
protected void afterInsert(T entity) {}
protected void beforeUpdate(T entity) {}
protected void afterUpdate(T entity) {}
```

**事务架构（设计 B：事务抽象钩子）：**

- `AbstractBaseService<T>` 纯 POJO，零框架依赖（不依赖 MyBatis-Plus，也不依赖 Spring）
- 批量入口（insertBatch/updateBatch/deleteBatch/saveOrUpdateBatch）经 `doInTransaction()` 钩子执行，默认透传（无事务）
- `MpBaseServiceImpl` 覆写 `doInTransaction`，用 Spring `TransactionTemplate` 编程式事务实现，任一操作失败整体回滚
- 替换事务实现（JTA/Atomikos/Seata）只换 `PlatformTransactionManager` Bean 或覆写钩子；禁用则保持基类默认透传
- 单元测试可直接实例化子类覆写钩子装假事务，无需启动 Spring

#### MpBaseServiceImpl<P extends SimpleBasePO, M extends BaseMapper<P>, T extends SimpleBaseEntity>

- 继承 `AbstractBaseService<T>`
- 通过 `@Autowired` 注入 `M baseMapper`（Mapper 操作 PO）
- 抽象钩子 `toPO(T)` / `toEntity(P)`：实体↔PO 转换，子类实现（通常用 `BeanCopyUtils`）
- 实现所有 `doXXX` 方法：先转 PO，再委托给 MyBatis-Plus `BaseMapper` 方法，结果转回实体
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

### Controller 层

#### BaseController<T, S, Q, V>

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
}
```

- 状态码枚举 `ResultCode`（`common.response`）：HTTP 语义对齐，分 2xx 成功 / 4xx 客户端错误 / 5xx 服务端错误三段，含 `isSuccess()`/`isClientError()`/`isServerError()` 分类谓词与 `of(int)` 查找。新增业务码在此扩展，禁止业务层散落魔法数字。
- `timestamp` 使用 `java.time.Instant.now().toString()` 生成 ISO-8601 标准时间字符串（如 `2026-08-03T10:30:00.123Z`），不走毫秒时间戳。

#### 异常体系

| 类 | 职责 |
|----|------|
| `BizException`（`common/exception`） | 业务可控错误信号，携带 `ResultCode`，业务层抛出不需逐层声明 |
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

| 层级 | 基类 | 说明 |
|---|---|---|
| 实体层（纯 POJO，零框架依赖） | `SimpleBaseEntity` | 全部领域实体继承：`id`、`deleted`（`DeleteFlagEnum`）、`createTime`、`updateTime` |
| 实体层 | `BaseEntity` extends `SimpleBaseEntity` | 核心业务表（用户、订单、商品等）：+`createUserId`、`updateUserId` |
| PO 层（MP 注解，Mapper 操作对象） | `SimpleBasePO` | 对应 `SimpleBaseEntity`：`@TableId(ASSIGN_ID)`、`@TableLogic`、`@TableField(fill)` |
| PO 层 | `BasePO` extends `SimpleBasePO` | 对应 `BaseEntity`：+审计人字段自动填充 |

**转换：** `MpBaseServiceImpl<P, M, T>` 桥接层通过抽象钩子 `toPO(T)` / `toEntity(P)` 完成实体↔PO 互转，子类用 `BeanCopyUtils.copy()`（`common.util`）实现。

**包位置：** 实体在 `com.sanye.strategy.domain`，PO 在 `com.sanye.strategy.po`，一一对应（`UmsUser` ↔ `UmsUserPO`）。

**业务枚举：** 状态/类型/标记字段（`gender`、`userType`、`userStatus`、`isVip`、`identityType` 等）抽成 `com.sanye.strategy.enums` 包下枚举类，`@EnumValue` 标注映射码（Integer 或 String），MyBatis-Plus 自动完成 枚举↔DB 互转。实体与 PO **共用同一枚举类**，`BeanCopyUtils` 同名同类型直接复制，无额外转换。查找枚举用静态 `valueOf(code)`；String 码枚举（如 `IdentityTypeEnum`）因与内置 `valueOf(String)` 签名冲突，改用 `valueOfCode(code)`。业务枚举统一进 `enums`。

**实体基类层枚举（约定式解耦）：** 仅 `DeleteFlagEnum`（`common.base`，`SimpleBaseEntity`/`SimpleBasePO` 的 `deleted` 字段）走约定式彻底解耦——枚举只实现纯接口 `IPersistEnum<T>`（`getPersistValue()` 返回映射码，零框架依赖），**不使用** `@EnumValue`。持久化适配由 `common/config/DeleteFlagEnumTypeHandler` 承担：写库取 `getPersistValue()`，读库经 `DeleteFlagEnum.valueOf(code)` 还原；通过 `mybatis-plus.type-handlers-package` 扫描 + `@MappedTypes` 注册，PO 层字段声明与上层业务代码完全不动。新增实体基类层枚举时沿用此约定（实现 `IPersistEnum` + 补一个 TypeHandler）。

**自动填充：** `MetaObjectHandler` 已注册（`MybatisPlusConfig`），插入/更新时填充 PO 的 `createTime`/`updateTime`；`createUserId`/`updateUserId` 待用户上下文（Spring Security/拦截器）接入后补充，当前写入 NULL。

**判断规则：** 系统级表（登录设备、认证绑定、安全日志）继承 `SimpleBaseEntity`。有人工操作、需要追溯操作人的业务表继承 `BaseEntity`。对应 PO 同理选 `SimpleBasePO` / `BasePO`。

`deleted` 字段使用 `DeleteFlagEnum`（`NOT_DELETED=0`，`DELETED=1`），实现 `IPersistEnum<Integer>` 约定式映射，持久化由 `DeleteFlagEnumTypeHandler` 桥接（见上文）。MyBatis-Plus 的 `@TableLogic` 自动过滤已删除行。

### 包结构

```
com.sanye.strategy
├── domain/           — 实体类（纯 POJO，继承 SimpleBaseEntity/BaseEntity），每表一个
├── enums/            — 业务枚举类（@EnumValue 类型安全映射，实体与 PO 共用同一套枚举）
├── po/               — PO 类（继承 SimpleBasePO/BasePO，@TableName + MP 映射注解），Mapper 操作对象
├── mapper/           — MyBatis-Plus BaseMapper<XxxPO> 接口，XML 结果映射在 resources/mapper/
├── service/          — 业务 Service 接口（继承 IService<T>）
│   └── impl/         — 业务 Service 实现（继承 MpBaseServiceImpl<P, M, T>）
├── controller/       — Controller，继承 BaseController<T, S, Q, V>
├── common/
│   ├── base/         — IBaseService<T>、IService<T>、AbstractBaseService<T>
│   │                   MpBaseServiceImpl<P,M,T>、IWrapper<T>、AbstractWrapper<T>
│   │                   DefaultQueryWrapper<T>、IQueryCondition、相关条件子类型
│   │                   实体层：SimpleBaseEntity、BaseEntity、DeleteFlagEnum
│   │                   PO 层：SimpleBasePO、BasePO
│   ├── model/        — IBasePage<T>、BasePage<T>、BasePageDTO<T>、DTO 类
│   ├── response/     — R<T> 统一响应包装、ResultCode 状态码枚举
│   ├── annotation/   — 自定义注解（待实现）
│   ├── config/       — Spring 配置（MybatisPlusConfig：分页拦截器 + MetaObjectHandler；
│   │                   DeleteFlagEnumTypeHandler：IPersistEnum 枚举持久化适配）
│   ├── constant/     — 常量（待实现）
│   ├── exception/    — BizException（业务异常）、GlobalExceptionHandler（全局异常处理）
│   ├── interceptor/  — 拦截器（待实现）
│   └── util/         — 工具类（BeanCopyUtils 实体↔PO 转换）
└── StrategyApplication.java — Spring Boot 入口
```

Mapper XML 文件位于 `src/main/resources/mapper/`。每个 Mapper 接口有对应的 XML，包含 `<resultMap>` 和 `<sql id="Base_Column_List">`。

### 多环境配置

- `application.yaml` 设置激活配置为 `dev`
- `application-dev.yaml` — 开发环境 MySQL 连接（`sys_strategy` 库）
- `application-test.yaml` — 测试环境（最小配置，仅应用名）
- 新增环境时创建 `application-{profile}.yaml` 即可

### 数据库表结构

建表语句使用 `if not exists`，`sql/user.sql` 可重复执行。所有表在 `sys_strategy` 库中，`utf8mb4` 字符集，`InnoDB` 引擎。主键为 `BIGINT UNSIGNED`，使用雪花算法（`IdType.ASSIGN_ID`）。

当前表：
- `ums_user` — 用户主表（继承 BaseEntity，含审计字段）
- `ums_user_profile` — 用户扩展信息（继承 SimpleBaseEntity，通过 uk_user_id 一对一关联）
- `ums_user_auth` — 第三方登录绑定（继承 SimpleBaseEntity）
- `ums_user_account_security` — 账号安全/密码/MFA（继承 SimpleBaseEntity）
- `ums_user_login_device` — 登录设备追踪（继承 SimpleBaseEntity）

## 已知缺陷与待办

common 层 DIP 初版经代码审查发现若干缺陷，详见 `docs/code-review/2026-08-04-common-layer-dip-review.md`。改动前先确认目标代码是否触及下列问题，避免在缺陷基线上叠加业务逻辑：

| 状态 | 缺陷 | 位置 | 影响 |
|------|------|------|------|
| ✅ 已修复 | `saveOrUpdate` 反射取 id 失败 → 永远 INSERT | `MpBaseServiceImpl.extractId` | 改为沿继承链上溯取主键 |
| ✅ 已修复 | 批量方法无事务、忽略逐行结果、恒返 true | `doInTransaction` 钩子 + `doInsertBatch` 等 | 事务钩子集中定义，桥接层以 TransactionTemplate 实现，逐行结果聚合 |
| ✅ 已修复 | 分页未注册拦截器 + pom 缺 `mybatis-plus-jsqlparser` | `common/config/MybatisPlusConfig` | 注册分页拦截器，`page()` 正常 |
| ✅ 已修复 | `or/and/nested/apply/exists/select/last` 静默空实现 | `AbstractWrapper` + `toMpWrapper` | 复杂操作符全部实现并映射；`or()` 采用待挂载标记（前后缀自动消除），空嵌套组/空投影/空 SQL/缺参占位符构建期 fail-fast，`select()` 支持列投影排除敏感列 |
| 🟠 | `createUserId`/`updateUserId` 无用户上下文可填 | `MybatisPlusConfig` | 已注册 MetaObjectHandler 填时间字段；审计人字段待安全上下文接入 |
| ✅ 已修复 | `getOne` 多行抛 `TooManyResultsException` | `doGetOne` | 自动追加 `LIMIT 1`（调用方显式 `last()` 时不追加），多行返回第一行 |
| ✅ 已修复 | `orderBy` 空列崩溃 | `toMpWrapper` | 空列跳过，与 orderByAsc/Desc 一致 |
| ✅ 已修复 | `in()` 传 List 变单参绑定 | `DefaultQueryWrapper.in` | 单 `Collection` 参数构建期展平为数组 |
| ✅ 已修复 | 比较/模糊操作符传 null → `= NULL` 永不匹配 | `DefaultQueryWrapper` | null 视为未提供，条件跳过（等价 MP `eq(boolean,...)` 空值防护） |
| 🟠 | 逻辑删除 + 唯一键 → 标识永久占用 | `sql/user.sql` | 删号后无法重注册用户名/手机号 |
| 🟠 | `ums_user_profile.ext_info` JSON 列无类型处理器 | `UmsUserProfilePO` | 暂以 String 存取（JSON 文本）；MP 的 `JacksonTypeHandler` 基于 Jackson 2（`com.fasterxml.jackson.*`），Spring Boot 4 默认 Jackson 3（`tools.jackson.*`），命名空间不兼容，序列化策略待定 |

**新增代码注意事项：**
- `saveOrUpdate` 已修复（沿继承链取主键）；批量方法经 `doInTransaction` 事务钩子执行并聚合逐行结果，事务收口于 `MpBaseServiceImpl`，`AbstractBaseService` 保持纯 POJO。
- 复杂查询（OR/嵌套/select 投影）走 `IWrapper` 有效：`or()`/`nested(consumer)`/`and`/`apply`/`exists`/`notExists`/`select`/`last` 均已实现并映射到 MP。`or()` 无参数版本仅支持单层 OR 拼接，OR 组用 `nested(sub -> sub.eq(...).or().eq(...))`。
- 设计模式类新增时必须补齐「角色说明 + 优缺点分析 + UML」三件套（CLAUDE.md 核心约束 2）。

## 代码生成

项目使用 MyBatis-Plus 代码生成器。生成类遵循统一模式（实体/PO 双层）：
- Domain 实体：纯 POJO，Lombok `@Data`，继承 `SimpleBaseEntity` / `BaseEntity`
- PO：放 `com.sanye.strategy.po` 包，继承 `SimpleBasePO` / `BasePO`，`@TableName` + 列映射注解，Mapper 操作对象
- Mapper 接口继承 `BaseMapper<XxxPO>`，配 XML 结果映射
- Service 接口继承 `IService<XxxEntity>`，实现类继承 `MpBaseServiceImpl<XxxPO, XxxMapper, XxxEntity>`，实现 `toPO`/`toEntity`（用 `BeanCopyUtils`）
- Controller 继承 `BaseController<XxxEntity, S extends IBaseService<XxxEntity>, Q, V>`

新增表时，生成实体 + PO + mapper/service 三件套，根据表是否需要操作人审计决定继承 `SimpleBaseEntity`/`SimpleBasePO` 还是 `BaseEntity`/`BasePO`。

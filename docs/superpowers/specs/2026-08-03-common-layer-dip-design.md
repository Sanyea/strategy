# Common 层 DIP 架构设计

**日期：** 2026-08-03
**状态：** 已确认
**分支：** dev

## 1. 概述

基于依赖倒置原则（DIP）构建项目 common 层的 Service 和 Controller 基础设施。核心目标：上层依赖抽象接口，不依赖具体 ORM 框架，换 ORM 时只需重写一个桥接类。

## 2. 架构总览

```
（纯抽象层 — 零框架依赖）
IWrapper<T> / IQueryCondition / IBasePage<T>    ← 查询条件与分页抽象
IBaseService<T> / IService<T>                   ← CRUD 契约
AbstractBaseService<T>                          ← 模板方法 + 钩子
R<T>                                            ← 统一响应

（MP 适配层 — 框架耦合唯一处）
MpBaseServiceImpl<M extends BaseMapper<T>, T>   ← IWrapper→QueryWrapper 桥接

（表现层）
BaseController<T, S extends IBaseService<T>, Q, V>  ← 标准化 GET 查询端点
```

## 3. 查询条件抽象

### 3.1 IQueryCondition — 条件元数据

```java
public interface IQueryCondition {
    String getColumn();    // 字段名
    String getOperator();  // 操作符：eq/like/gt/or/nested/exists…
    Object getValue();     // 值
}
```

子类型（内部类，放在 DefaultQueryWrapper 中）：

| 类型 | 操作符 | 用途 |
|------|--------|------|
| `Condition` | eq/ne/gt/ge/lt/le/like/… | 普通二元条件 |
| `OrCondition` | or | OR 逻辑分隔 |
| `AndCondition` | and | 原生 SQL AND 片段 |
| `NestedCondition` | nested | 嵌套子查询（递归包含 `List<IQueryCondition>`） |
| `ExistsCondition` | exists | EXISTS 子查询 |
| `ApplyCondition` | apply | 原生 SQL 拼接 |

### 3.2 IWrapper<T> — 查询条件构建接口

链式 API，约 30 个方法：eq、ne、gt、ge、lt、le、like、notLike、likeLeft、likeRight、between、notBetween、isNull、isNotNull、in、notIn、inSql、notInSql、groupBy、having、orderByAsc、orderByDesc、orderBy、or、and、nested、apply、exists、notExists、select、last。

### 3.3 类层次

```
IWrapper<T> (interface)
    ↑
AbstractWrapper<T> (abstract) — conditionList 存储 + addCondition()，复杂方法空实现
    ↑
DefaultQueryWrapper<T> — 基础比较/模糊/范围/集合/排序方法实现
```

`AbstractWrapper` 内部维护 `List<IQueryCondition> conditionList`，子类通过 `addCondition(column, op, value)` 添加条件。复杂方法（`or`/`and`/`nested`/`apply`/`exists`/`notExists`/`select`/`last`）在 `AbstractWrapper` 中保留空实现，子类暂不覆盖。`DefaultQueryWrapper` 实现基础查询方法（eq/ne/gt/ge/lt/le/like/notLike/likeLeft/likeRight/between/notBetween/isNull/isNotNull/in/notIn/inSql/notInSql/groupBy/having/orderByAsc/orderByDesc/orderBy）。

## 4. 分页抽象

### 4.1 IBasePage<T>

```java
public interface IBasePage<T> extends Serializable {
    List<T> getRecords();
    IBasePage<T> setRecords(List<T> records);
    long getTotal();
    IBasePage<T> setTotal(long total);
    long getSize();
    IBasePage<T> setSize(long size);
    long getCurrent();
    IBasePage<T> setCurrent(long current);

    default long offset()   // (current-1) * size
    default long getPages() // total / size 向上取整
    default <R> IBasePage<R> convert(Function<T, R> mapper)  // DO→VO 类型转换
    default boolean searchCount() // 是否查总数，默认 true
}
```

### 4.2 类层次

```
IBasePage<T> (interface)
    ↑
BasePage<T> — 存储 records/total/size/current，提供构造器
    ↑
BasePageDTO<T> — 静态工厂 of()、业务方法 isHasMore()
```

## 5. 服务层

### 5.1 IBaseService<T> — 10 个 CRUD 方法

| 方法 | 返回 | 说明 |
|------|------|------|
| `insert(T entity)` | `boolean` | 新增 |
| `updateById(T entity)` | `boolean` | 按ID更新 |
| `deleteById(Serializable id)` | `boolean` | 逻辑删除 |
| `getById(Serializable id)` | `T` | 按ID查询 |
| `listByIds(Collection<? extends Serializable> ids)` | `List<T>` | ID批量查询 |
| `getOne(IWrapper<T> wrapper)` | `T` | 条件查询单条 |
| `list(IWrapper<T> wrapper)` | `List<T>` | 条件查询列表 |
| `count(IWrapper<T> wrapper)` | `long` | 条件统计 |
| `page(IBasePage<T> page, IWrapper<T> wrapper)` | `IBasePage<T>` | 分页查询 |
| `saveOrUpdate(T entity)` | `boolean` | 保存或更新 |

所有方法签名使用自定义 `IWrapper<T>` 和 `IBasePage<T>`，零 MyBatis-Plus 类型泄漏。

### 5.2 IService<T> — 4 个批量方法

```java
public interface IService<T> extends IBaseService<T> {
    boolean insertBatch(Collection<T> entityList);
    boolean updateBatch(Collection<T> entityList);
    boolean deleteBatch(Collection<? extends Serializable> idList);
    boolean saveOrUpdateBatch(Collection<T> entityList);
}
```

### 5.3 AbstractBaseService<T> — 模板方法骨架

纯抽象类，不依赖任何 ORM 框架。

```java
public abstract class AbstractBaseService<T> implements IService<T> {

    // ===== 14 个 doXXX 抽象方法（子类实现） =====
    protected abstract boolean doInsert(T entity);
    protected abstract boolean doUpdateById(T entity);
    protected abstract boolean doDeleteById(Serializable id);
    protected abstract T doGetById(Serializable id);
    protected abstract List<T> doListByIds(Collection<? extends Serializable> ids);
    protected abstract T doGetOne(IWrapper<T> wrapper);
    protected abstract List<T> doList(IWrapper<T> wrapper);
    protected abstract long doCount(IWrapper<T> wrapper);
    protected abstract IBasePage<T> doPage(IBasePage<T> page, IWrapper<T> wrapper);
    protected abstract boolean doSaveOrUpdate(T entity);
    protected abstract boolean doInsertBatch(Collection<T> entityList);
    protected abstract boolean doUpdateBatch(Collection<T> entityList);
    protected abstract boolean doDeleteBatch(Collection<? extends Serializable> idList);
    protected abstract boolean doSaveOrUpdateBatch(Collection<T> entityList);

    // ===== 钩子方法（子类可选覆盖） =====
    protected void beforeInsert(T entity) {}
    protected void afterInsert(T entity) {}
    protected void beforeUpdate(T entity) {}
    protected void afterUpdate(T entity) {}

    // ===== IService 接口实现（委托 doXXX + 钩子） =====
    @Override
    public boolean insert(T entity) {
        beforeInsert(entity);
        boolean result = doInsert(entity);
        afterInsert(entity);
        return result;
    }
    // ... 其余方法同理
}
```

**作用：**
- 横切关注点（日志、权限校验）通过钩子统一处理，不侵入业务代码
- 换 ORM 时只需提供新的 doXXX 实现，模板逻辑完全复用

### 5.4 MpBaseServiceImpl<M, T> — MP 桥接适配器

框架耦合的唯一位置。

```java
public abstract class MpBaseServiceImpl<M extends BaseMapper<T>, T>
        extends AbstractBaseService<T> {

    @Autowired
    protected M baseMapper;

    // 核心转换：IWrapper → MP QueryWrapper
    protected QueryWrapper<T> toMpWrapper(IWrapper<T> wrapper) {
        QueryWrapper<T> qw = new QueryWrapper<>();
        for (IQueryCondition cond : wrapper.getConditions()) {
            switch (cond.getOperator()) {
                // 比较
                case "eq"  -> qw.eq(cond.getColumn(), cond.getValue());
                case "ne"  -> qw.ne(cond.getColumn(), cond.getValue());
                case "gt"  -> qw.gt(cond.getColumn(), cond.getValue());
                case "ge"  -> qw.ge(cond.getColumn(), cond.getValue());
                case "lt"  -> qw.lt(cond.getColumn(), cond.getValue());
                case "le"  -> qw.le(cond.getColumn(), cond.getValue());
                // 模糊
                case "like"      -> qw.like(cond.getColumn(), cond.getValue());
                case "notLike"   -> qw.notLike(cond.getColumn(), cond.getValue());
                case "likeLeft"  -> qw.likeLeft(cond.getColumn(), cond.getValue());
                case "likeRight" -> qw.likeRight(cond.getColumn(), cond.getValue());
                // 范围
                case "between"    -> { Object[] a = (Object[]) cond.getValue(); qw.between(cond.getColumn(), a[0], a[1]); }
                case "notBetween" -> { Object[] a = (Object[]) cond.getValue(); qw.notBetween(cond.getColumn(), a[0], a[1]); }
                // 空值
                case "isNull"    -> qw.isNull(cond.getColumn());
                case "isNotNull" -> qw.isNotNull(cond.getColumn());
                // 集合
                case "in"       -> qw.in(cond.getColumn(), (Object[]) cond.getValue());
                case "notIn"    -> qw.notIn(cond.getColumn(), (Object[]) cond.getValue());
                case "inSql"    -> qw.inSql(cond.getColumn(), (String) cond.getValue());
                case "notInSql" -> qw.notInSql(cond.getColumn(), (String) cond.getValue());
                // 分组与聚合
                case "groupBy" -> qw.groupBy((String[]) cond.getValue());
                case "having"  -> { Object[] a = (Object[]) cond.getValue(); qw.having((String) a[0], (Object[]) a[1]); }
                // 排序
                case "orderByAsc"  -> qw.orderByAsc((String[]) cond.getValue());
                case "orderByDesc" -> qw.orderByDesc((String[]) cond.getValue());
                case "orderBy"     -> { Object[] a = (Object[]) cond.getValue(); qw.orderBy((Boolean) a[0], (String[]) a[1]); }
                // 复杂：or/and/nested/apply/exists/notExists/select/last 暂不处理（AbstractWrapper 空实现）
            }
        }
        return qw;
    }

    // doXXX 实现委托给 baseMapper
    @Override
    protected boolean doInsert(T entity) { return baseMapper.insert(entity) > 0; }

    @Override
    protected IBasePage<T> doPage(IBasePage<T> page, IWrapper<T> wrapper) {
        Page<T> mpPage = new Page<>(page.getCurrent(), page.getSize(), page.searchCount());
        Page<T> result = baseMapper.selectPage(mpPage, toMpWrapper(wrapper));
        // 回填 IBasePage
        BasePage<T> out = new BasePage<>();
        out.setRecords(result.getRecords());
        out.setTotal(result.getTotal());
        out.setSize(result.getSize());
        out.setCurrent(result.getCurrent());
        return out;
    }
    // ... 其余 doXXX 实现
}
```

### 5.5 完整类图

```
IBaseService<T>                    ← 接口: 10 CRUD
    ↑
IService<T>                        ← 接口: + 4 batch
    ↑ (implements)
AbstractBaseService<T>             ← 抽象: template method + doXXX hooks, 零 MP
    ↑ (extends)
MpBaseServiceImpl<M extends BaseMapper<T>, T>  ← MP bridge ONLY
    ↑ (extends)
UserServiceImpl extends MpBaseServiceImpl<UserMapper, UmsUser>
```

**角色总结：**

| 角色 | 类 | MP 依赖 | 职责 |
|------|-----|:---:|------|
| CRUD 契约 | `IBaseService<T>` | 无 | 定义 10 个方法签名 |
| 批量契约 | `IService<T>` | 无 | 扩展 4 个批量操作 |
| 模板骨架 | `AbstractBaseService<T>` | 无 | 模板方法、钩子、流程编排 |
| MP 适配器 | `MpBaseServiceImpl<M, T>` | 有 | IWrapper→QueryWrapper、委托 baseMapper |
| 业务实现 | `XxxServiceImpl` | 无 | 业务逻辑 |

## 6. Controller 层

### 6.1 BaseController<T, S, Q, V>

```java
public abstract class BaseController<
    T,                          // 实体
    S extends IBaseService<T>,  // 顶层 Service
    Q,                          // 请求 DTO（无父类约束）
    V                           // 响应 VO（无父类约束）
>
```

**子类实现：**
- `toVO(T entity): V` — 实体 → VO
- `buildQuery(Q query): IWrapper<T>` — DTO → 查询条件
- `buildPage(Q query): IBasePage<T>` — DTO → 分页参数

**BaseController 提供标准端点：**

| 端点 | 方法 | 返回 |
|------|------|------|
| `GET /{id}` | `getById(@PathVariable Long id)` | `R<V>` |
| `GET /page` | `page(@Valid Q query)` | `R<IBasePage<V>>` |

**BaseController 不提供：** `POST /`、`PUT /{id}`、`DELETE /{id}`。

原因：增删改含业务逻辑（密码加密、状态校验、权限检查），不适合通用模板，子类自行实现。

### 6.2 查询 DTO 设计原则

- DTO **组合**分页参数（`page`、`size`、`sortField`、`sortOrder`），**禁止继承**任何分页类
- `Q` 泛型无父类约束
- 分页载体由 `buildPage(Q)` 从 DTO 字段提取

示例：

```java
@Data
public class UserPageReq {
    // 分页参数（组合，非继承）
    private Integer page = 1;
    private Integer size = 10;
    private String sortField;
    private String sortOrder;
    // 业务查询字段
    private String username;
    private Integer userStatus;
}
```

### 6.3 子类示例

```java
@RestController
@RequestMapping("/api/user")
public class UserController
        extends BaseController<UmsUser, UmsUserService, UserPageReq, UserVO> {

    @Override
    protected UserVO toVO(UmsUser user) { /* 字段映射 */ }

    @Override
    protected IWrapper<UmsUser> buildQuery(UserPageReq req) {
        DefaultQueryWrapper<UmsUser> w = new DefaultQueryWrapper<>();
        if (req.getUsername() != null) w.eq("username", req.getUsername());
        if (req.getUserStatus() != null) w.eq("user_status", req.getUserStatus());
        return w;
    }

    @Override
    protected IBasePage<UmsUser> buildPage(UserPageReq req) {
        return new BasePage<>(req.getPage(), req.getSize());
    }

    @PostMapping
    public R<Void> create(@RequestBody @Valid UserCreateReq req) {
        // 子类自主实现
        userService.insert(toEntity(req));
        return R.ok();
    }
}
```

## 7. 统一响应 R<T>

```java
package com.sanye.strategy.common.response;

public class R<T> {
    private int code;           // 0=成功，非0=业务错误码
    private String message;     // 提示信息
    private T data;             // 响应体
    private String timestamp;   // ISO-8601 时间字符串

    // 构造时自动设置 timestamp = Instant.now().toString()
    // 输出格式：2026-08-03T10:30:00.123Z
    public static <T> R<T> ok(T data);
    public static <T> R<T> ok();
    public static <T> R<T> fail(int code, String message);
}
```

`timestamp` 使用 `java.time.Instant.now().toString()`，JDK8 新时间 API，ISO-8601 标准格式，可读性强。

## 8. 需新建/修改的文件

| 文件 | 动作 | 说明 |
|------|------|------|
| `common/base/IBaseService.java` | 重写 | 10 个 CRUD 方法，使用 IWrapper/IBasePage |
| `common/base/IService.java` | 已有 | 4 个批量方法（不变） |
| `common/base/AbstractBaseService.java` | 新建 | 模板方法 + 14 doXXX + 4 钩子 |
| `common/base/MpBaseServiceImpl.java` | 新建 | IWrapper→QueryWrapper 桥接 |
| `common/base/IQueryCondition.java` | 修改 | 新增子类型（NestedCondition、ExistsCondition 等） |
| `common/base/DefaultQueryWrapper.java` | 修改 | 补齐基础查询方法（eq/ne/gt/like/between/in 等），复杂方法沿用 AbstractWrapper 空实现 |
| `common/base/AbstractWrapper.java` | 修改 | 复杂方法保留空实现（or/and/nested/apply/exists 等） |
| `common/model/IBasePage.java` | 已有 | 不变 |
| `common/model/BasePage.java` | 已有 | 不变 |
| `common/model/BasePageDTO.java` | 已有 | 不变 |
| `common/response/R.java` | 新建 | 统一响应包装，ISO-8601 时间字符串 |
| `common/controller/BaseController.java` | 新建 | 四泛型，GET 查询端点 |

## 9. 优缺点

### 优点

- DIP 贯穿三层 — Controller → IBaseService → IWrapper/IBasePage，全链路零 MP 泄漏
- 框架隔离 — 仅 `MpBaseServiceImpl` 持有 MyBatis-Plus 引用，换 ORM 只改一个类
- 模板方法 — before/after 钩子，日志、权限等横切关注点集中处理
- DTO 组合分页 — 松耦合，DTO 不继承分页父类
- 类型转换内置 — `IBasePage.convert()` 一行完成 DO→VO

### 缺点

- 抽象层数 4 层（IBaseService → IService → AbstractBaseService → MpBaseServiceImpl），新人理解成本较高
- `IWrapper ↔ QueryWrapper` 转换有遍历开销（可接受：查询条件数量通常 < 20）
- 复杂嵌套查询的递归展开需仔细处理边界（NestedCondition 中的子条件可能再次包含 NestedCondition）

## 10. 设计决策记录

| 决策点 | 选项 | 理由 |
|--------|------|------|
| CRUD 方法数量 | 10 个 | 覆盖 90% 场景，不过度膨胀 |
| IService 批量方法 | 4 个（insertBatch/updateBatch/deleteBatch/saveOrUpdateBatch） | 核心批量操作，不冗余 |
| BaseController 端点 | 仅 GET（id + page） | 增删改含业务逻辑，不适合通用化 |
| BaseController 泛型 Q | 无父类约束 | DTO 组合分页，非继承分页 |
| BaseController 泛型 V | 不继承 Serializable | 减少不必要的约束 |
| MP 桥接位置 | 独立子类 MpBaseServiceImpl | 抽象层零框架依赖 |
| R<T> timestamp 类型 | `String`（ISO-8601） | JDK8 Instant API，可读性优于毫秒时间戳 |

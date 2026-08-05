# Common 层 DIP 架构实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 创建 AbstractBaseService、MpBaseServiceImpl、R、BaseController 四个类，完成 common 层 DIP 架构。

**Architecture:** 纯抽象层（零 MP 依赖）→ MP 适配层（框架耦合唯一处）→ Controller 层（标准化 GET 端点）。IBaseService/IService/IWrapper/IBasePage 等接口已就绪，本次只新建实现类。

**Tech Stack:** Java 21, Spring Boot 4.1.0, MyBatis-Plus 3.5.17, Jakarta Validation, Lombok

## Global Constraints

- 严格遵循阿里巴巴 Java 开发规范
- @author 使用 31372
- JavaDoc 使用中文
- AbstractBaseService 零 MyBatis-Plus 依赖
- 仅 MpBaseServiceImpl 持有 MP 引用
- R<T> timestamp 使用 Instant.now().toString()（ISO-8601）

---

## 现状分析

以下文件已完整，**无需修改**：
- `IBaseService.java` — 10 个 CRUD 方法已定义，使用 IWrapper/IBasePage
- `IService.java` — 4 个批量方法已定义
- `IWrapper.java` — 约 30 个链式方法已声明
- `IQueryCondition.java` — 条件元数据接口已定义（复杂方法为 stub，子类型暂不需要）
- `AbstractWrapper.java` — conditionList + addCondition()，复杂方法空实现
- `DefaultQueryWrapper.java` — 23 个基础方法全部已实现
- `IBasePage.java` / `BasePage.java` / `BasePageDTO.java` — 分页抽象完整

---

### Task 1: 创建 AbstractBaseService

**Files:**
- Create: `src/main/java/com/sanye/strategy/common/base/AbstractBaseService.java`

**Interfaces:**
- Implements: `IService<T>`
- Produces: 14 个 `protected abstract doXxx()` 方法、4 个 hook 方法、14 个 IService 接口实现

- [ ] **Step 1: 编写 AbstractBaseService.java**

```java
package com.sanye.strategy.common.base;

import com.sanye.strategy.common.model.IBasePage;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/**
 * <p>
 * 抽象 Service 基类 — 模板方法骨架
 * </p>
 * <p>
 * 完全解耦 ORM 框架，不依赖 MyBatis-Plus。
 * 定义 14 个抽象 {@code doXxx} 方法供子类实现，
 * 配合 before/after 钩子实现横切关注点统一处理。
 * </p>
 * <p>
 * 类层次：
 * <pre>
 *   IBaseService&lt;T&gt; → IService&lt;T&gt; → AbstractBaseService&lt;T&gt; → MpBaseServiceImpl&lt;M, T&gt;
 * </pre>
 * </p>
 *
 * @param <T> 实体类型
 * @author 31372
 */
public abstract class AbstractBaseService<T> implements IService<T> {

    // ==================== 新增相关 ====================

    @Override
    public boolean insert(T entity) {
        beforeInsert(entity);
        boolean result = doInsert(entity);
        afterInsert(entity);
        return result;
    }

    @Override
    public boolean saveOrUpdate(T entity) {
        return doSaveOrUpdate(entity);
    }

    @Override
    public boolean insertBatch(Collection<T> entityList) {
        return doInsertBatch(entityList);
    }

    @Override
    public boolean saveOrUpdateBatch(Collection<T> entityList) {
        return doSaveOrUpdateBatch(entityList);
    }

    // ==================== 更新相关 ====================

    @Override
    public boolean updateById(T entity) {
        beforeUpdate(entity);
        boolean result = doUpdateById(entity);
        afterUpdate(entity);
        return result;
    }

    @Override
    public boolean updateBatch(Collection<T> entityList) {
        return doUpdateBatch(entityList);
    }

    // ==================== 删除相关 ====================

    @Override
    public boolean deleteById(Serializable id) {
        return doDeleteById(id);
    }

    @Override
    public boolean deleteBatch(Collection<? extends Serializable> idList) {
        return doDeleteBatch(idList);
    }

    // ==================== 查询相关 ====================

    @Override
    public T getById(Serializable id) {
        return doGetById(id);
    }

    @Override
    public List<T> listByIds(Collection<? extends Serializable> ids) {
        return doListByIds(ids);
    }

    @Override
    public T getOne(IWrapper<T> queryWrapper) {
        return doGetOne(queryWrapper);
    }

    @Override
    public List<T> list(IWrapper<T> queryWrapper) {
        return doList(queryWrapper);
    }

    @Override
    public long count(IWrapper<T> queryWrapper) {
        return doCount(queryWrapper);
    }

    @Override
    public IBasePage<T> page(IBasePage<T> page, IWrapper<T> queryWrapper) {
        return doPage(page, queryWrapper);
    }

    // ==================== 抽象 doXxx 方法（子类必须实现） ====================

    protected abstract boolean doInsert(T entity);

    protected abstract boolean doUpdateById(T entity);

    protected abstract boolean doDeleteById(Serializable id);

    protected abstract T doGetById(Serializable id);

    protected abstract List<T> doListByIds(Collection<? extends Serializable> ids);

    protected abstract T doGetOne(IWrapper<T> queryWrapper);

    protected abstract List<T> doList(IWrapper<T> queryWrapper);

    protected abstract long doCount(IWrapper<T> queryWrapper);

    protected abstract IBasePage<T> doPage(IBasePage<T> page, IWrapper<T> queryWrapper);

    protected abstract boolean doSaveOrUpdate(T entity);

    protected abstract boolean doInsertBatch(Collection<T> entityList);

    protected abstract boolean doUpdateBatch(Collection<T> entityList);

    protected abstract boolean doDeleteBatch(Collection<? extends Serializable> idList);

    protected abstract boolean doSaveOrUpdateBatch(Collection<T> entityList);

    // ==================== 钩子方法（子类可选覆盖） ====================

    /**
     * 新增前钩子，子类可覆盖以实现数据校验、字段填充等
     */
    protected void beforeInsert(T entity) {
    }

    /**
     * 新增后钩子，子类可覆盖以实现日志记录、事件发布等
     */
    protected void afterInsert(T entity) {
    }

    /**
     * 更新前钩子，子类可覆盖以实现数据校验、字段填充等
     */
    protected void beforeUpdate(T entity) {
    }

    /**
     * 更新后钩子，子类可覆盖以实现日志记录、事件发布等
     */
    protected void afterUpdate(T entity) {
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/sanye/strategy/common/base/AbstractBaseService.java
git commit -m "feat: add AbstractBaseService with template method and doXxx hooks"
```

---

### Task 2: 创建 MpBaseServiceImpl

**Files:**
- Create: `src/main/java/com/sanye/strategy/common/base/MpBaseServiceImpl.java`

**Interfaces:**
- Consumes: `AbstractBaseService<T>` (Task 1), `IWrapper<T>`, `IQueryCondition`, `IBasePage<T>`, `BasePage<T>`
- Produces: `MpBaseServiceImpl<M extends BaseMapper<T>, T>` — 业务 Service 实现类的直接父类

- [x] **Step 1: 编写 MpBaseServiceImpl.java**

```java
package com.sanye.strategy.common.base;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanye.strategy.common.model.BasePage;
import com.sanye.strategy.common.model.IBasePage;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/**
 * <p>
 * MyBatis-Plus 桥接适配器 — 框架耦合的唯一位置
 * </p>
 * <p>
 * 负责将自定义抽象（{@link IWrapper}、{@link IBasePage}）转换为
 * MyBatis-Plus 原生类型（{@code QueryWrapper}、{@code Page}）。
 * 业务 Service 实现类继承此类，获得完整 CRUD 能力。
 * </p>
 * <p>
 * 类层次：
 * <pre>
 *   AbstractBaseService&lt;T&gt; → MpBaseServiceImpl&lt;M, T&gt; → XxxServiceImpl
 * </pre>
 * </p>
 *
 * @param <M> Mapper 类型，必须继承 {@link BaseMapper}
 * @param <T> 实体类型
 * @author 31372
 */
public abstract class MpBaseServiceImpl<M extends BaseMapper<T>, T>
        extends AbstractBaseService<T> {

    @Autowired
    protected M baseMapper;

    // ==================== IWrapper → QueryWrapper 转换 ====================

    /**
     * 将自定义查询条件转换为 MyBatis-Plus QueryWrapper
     * <p>
     * 遍历条件列表，根据操作符映射到对应的 MP API。
     * 复杂操作符（or/and/nested/apply/exists/notExists/select/last）
     * 因 AbstractWrapper 为空实现，暂不处理。
     * </p>
     */
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
                case "between"    -> {
                    Object[] a = (Object[]) cond.getValue();
                    qw.between(cond.getColumn(), a[0], a[1]);
                }
                case "notBetween" -> {
                    Object[] a = (Object[]) cond.getValue();
                    qw.notBetween(cond.getColumn(), a[0], a[1]);
                }
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
                case "having"  -> {
                    Object[] a = (Object[]) cond.getValue();
                    qw.having((String) a[0], (Object[]) a[1]);
                }
                // 排序
                case "orderByAsc"  -> qw.orderByAsc((String[]) cond.getValue());
                case "orderByDesc" -> qw.orderByDesc((String[]) cond.getValue());
                case "orderBy"     -> {
                    Object[] a = (Object[]) cond.getValue();
                    qw.orderBy((Boolean) a[0], (String[]) a[1]);
                }
                // 复杂操作符（or/and/nested/apply/exists/notExists/select/last）
                // AbstractWrapper 空实现，不会产生对应 IQueryCondition，跳过
                default -> {
                }
            }
        }
        return qw;
    }

    /**
     * IBasePage → MyBatis-Plus Page 转换
     */
    protected Page<T> toMpPage(IBasePage<T> page) {
        return new Page<>(page.getCurrent(), page.getSize(), page.searchCount());
    }

    /**
     * MyBatis-Plus IPage → IBasePage 回填
     */
    protected IBasePage<T> fromMpPage(Page<T> mpPage) {
        BasePage<T> result = new BasePage<>();
        result.setRecords(mpPage.getRecords());
        result.setTotal(mpPage.getTotal());
        result.setSize(mpPage.getSize());
        result.setCurrent(mpPage.getCurrent());
        return result;
    }

    // ==================== doXxx 实现 — 委托给 baseMapper ====================

    @Override
    protected boolean doInsert(T entity) {
        return baseMapper.insert(entity) > 0;
    }

    @Override
    protected boolean doUpdateById(T entity) {
        return baseMapper.updateById(entity) > 0;
    }

    @Override
    protected boolean doDeleteById(Serializable id) {
        return baseMapper.deleteById(id) > 0;
    }

    @Override
    protected T doGetById(Serializable id) {
        return baseMapper.selectById(id);
    }

    @Override
    protected List<T> doListByIds(Collection<? extends Serializable> ids) {
        return baseMapper.selectBatchIds(ids);
    }

    @Override
    protected T doGetOne(IWrapper<T> queryWrapper) {
        return baseMapper.selectOne(toMpWrapper(queryWrapper));
    }

    @Override
    protected List<T> doList(IWrapper<T> queryWrapper) {
        return baseMapper.selectList(toMpWrapper(queryWrapper));
    }

    @Override
    protected long doCount(IWrapper<T> queryWrapper) {
        return baseMapper.selectCount(toMpWrapper(queryWrapper));
    }

    @Override
    protected IBasePage<T> doPage(IBasePage<T> page, IWrapper<T> queryWrapper) {
        Page<T> mpResult = baseMapper.selectPage(toMpPage(page), toMpWrapper(queryWrapper));
        return fromMpPage(mpResult);
    }

    @Override
    protected boolean doSaveOrUpdate(T entity) {
        // MyBatis-Plus BaseMapper 无 saveOrUpdate，按 ID 是否存在判断
        // 通过反射获取 ID 并判断，简单但可靠
        try {
            java.lang.reflect.Field idField = entity.getClass().getSuperclass()
                    .getDeclaredField("id");
            idField.setAccessible(true);
            Object id = idField.get(entity);
            if (id != null) {
                return baseMapper.updateById(entity) > 0;
            }
        } catch (Exception ignored) {
        }
        return baseMapper.insert(entity) > 0;
    }

    @Override
    protected boolean doInsertBatch(Collection<T> entityList) {
        if (entityList == null || entityList.isEmpty()) {
            return false;
        }
        for (T entity : entityList) {
            baseMapper.insert(entity);
        }
        return true;
    }

    @Override
    protected boolean doUpdateBatch(Collection<T> entityList) {
        if (entityList == null || entityList.isEmpty()) {
            return false;
        }
        for (T entity : entityList) {
            baseMapper.updateById(entity);
        }
        return true;
    }

    @Override
    protected boolean doDeleteBatch(Collection<? extends Serializable> idList) {
        if (idList == null || idList.isEmpty()) {
            return false;
        }
        return baseMapper.deleteByIds(idList) > 0;
    }

    @Override
    protected boolean doSaveOrUpdateBatch(Collection<T> entityList) {
        if (entityList == null || entityList.isEmpty()) {
            return false;
        }
        for (T entity : entityList) {
            doSaveOrUpdate(entity);
        }
        return true;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/sanye/strategy/common/base/MpBaseServiceImpl.java
git commit -m "feat: add MpBaseServiceImpl MP bridge with IWrapper→QueryWrapper mapping"
```

---

### Task 3: 创建 R 统一响应包装

**Files:**
- Create: `src/main/java/com/sanye/strategy/common/response/R.java`

**Interfaces:**
- Produces: `R<T>` — 全项目统一响应格式，3 个静态工厂方法

- [x] **Step 1: 创建 response 包目录下的 R.java**

```java
package com.sanye.strategy.common.response;

import java.io.Serializable;
import java.time.Instant;

/**
 * <p>
 * 统一响应包装
 * </p>
 * <p>
 * 所有 Controller 接口返回值必须使用此类包装。
 * timestamp 使用 JDK8 Instant API 生成 ISO-8601 标准时间字符串。
 * </p>
 *
 * @param <T> 响应数据类型
 * @author 31372
 */
public class R<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 成功状态码 */
    public static final int CODE_SUCCESS = 0;

    /** 默认失败状态码 */
    public static final int CODE_FAIL = -1;

    /** 状态码，0 表示成功 */
    private int code;

    /** 提示信息 */
    private String message;

    /** 响应数据 */
    private T data;

    /** 响应时间，ISO-8601 格式 */
    private String timestamp;

    private R() {
        this.timestamp = Instant.now().toString();
    }

    private R(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = Instant.now().toString();
    }

    /**
     * 成功响应（带数据）
     *
     * @param <T>  数据类型
     * @param data 响应数据
     * @return R 实例
     */
    public static <T> R<T> ok(T data) {
        return new R<>(CODE_SUCCESS, "success", data);
    }

    /**
     * 成功响应（无数据）
     *
     * @param <T> 数据类型
     * @return R 实例
     */
    public static <T> R<T> ok() {
        return new R<>(CODE_SUCCESS, "success", null);
    }

    /**
     * 失败响应
     *
     * @param <T>     数据类型
     * @param code    业务错误码
     * @param message 错误提示
     * @return R 实例
     */
    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null);
    }

    // ==================== Getter / Setter ====================

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/sanye/strategy/common/response/R.java
git commit -m "feat: add R<T> unified response wrapper with ISO-8601 timestamp"
```

---

### Task 4: 创建 BaseController

**Files:**
- Create: `src/main/java/com/sanye/strategy/common/controller/BaseController.java`

**Interfaces:**
- Consumes: `IBaseService<T>` (已有), `IWrapper<T>`, `IBasePage<T>`, `R<T>` (Task 3)
- Produces: `BaseController<T, S, Q, V>` — 所有业务 Controller 的基类

- [x] **Step 1: 创建 controller 包并编写 BaseController.java**

```java
package com.sanye.strategy.common.controller;

import com.sanye.strategy.common.base.IBaseService;
import com.sanye.strategy.common.base.IWrapper;
import com.sanye.strategy.common.model.IBasePage;
import com.sanye.strategy.common.response.R;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * <p>
 * Controller 抽象基类 — 标准化 GET 查询端点
 * </p>
 * <p>
 * 提供 {@code GET /{id}} 和 {@code GET /page} 两个标准查询端点。
 * 新增（POST）、更新（PUT）、删除（DELETE）因含业务逻辑，由子类自行实现。
 * </p>
 * <p>
 * 泛型说明：
 * <ul>
 *   <li>{@code T} — 实体类型（Domain 类）</li>
 *   <li>{@code S} — Service 接口，必须继承 {@link IBaseService}</li>
 *   <li>{@code Q} — 请求 DTO，无父类约束，分页参数由组合方式提供</li>
 *   <li>{@code V} — 响应 VO，无父类约束</li>
 * </ul>
 * </p>
 *
 * @param <T> 实体类型
 * @param <S> Service 类型，必须继承 {@link IBaseService}{@code <T>}
 * @param <Q> 查询请求 DTO
 * @param <V> 响应 VO
 * @author 31372
 */
public abstract class BaseController<T, S extends IBaseService<T>, Q, V> {

    @Autowired
    protected S service;

    // ==================== 子类必须实现的抽象方法 ====================

    /**
     * 实体 → VO 转换
     *
     * @param entity 领域实体
     * @return 视图对象
     */
    protected abstract V toVO(T entity);

    /**
     * 从请求 DTO 构建查询条件
     *
     * @param query 查询请求 DTO
     * @return 查询条件包装器
     */
    protected abstract IWrapper<T> buildQuery(Q query);

    /**
     * 从请求 DTO 提取分页参数
     *
     * @param query 查询请求 DTO
     * @return 分页对象
     */
    protected abstract IBasePage<T> buildPage(Q query);

    // ==================== 标准化查询端点 ====================

    /**
     * 根据 ID 查询单条记录
     *
     * @param id 主键 ID
     * @return 统一响应
     */
    @GetMapping("/{id}")
    public R<V> getById(@PathVariable Long id) {
        T entity = service.getById(id);
        if (entity == null) {
            return R.fail(404, "记录不存在");
        }
        return R.ok(toVO(entity));
    }

    /**
     * 分页条件查询
     *
     * @param query 查询请求 DTO
     * @return 统一响应，data 为分页 VO
     */
    @GetMapping("/page")
    public R<IBasePage<V>> page(@Valid Q query) {
        IBasePage<T> pageResult = service.page(buildPage(query), buildQuery(query));
        return R.ok(pageResult.convert(this::toVO));
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/sanye/strategy/common/controller/BaseController.java
git commit -m "feat: add BaseController with standardized GET query endpoints"
```

---

### Task 5: 验证编译通过

**Files:**
- 无新建/修改

- [x] **Step 1: Maven 编译验证**

```bash
./mvnw clean compile -DskipTests
```

Expected: `BUILD SUCCESS`，无编译错误。

- [ ] **Step 2: 若有编译错误，根据报错修正后重新验证**

---

## 文件变更总览

| 文件 | 动作 | 说明 |
|------|------|------|
| `common/base/AbstractBaseService.java` | 新建 | 模板方法 + 14 doXXX + 4 hook |
| `common/base/MpBaseServiceImpl.java` | 新建 | IWrapper→QueryWrapper 桥接，23 个操作符映射 |
| `common/response/R.java` | 新建 | 统一响应包装，ISO-8601 时间字符串 |
| `common/controller/BaseController.java` | 新建 | 四泛型，`GET /{id}` + `GET /page` |

注：`IBaseService.java`、`IService.java`、`IQueryCondition.java`、`AbstractWrapper.java`、`DefaultQueryWrapper.java`、`IBasePage.java`、`BasePage.java`、`BasePageDTO.java` 均已完成，本次不修改。

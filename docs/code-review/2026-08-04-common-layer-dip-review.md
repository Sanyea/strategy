# Common 层 DIP 架构 Code Review 报告

> 日期：2026-08-04
> 范围：`master...dev` 新增的 common 层 DIP 抽象（19 文件，2205 行）
> 方式：10 独立审查角度 × 高召回 → 去重（72 候选 → 41）→ 1 票 3 态验证（41 保留）→ 缺口扫荡（+4）
> 结论：41 CONFIRMED / 2 PLAUSIBLE 保留，0 REFUTED；去重后 27 个独立缺陷

---

## 严重度分级

| 级别 | 数量 | 说明 |
|------|------|------|
| 🔴 严重 | 4 | 核心 CRUD 语义错误，数据正确性受损 |
| 🟠 高 | 6 | 静默错误 SQL / 审计丢失 / 分页失效 |
| 🟡 中 | 9 | 边界输入崩溃 / 约束冲突 / 越权暴露 |
| ⚪ 约定/质量 | 8 | 阿里规范 / 效率 / 死代码 |

---

## 🔴 严重

### 1. saveOrUpdate 反射取 id 失败 → 永远 INSERT

- 文件：`src/main/java/com/sanye/strategy/common/base/MpBaseServiceImpl.java:179`
- 验证：CONFIRMED

`doSaveOrUpdate` 用 `entity.getClass().getSuperclass().getDeclaredField("id")` 取主键。但 `id` 声明在 `SimpleBaseEntity`（`BaseEntity` 的父类），对 `BaseEntity` 子类（如 `UmsUser extends BaseEntity extends SimpleBaseEntity`）而言，`getSuperclass()` 返回 `BaseEntity`，不含 `id` 字段 → 抛 `NoSuchFieldException`，被空 catch 吞掉 → 落到 `baseMapper.insert()`。

触发：`UmsUser u = new UmsUser(); u.setId(existingId); service.saveOrUpdate(u)` → 重复键错误或新建行，而非更新。`saveOrUpdateBatch` 逐行继承。

### 2. 批量方法无事务 + 忽略逐行结果

- 文件：`MpBaseServiceImpl.java:196`（insertBatch）/ `:207`（updateBatch）/ `:222`（saveOrUpdateBatch）
- 验证：CONFIRMED

三个批量方法均无 `@Transactional`（全库 grep 零命中），且循环丢弃 `insert/updateById` 返回的受影响行数，无条件 `return true`。

触发：`updateBatch` 10 条中 3 条已被逻辑删除（`@TableLogic` → `WHERE deleted=0` 影响 0 行）→ 返回 true，调用方以为全成功。循环中第 N 行唯一键冲突 → 异常传播，但前 N-1 行已自动提交 → 半写，与干净失败无法区分。

### 3. 分页完全失效（无拦截器 + 缺 jsqlparser 依赖）

- 文件：`MpBaseServiceImpl.java:170`，`pom.xml:34`
- 验证：CONFIRMED

`doPage` 调 `baseMapper.selectPage`，但项目未注册 `MybatisPlusInterceptor` / `PaginationInnerInterceptor`（`common/config` 仅占位 package-info），MP 的 LIMIT 改写与 COUNT 只在该拦截器中发生。且 pom 缺 `mybatis-plus-jsqlparser` 构件（MP 3.5.9 起拦截器依赖它），修复时直接无法编译。

触发：`GET /page?page=1&size=10` → 返回整表数据、`total=0`、`getPages()==0`、`isHasMore()==false`，每页返回同一全量结果，无报错。

### 4. IWrapper 复杂操作符为静默空实现 → 错误 SQL + 敏感列暴露

- 文件：`AbstractWrapper.java:53-92`，`MpBaseServiceImpl.java:100`
- 验证：CONFIRMED

`or()/and()/nested()/apply()/exists()/notExists()/select()/last()` 全部 `return this` 不记录任何条件；`toMpWrapper` 的 `default -> {}` 静默丢弃未识别操作符。无编译期或运行期报错。

触发：
- `wrapper.select("id","username")` 被丢弃 → `list()` 返回全列，含 `password`/`salt`/`idCardNo` → 敏感数据越权暴露；
- `eq("status",1).or().eq("userType",2)` → 变 AND 语义，返回错误行集；
- `last("LIMIT 1")` 被丢弃 → `getOne` 遇多行抛 `TooManyResultsException`。

---

## 🟠 高

### 5. 审计字段永不填充（缺 MetaObjectHandler）

- 文件：`BaseEntity.java:33`，`SimpleBaseEntity.java:45`
- 验证：CONFIRMED

`createUserId`/`updateUserId` 标 `@TableField(fill = FieldFill.INSERT/INSERT_UPDATE)`，但项目无 `MetaObjectHandler` Bean（全库 grep 零命中）。MP 的 FieldFill 无 handler 即惰性。`createTime`/`updateTime` 仅靠 DDL `CURRENT_TIMESTAMP` 兜底。

触发：`insert(user)` → `create_user_id`/`update_user_id` 恒为 NULL（列 `DEFAULT NULL`，无 DB 默认）→ CLAUDE.md 宣称的「操作人审计」静默失效。

### 6. getOne 多行直接抛异常

- 文件：`MpBaseServiceImpl.java:155`
- 验证：CONFIRMED

`doGetOne` 委托 `baseMapper.selectOne(wrapper)`，MP 3.5.17 多行时抛 `TooManyResultsException`；常规防护 `last("LIMIT 1")` 本身是空实现。

触发：`getOne(eq("phone","13800000000"))` 命中多行（`uk_phone` 允许 NULL，`''` 可重复）→ 未处理异常 → 500（无 `@ControllerAdvice`）。

### 7. orderBy 空列崩溃

- 文件：`MpBaseServiceImpl.java:96`
- 验证：CONFIRMED

`orderBy` case 无条件读 `cols[0]`。`wrapper.orderBy(true)`（无列）→ 存 `Object[]{true, new String[0]}` → `cols[0]` 空数组越界 → `ArrayIndexOutOfBoundsException` → 500。兄弟方法 `orderByAsc/orderByDesc`（`Arrays.asList`）安全，仅 `orderBy` 未防护。

### 8. in() 传 List 变单参绑定

- 文件：`DefaultQueryWrapper.java:78`，`MpBaseServiceImpl.java:80`
- 验证：CONFIRMED

`in(String, Object... values)` 将 `List` 作为单个 varargs 元素存储；`toMpWrapper` 强转 `Object[]` 展开，MP 收到 `Object[]{list}` → 生成 `WHERE id IN (?)` 并绑定 List 为标量。

触发：`wrapper.in("id", List.of(1L,2L,3L))` → 驱动 SQLException（`Can't set IN parameter to type ArrayList`）。仅当调用方手动 `.toArray()` 才正常。

### 9. eq 传 null → `= NULL` 永不匹配

- 文件：`DefaultQueryWrapper.java:8`
- 验证：CONFIRMED

`eq/ne/gt/ge/lt/le` 将 null 直传 `QueryWrapper`，生成 `column = NULL`（永不匹配），且抽象层丢弃了 MP 的条件重载 `eq(boolean, ...)`（常规空值防护）。

触发：动态查询 `wrapper.eq("phone", dto.getPhone())` 字段为 null → 静默返回空结果，搜索结果消失无报错。

### 10. 逻辑删除 + 唯一键 → 标识永久占用

- 文件：`sql/user.sql:41-43`
- 验证：CONFIRMED

`@TableLogic` 逻辑删除只翻 `deleted=1`，行仍在表内；`uk_username`/`uk_phone`/`uk_email` 唯一键不区分。

触发：删号后重注册同名用户名/手机号 → `DuplicateKeyException`，标识永远无法复用。

---

## 🟡 中

### 11. 空手机号/邮箱撞唯一键

- 文件：`sql/user.sql:42-43`
- 验证：CONFIRMED

`phone DEFAULT ''`、`phone_country_code DEFAULT '+86'`、`email DEFAULT ''` + 唯一键。两个都不填手机号的用户 → DB 默认 `''` → 第二个插入报 `Duplicate entry for key 'uk_phone'`。

### 12. JSON 列无类型处理器

- 文件：`src/main/java/com/sanye/strategy/domain/UmsUserProfile.java:86`
- 验证：CONFIRMED

`extInfo` 为 `Object` 类型映射 MySQL JSON 列，无 `JacksonTypeHandler`、无 `@TableName(autoResultMap=true)`。

触发：`insert(profile)` 且 extInfo 存 Map/List → `setObject` 抛 `No conversion`；读回时 JSON 变成原始 String，永不解析为结构化数据。

### 13. 批量路径绕过钩子

- 文件：`AbstractBaseService.java:46`（insertBatch）/ `:41`（saveOrUpdate）/ `:67`（updateBatch）
- 验证：CONFIRMED

`saveOrUpdate` 与全部批量入口直调 `doXxx`，跳过 `beforeInsert/afterInsert/beforeUpdate/afterUpdate` 钩子链（仅单条 `insert()/updateById()` 走钩子）。钩子是当前唯一填充机制（无 MetaObjectHandler）。

触发：子类在 `beforeInsert` 填 `createUserId` 或做校验 → 单条 `insert()` 生效，`insertBatch()` 存 NULL，同一实体因 API 不同审计/校验不一致。

### 14. R.fail(404) 但 HTTP 200

- 文件：`BaseController.java:79`
- 验证：CONFIRMED

`getById` 缺失记录返回 `R.fail(404, "记录不存在")`，HTTP 层是 200。404 不是 R 文档化的业务码（仅 0/-1）。

触发：`GET /users/999999` → HTTP 200 + `{"code":404}`，HTTP 层与成功无法区分，网关/监控期望的 404 永远不会出现。

### 15. DATETIME 时区偏移

- 文件：`sql/user.sql:38-39`
- 验证：PLAUSIBLE

`create_time DATETIME DEFAULT CURRENT_TIMESTAMP` 写 DB 会话时区；JDBC URL 仅设 `serverTimezone=Asia/Shanghai`（不影响 DATETIME 读取）。若 DB 主机为 UTC → 落库 UTC 墙上时间 → 读回比北京时间慢 8 小时。需查 `@@time_zone` 确认主机时区。

### 16. 抽象层泄漏 MP 特定表示

- 文件：`MpBaseServiceImpl.java:51`
- 验证：PLAUSIBLE

`groupBy`/`orderByAsc`/`orderByDesc` 以哨兵字符串作 `getColumn()`（如 `getColumn()=="groupBy"`），`between`/`having`/`orderBy` 值形状是裸 `Object[]`（`{val1,val2}`、`{sql,params}`、`{asc,cols}`），布局仅 `toMpWrapper` 的 switch 能解读。第二个 ORM 适配器或任何非 MP 消费者读 `getConditions()` 需逆向工程。当前无第二消费方，故 PLAUSIBLE。

---

## ⚪ 约定 / 质量

| # | 文件:行 | 问题 | 类型 |
|---|---------|------|------|
| 17 | `MpBaseServiceImpl.java:150` | `selectBatchIds` 已废弃，应改 `selectByIds`（与 `deleteByIds` 一致） | 约定 |
| 18 | `MpBaseServiceImpl.java:38` | `baseMapper` 字段注入，应构造器注入（阿里规范） | 约定 |
| 19 | `MpBaseServiceImpl.java:35` | 适配器 Javadoc 缺「优缺点分析 + UML 类图/时序图」（CLAUDE.md 约束 2） | 约定 |
| 20 | `IBaseService.java:12` | 10 个 CRUD 方法用 `//` 注释非 Javadoc，接口无类描述 | 约定 |
| 21 | `MpBaseServiceImpl.java:197` | 批量循环逐条 SQL（N 次往返），未用 MP 3.5.17 `insert(Collection)` 批量会话 | 效率 |
| 22 | `IBasePage.java:63` | `searchCount()` 硬编码 true，分页恒多跑一次 COUNT，无法关闭 | 效率 |
| 23 | `MpBaseServiceImpl.java:54` | 操作符字符串在 DefaultQueryWrapper 与 switch 双份维护，无编译期校验，拼写错误静默丢条件 | 质量 |
| 24 | `MpBaseServiceImpl.java:186` | `catch (Exception ignored) {}` 空 catch 吞掉 `NoSuchFieldException`（正是缺陷 1 根因），无日志（阿里规范要求至少 warn） | 质量 |
| 25 | `R.java:40` | 私有无参构造器死代码，三个工厂全用三参构造 | 质量 |
| 26 | `BasePageDTO.java:26/31` | 仅含 `of()` 与一行 `isHasMore()`（`getPages()` 已有 default 实现），重复空转；`toString()` 输出对象哈希，无分页数据 | 质量 |
| 27 | `IBasePage.java:26` | `offset()` 无任何调用点（toMpPage 直传 current/size），死 API | 质量 |

---

## 修复优先级建议

1. **缺陷 1** — `saveOrUpdate` 改委托 MP `ServiceImpl.saveOrUpdate`（经 TableInfo 读 `@TableId`），或改走 `getId()` 并沿继承链上溯。破坏面最大，优先。
2. **缺陷 2/13** — 批量方法补 `@Transactional`、聚合逐行结果、钩子链统一到 doXxx 层。
3. **缺陷 3** — 补 `mybatis-plus-jsqlparser` 依赖 + 注册分页拦截器 Bean。
4. **缺陷 4** — 未实现的复杂操作符改为 `UnsupportedOperationException`（快速失败），或补全实现。
5. **缺陷 5** — 补 `MetaObjectHandler` Bean，审计字段真正生效。
6. 其余按严重度表顺序处理。

---

*验证方法：候选经 1 票 3 态验证（CONFIRMED/PLAUSIBLE/REFUTED），CONFIRMED 需引用确切代码行；MP API 行为经 `javap` 反编译 `mybatis-plus-core-3.5.17.jar` 核实。*

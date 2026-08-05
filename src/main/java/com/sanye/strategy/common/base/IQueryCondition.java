package com.sanye.strategy.common.base;

import java.util.List;

/**
 * <p>
 * 查询条件元数据 — 一条条件的存储契约
 * </p>
 * <p>
 * 由 {@link AbstractWrapper} 构建时写入，{@link MpBaseServiceImpl} 映射为
 * MyBatis-Plus {@code QueryWrapper} 时读取。操作符字符串为"写入端/读取端双份维护"，
 * 新增操作符须同步修改 {@link AbstractWrapper}（写）与 {@link MpBaseServiceImpl}
 * 的 applyConditions 映射（读）。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：查询条件模型的载体，普通条件用 column+op+value，复杂条件用 op+value+params/children。</li>
 *   <li>优缺点：单接口覆盖全部操作符，无需为每个操作符建类；默认方法返回 null，普通条件零负担。
 *       缺点：操作符以字符串约定，编译期无校验，依赖双端一致。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
public interface IQueryCondition {

    /** 字段名（复杂操作符为 null） */
    String getColumn();

    /** 操作符：eq/like/gt/ge... 及 or/and/nested/apply/exists/notExists/select/last */
    String getOperator();

    /** 值：普通条件为值；between/groupBy/orderByAsc 等为数组或哨兵结构 */
    Object getValue();

    /**
     * 附加绑定参数（{@code and}/{@code apply}/{@code exists}/{@code notExists} 的
     * {@code {n}} 占位符绑定值），无则返回 null
     *
     * @return 绑定参数数组
     */
    default Object[] getParams() {
        return null;
    }

    /**
     * 子条件列表（{@code nested} 嵌套条件），无则返回 null
     *
     * @return 嵌套条件列表
     */
    default List<IQueryCondition> getChildren() {
        return null;
    }

    /**
     * 本条条件是否由 {@code or()} 标记为 OR 拼接（非 WHERE 段条件恒为 false）
     *
     * @return true 表示该条件与前一条条件 OR 拼接
     */
    default boolean isOr() {
        return false;
    }
}

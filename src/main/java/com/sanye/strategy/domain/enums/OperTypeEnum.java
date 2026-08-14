package com.sanye.strategy.domain.enums;

import lombok.Getter;

/**
 * <p>
 * 操作日志类型枚举
 * </p>
 * <p>
 * 与 {@code ums_oper_log.oper_type} 列（TINYINT UNSIGNED）对应：1-新增 2-修改 3-删除 4-查询
 * 5-授权 6-导入 7-导出 8-其他；{@link #EVICT_USER} 9-踢下线 为 RBAC 本期新增。
 * 注：{@code sql/oper_log.sql} 表注释仅列到 8，与枚举 9 存在注释口径差异（DB 列宽 TINYINT 容纳 9，
 * 不改 DDL）。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：操作类型受控词表，供 {@code OperLogReq.type} 与 {@code OperLogService} 落库映射。</li>
 *   <li>优缺点：类型安全、可扩展；新增类型改枚举即可（列宽 TINYINT 容纳 9）。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Getter
public enum OperTypeEnum {
    CREATE(1, "新增"), UPDATE(2, "修改"), DELETE(3, "删除"), QUERY(4, "查询"),
    GRANT(5, "授权"), IMPORT(6, "导入"), EXPORT(7, "导出"), OTHER(8, "其他"),
    EVICT_USER(9, "踢下线");

    /**
     * 操作类型码
     */
    private final int code;

    /**
     * 操作类型描述
     */
    private final String desc;

    OperTypeEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据 code 获取枚举
     *
     * @param code 操作类型码
     * @return 对应枚举值，未匹配返回 null
     */
    public static OperTypeEnum valueOf(int code) {
        for (OperTypeEnum e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return null;
    }
}

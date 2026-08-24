package com.sanye.strategy.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sanye.strategy.domain.enums.OperTypeEnum;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * <p>
 * 操作日志持久化对象（PO）— 仅插入，无更新无逻辑删除，不继承 {@code SimpleBasePO}
 * </p>
 * <p>
 * 对应 {@code ums_oper_log} 表（sql/oper_log.sql）：无 deleted/update_time 列，
 * 主键手工 {@code @TableId(ASSIGN_ID)}，create_time 自动填充。
 * 写入经 {@link com.sanye.strategy.application.rbac.OperLogService}（REQUIRES_NEW 独立事务，
 * 业务回滚也留痕、写审计失败降级不影响主流程）。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：操作日志表持久化载体，Mapper 操作对象。</li>
 *   <li>优缺点：独立 PO 无继承，走 {@code BaseMapper.insert}；create_time 由 MetaObjectHandler
 *       （strictInsertFill 按字段存在性填充，无 deleted/updateTime 字段自动跳过）自动填充，
 *       oper_time 由业务显式写入。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Data
@TableName(value = "ums_oper_log")
public class UmsOperLogPO {

    /**
     * 主键ID（雪花）
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 操作用户ID
     */
    private Long userId;

    /**
     * 操作用户账号快照
     */
    private String username;

    /**
     * 操作模块
     */
    private String operModule;

    /**
     * 操作动作
     */
    private String operAction;

    /**
     * 操作说明
     */
    private String operDesc;

    /**
     * 操作类型 {@link OperTypeEnum}
     */
    private Integer operType;

    /**
     * HTTP方法
     */
    private String requestMethod;

    /**
     * 请求URI
     */
    private String requestUri;

    /**
     * 请求参数（建议存JSON或截断）
     */
    private String requestParams;

    /**
     * 请求体
     */
    private String requestBody;

    /**
     * 响应码
     */
    private String responseCode;

    /**
     * 响应信息
     */
    private String responseMsg;

    /**
     * 耗时（毫秒）
     */
    private Integer costTime;

    /**
     * 操作IP
     */
    private String operIp;

    /**
     * 浏览器UA
     */
    private String userAgent;

    /**
     * 操作结果 0-失败 1-成功
     */
    private Integer status;

    /**
     * 错误信息
     */
    private String errorMsg;

    /**
     * 链路追踪ID（MDC traceId）
     */
    private String traceId;

    /**
     * 操作对象实体/表名
     */
    private String targetEntity;

    /**
     * 操作对象主键ID
     */
    private Long targetId;

    /**
     * 操作者类型：1-人工用户 2-系统任务
     */
    private Integer operatorType;

    /**
     * 字段变更 diff（JSON 数组字符串，规格 7.1；仅 INSERT，不参与脱敏——凭据剔除/PII 掩码已产生端完成）
     */
    private String changeDiff;

    /**
     * 操作时间
     */
    private LocalDateTime operTime;

    /**
     * 创建时间（自动填充）
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}

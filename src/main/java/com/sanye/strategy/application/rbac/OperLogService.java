package com.sanye.strategy.application.rbac;

import com.sanye.strategy.common.util.IpUtils;
import com.sanye.strategy.domain.enums.OperTypeEnum;
import com.sanye.strategy.infrastructure.persistence.mapper.UmsOperLogMapper;
import com.sanye.strategy.infrastructure.persistence.po.UmsOperLogPO;
import com.sanye.strategy.infrastructure.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <p>
 * 操作日志服务 — REQUIRES_NEW 独立事务：业务回滚也留痕；写审计失败不影响主流程
 * </p>
 * <p>
 * 每调用构建独立 {@link TransactionTemplate} 并设 {@code PROPAGATION_REQUIRES_NEW}，
 * 与业务事务（{@code TransactionConfig} 的共享模板）解耦——业务回滚后审计仍落库；
 * {@code record} 全程 try/catch 吞异常仅记 error，审计写失败绝不上抛。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：审计基础设施，供 RBAC 门面/定时任务等记录操作留痕。</li>
 *   <li>优缺点：REQUIRES_NEW 保证业务成败与审计隔离（业务回滚也留痕、审计失败不阻断业务）；
 *       代价为每记录一笔开独立事务（审计低频，可接受）。</li>
 *   <li>用户与 IP：用户经 {@link UserContext} 取（无上下文=后台脚本，落 NULL）；
 *       IP 经 {@code RequestContextHolder} 取请求（定时任务线程无请求上下文，落 NULL，不 NPE）。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OperLogService {

    private final PlatformTransactionManager txManager;
    private final UmsOperLogMapper operLogMapper;

    /**
     * 审计权威链 logger — logback 路由 audit.log（阶段1 经 Vector → MinIO Object Lock WORM，规格 7.6）
     */
    private static final org.slf4j.Logger AUDIT_LOG = org.slf4j.LoggerFactory.getLogger("AUDIT");

    /**
     * 审计 JSON 序列化器 — Boot4 默认 Jackson 3（tools.jackson）
     */
    private static final ObjectMapper AUDIT_MAPPER = new ObjectMapper();

    /**
     * 记录操作日志（REQUIRES_NEW 独立事务，异常吞掉降级不影响主流程）
     *
     * @param req 审计请求
     */
    public void record(OperLogReq req) {
        try {
            TransactionTemplate tpl = new TransactionTemplate(txManager);
            tpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tpl.executeWithoutResult(status -> {
                UserContext ctx = UserContext.get();
                // traceId 从 MDC 取（不信任调用方传，规格 7.3）；operatorType 逻辑推导（规格 7.4）
                String traceId = MDC.get("traceId");
                int operatorType = ctx == null ? 2 : 1;
                UmsOperLogPO po = new UmsOperLogPO();
                po.setUserId(ctx == null ? null : ctx.getUserId());
                po.setUsername(ctx == null ? null : String.valueOf(ctx.getUserId()));
                po.setOperModule(req.getModule());
                po.setOperAction(req.getAction());
                po.setOperDesc(req.getDesc());
                po.setOperType(req.getType() == null ? OperTypeEnum.OTHER.getCode() : req.getType().getCode());
                po.setRequestMethod(req.getRequestMethod());
                po.setRequestUri(req.getRequestUri());
                po.setOperIp(resolveClientIp());
                po.setUserAgent(req.getUserAgent());
                po.setStatus(req.isSuccess() ? 1 : 0);
                po.setErrorMsg(req.getErrorMsg());
                po.setTraceId(traceId);
                po.setTargetEntity(req.getTargetEntity());
                po.setTargetId(req.getTargetId());
                po.setOperatorType(operatorType);
                po.setChangeDiff(req.getChangeDiff());
                po.setOperTime(LocalDateTime.now());
                operLogMapper.insert(po);
                writeAuditFile(po);
            });
        } catch (Exception e) {
            log.error("审计日志写入失败，降级不影响主流程", e);
        }
    }

    /**
     * 审计权威链双写：结构化 JSON 落 audit.log（视图副本之外的 WORM 源，规格 7.6）；
     * 文件写失败仅记 error，不影响 DB 副本已落库的事实
     */
    private void writeAuditFile(UmsOperLogPO po) {
        try {
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("auditId", po.getId());
            audit.put("traceId", po.getTraceId());
            audit.put("userId", po.getUserId());
            audit.put("operatorType", po.getOperatorType());
            audit.put("module", po.getOperModule());
            audit.put("action", po.getOperAction());
            audit.put("targetEntity", po.getTargetEntity());
            audit.put("targetId", po.getTargetId());
            audit.put("operType", po.getOperType());
            audit.put("ip", po.getOperIp());
            audit.put("status", po.getStatus());
            audit.put("changeDiff", po.getChangeDiff());
            audit.put("desc", po.getOperDesc());
            audit.put("errorMsg", po.getErrorMsg());
            audit.put("operTime", po.getOperTime() == null ? null : po.getOperTime().toString());
            AUDIT_LOG.info(AUDIT_MAPPER.writeValueAsString(audit));
        } catch (Exception e) {
            log.error("审计文件双写失败（DB 副本已落库）", e);
        }
    }

    /**
     * 从请求上下文解析客户端 IP；无请求上下文（定时任务线程）返回 null 落库默认值
     */
    private String resolveClientIp() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        return IpUtils.getClientIp(attrs.getRequest());
    }
}

package com.sanye.strategy.application.rbac;

import com.sanye.strategy.common.util.IpUtils;
import com.sanye.strategy.domain.enums.OperTypeEnum;
import com.sanye.strategy.infrastructure.persistence.mapper.UmsOperLogMapper;
import com.sanye.strategy.infrastructure.persistence.po.UmsOperLogPO;
import com.sanye.strategy.infrastructure.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

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
                po.setOperTime(LocalDateTime.now());
                operLogMapper.insert(po);
            });
        } catch (Exception e) {
            log.error("审计日志写入失败，降级不影响主流程", e);
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

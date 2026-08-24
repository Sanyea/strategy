package com.sanye.strategy.infrastructure.logging;

import com.sanye.strategy.common.util.IpMaskUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * <p>
 * 安全事件日志器 — 事件轨（category=security）产生端唯一出口（规格 4.2/6.3）
 * </p>
 * <p>
 * 经 {@code SECURITY} logger 输出结构化事件（logback 路由 security.log，不挂 ValueMasker——
 * 事件轨结构化敏感字段禁碰，规格 6.1）。IP 分级策略产生端完成：
 * 高威胁（authz 越权 / anomaly 风控 / account 锁定冻结）完整原始 IP 保留（攻击溯源取证）；
 * 普通事件（authn 登录 / credential 凭据变更）末段掩码（规格 6.3）。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：安全事件收口组件，AuthService/拦截器经本类记录，禁止散落直接打 SECURITY logger。</li>
 *   <li>优缺点：IP 分级与 securityType 词表收口一处、口径统一；
 *       代价为调用方须按五类语义选型（选型错误仅影响 IP 粒度，不影响事件留痕）。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Component
public class SecurityEventLogger {

    private static final Logger SECURITY_LOG = LoggerFactory.getLogger("SECURITY");

    /**
     * 高威胁事件类型（完整 IP 保留，规格 6.3）
     */
    private static final Set<String> HIGH_THREAT_TYPES = Set.of("authz", "anomaly", "account");

    /**
     * 记录安全事件
     *
     * @param securityType 事件子类型（authn/authz/account/credential/anomaly，规格 4.2）
     * @param account      账号标识（用户名等；失败事件无 userId 时靠账号文本定位）
     * @param ip           客户端 IP（分级处理见类说明）
     * @param result       结果（SUCCESS/FAIL/LOCKED/DENY 等受控词）
     * @param detail       补充说明
     */
    public void log(String securityType, String account, String ip, String result, String detail) {
        String effectiveIp = HIGH_THREAT_TYPES.contains(securityType)
                ? ip
                : IpMaskUtils.maskLastSegment(ip);
        SECURITY_LOG.atWarn()
                .addKeyValue("securityType", securityType)
                .addKeyValue("account", account)
                .addKeyValue("ip", effectiveIp)
                .addKeyValue("result", result)
                .addKeyValue("detail", detail)
                .log("security event: " + securityType + " account=" + account + " result=" + result);
    }
}
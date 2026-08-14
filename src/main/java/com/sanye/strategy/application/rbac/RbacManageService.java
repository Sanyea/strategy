package com.sanye.strategy.application.rbac;

import com.sanye.strategy.common.base.DefaultQueryWrapper;
import com.sanye.strategy.common.base.IWrapper;
import com.sanye.strategy.common.exception.BizException;
import com.sanye.strategy.common.response.ResultCode;
import com.sanye.strategy.common.util.BeanCopyUtils;
import com.sanye.strategy.domain.enums.DataScopeEnum;
import com.sanye.strategy.domain.enums.OperTypeEnum;
import com.sanye.strategy.domain.enums.RoleStatusEnum;
import com.sanye.strategy.domain.enums.YesNoEnum;
import com.sanye.strategy.domain.rbac.entity.UmsPermission;
import com.sanye.strategy.domain.rbac.repository.UmsPermissionService;
import com.sanye.strategy.domain.rbac.repository.UmsRolePermissionService;
import com.sanye.strategy.domain.user.entity.UmsRole;
import com.sanye.strategy.domain.user.entity.UmsUserRole;
import com.sanye.strategy.domain.user.repository.UmsRoleService;
import com.sanye.strategy.domain.user.repository.UmsUserRoleService;
import com.sanye.strategy.infrastructure.security.UserContext;
import com.sanye.strategy.interfaces.rbac.dto.PermissionDTO;
import com.sanye.strategy.interfaces.rbac.dto.RoleDTO;
import com.sanye.strategy.interfaces.rbac.dto.RoleImportItem;
import com.sanye.strategy.interfaces.rbac.dto.UserRoleAssignDTO;
import com.sanye.strategy.interfaces.rbac.vo.EvictTaskVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * <p>
 * RBAC 管理门面 — 管理写操作编排（事务/内置保护/管理面过滤/自动 evict/审计）
 * </p>
 * <p>
 * 每笔写操作统一走 {@link #manageWrite}：事务提交成功 → afterCommit 语义触发受影响用户
 * evict（阈值分流同步/异步）→ 操作审计。业务失败同样审计（REQUIRES_NEW 独立事务，
 * 失败上下文入 error_msg）。功能权限为 JWT 快照（perms/roles 变更须踢人重新登录生效），
 * 数据权限为实时（data_scope 每次请求查库），两模型生效差异见 CLAUDE.md 备忘。
 * </p>
 * <p>
 * 设计模式（门面）：
 * <ul>
 *   <li>角色：管理面用例编排者，聚合角色/权限/用户角色/角色权限契约 + evict + 审计；
 *       Controller 瘦、业务规则（内置保护/管理面过滤）收口一处。</li>
 *   <li>优缺点：跨表事务与自动踢人统一编排、审计路径单一；代价为门面体积随操作增长
 *       （按需拆分，见下）。</li>
 * </ul>
 * 时序（以 updateRoleStatus 为例）：
 * <pre>
 * Controller --&gt; RbacManageService.updateRoleStatus(id, status)
 *   -- 内置保护：SUPER_ADMIN 禁停用
 *   --&gt; manageWrite( tx(updateById) , EvictPlan(roleId), logReq )
 *         tx 提交成功 --&gt; triggerEvict --&gt; evictRoleUsers/evictUsers（异步经 EvictTaskRegistry）
 *         --&gt; operLogService.record(操作审计)
 * </pre>
 * </p>
 *
 * @author 31372
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RbacManageService {

    private static final String SUPER_ADMIN = "SUPER_ADMIN";

    private final UmsRoleService roleService;
    private final UmsPermissionService permissionService;
    private final UmsUserRoleService userRoleService;
    private final UmsRolePermissionService rolePermissionService;
    private final PermissionSyncService syncService;
    private final EvictService evictService;
    private final EvictTaskRegistry evictTaskRegistry;
    private final OperLogService operLogService;
    private final TransactionTemplate transactionTemplate;
    /**
     * 自动 evict 同步/异步阈值：受影响用户数超过该值走异步任务（默认 50）
     */
    @Value("${rbac.evict-async-threshold:50}")
    private long evictAsyncThreshold;

    /**
     * 角色导入 JSON 解析器 — Boot4 默认 Jackson 3（tools.jackson），与 MP JacksonTypeHandler
     * 的 Jackson 2 命名空间不混用
     */
    private final ObjectMapper objectMapper;

    // ============ 通用编排：事务 → afterCommit evict → 审计 ============

    /**
     * 管理写操作通用编排：事务内执行 → 提交成功后触发自动 evict → 操作审计
     * <p>业务失败也审计（REQUIRES_NEW 独立事务，失败上下文入 error_msg），再上抛原异常。</p>
     *
     * @param action 业务动作（事务内执行）
     * @param plan   evict 计划（null=无需踢人）
     * @param logReq 操作审计（null=不审计）
     * @param <T>    动作返回类型
     * @return 动作结果
     */
    private <T> T manageWrite(Supplier<T> action, EvictPlan plan, OperLogReq logReq) {
        try {
            T result = transactionTemplate.execute(status -> action.get());   // 提交成功才触发 evict
            triggerEvict(plan);
            if (logReq != null) {
                operLogService.record(logReq);
            }
            return result;
        } catch (Exception e) {
            if (logReq != null) {   // 业务失败也审计（REQUIRES_NEW 独立事务，失败上下文入 error_msg）
                operLogService.record(OperLogReq.builder()
                        .module(logReq.getModule()).action(logReq.getAction())
                        .desc(logReq.getDesc()).type(logReq.getType())
                        .success(false).errorMsg(e.getMessage()).build());
            }
            throw e;
        }
    }

    /**
     * 触发自动 evict：按计划估算规模分流——小量同步执行、大批异步提交任务注册表；
     * evict 异常不阻断业务（权限变更已提交生效，仅踢人延迟，审计失败状态供人工补偿）
     */
    private void triggerEvict(EvictPlan plan) {
        if (plan == null) {
            return;
        }
        try {
            long total = plan.estimateTotal(userRoleService);
            Supplier<Integer> evict = () -> plan.getRoleId() != null
                    ? evictService.evictRoleUsers(plan.getRoleId())
                    : evictService.evictUsers(plan.getUserIds());
            if (total <= evictAsyncThreshold) {
                int kicked = evict.get();
                plan.setEvictTaskId(null);
                evictLog(plan, kicked, null, true, null);
            } else {
                String taskId = evictTaskRegistry.submit(evict, plan.getSourceDesc());
                plan.setEvictTaskId(taskId);
                evictLog(plan, -1, taskId, true, null);
            }
        } catch (Exception e) {
            // 权限变更已提交生效，仅踢人延迟——审计失败状态，人工可用 evict 接口补偿
            log.error("自动 evict 失败: 来源={}", plan.getSourceDesc(), e);
            evictLog(plan, -1, null, false, e.getMessage());
        }
    }

    /**
     * evict 审计（同步/异步/失败统一入口；重试在 EvictService.revokeWithRetry）
     */
    private void evictLog(EvictPlan plan, int kicked, String taskId, boolean success, String errorMsg) {
        String desc = "自动踢人 来源=" + plan.getSourceDesc()
                + (plan.getRoleId() != null ? " 角色ID=" + plan.getRoleId() : "")
                + (plan.getUserIds() != null ? " 用户数=" + plan.getUserIds().size() : "")
                + " 踢中会话=" + (kicked < 0 ? "异步" : kicked)
                + (taskId != null ? " taskId=" + taskId : "");
        operLogService.record(OperLogReq.builder()
                .module("rbac").action("evict").desc(desc).type(OperTypeEnum.EVICT_USER)
                .success(success).errorMsg(errorMsg).build());
    }

    // ============ 角色 ============

    /**
     * 新增角色（默认 NORMAL/NOT_BUILT_IN；role_code 查重冲突 409）
     */
    public void createRole(RoleDTO dto) {
        if (roleService.count(new DefaultQueryWrapper<UmsRole>().eq("role_code", dto.getRoleCode())) > 0) {
            throw new BizException(ResultCode.CONFLICT, "角色编码已存在");
        }
        UmsRole role = new UmsRole();
        BeanCopyUtils.copy(dto, role);
        role.setStatus(RoleStatusEnum.NORMAL);
        role.setIsBuiltIn(YesNoEnum.NO);
        if (dto.getDataScope() != null) {
            role.setDataScope(DataScopeEnum.valueOf(dto.getDataScope()));
            if (role.getDataScope() == null) {
                log.warn("非法 dataScope 码值={}，角色 {} 按 DB 默认(全部数据)处理", dto.getDataScope(), dto.getRoleCode());
            }
        }
        manageWrite(() -> {
            roleService.insert(role);
            return null;
        }, null,
                OperLogReq.builder().module("rbac").action("createRole").desc("新增角色 " + dto.getRoleCode())
                        .type(OperTypeEnum.CREATE).success(true).build());
    }

    /**
     * 修改角色（内置角色禁改 role_code；非内置可改）
     */
    public void updateRole(Long id, RoleDTO dto) {
        UmsRole role = roleService.getById(id);
        if (role == null) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
        if (YesNoEnum.YES.equals(role.getIsBuiltIn())
                && !dto.getRoleCode().equals(role.getRoleCode())) {
            throw new BizException(ResultCode.CONFLICT, "内置角色禁改 role_code");
        }
        // 非内置可改 role_code（内置由上方守卫拦截）
        String originalRoleCode = role.getRoleCode();
        BeanCopyUtils.copy(dto, role, "id", "isBuiltIn", "deleted", "createTime", "updateTime");
        if (dto.getDataScope() != null) {
            DataScopeEnum scope = DataScopeEnum.valueOf(dto.getDataScope());
            if (scope == null) {
                log.warn("非法 dataScope 码值={}，角色 {} 保持原数据范围", dto.getDataScope(), id);
            } else {
                role.setDataScope(scope);
            }
        }
        // 改名 → 已绑定用户 JWT roles 快照（≤30min）与实时 data_scope 解析失效，须踢角色下用户重登
        boolean roleCodeChanged = originalRoleCode != null && !originalRoleCode.equals(role.getRoleCode());
        EvictPlan plan = roleCodeChanged
                ? EvictPlan.builder().roleId(id).sourceDesc("角色改名 roleId=" + id).build()
                : null;
        manageWrite(() -> {
            roleService.updateById(role);
            return null;
        }, plan,
                OperLogReq.builder().module("rbac").action("updateRole").desc("修改角色 " + id)
                        .type(OperTypeEnum.UPDATE).success(true).build());
    }

    /**
     * 删除角色（内置禁删；已关联用户先解绑）
     */
    public void deleteRole(Long id) {
        UmsRole role = roleService.getById(id);
        if (role == null) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
        if (YesNoEnum.YES.equals(role.getIsBuiltIn())) {
            throw new BizException(ResultCode.CONFLICT, "内置角色不允许删除");
        }
        if (userRoleService.countUserIdsByRoleId(id) > 0) {
            throw new BizException(ResultCode.CONFLICT, "角色已关联用户，先解绑");
        }
        manageWrite(() -> {
            roleService.deleteById(id);
            rolePermissionService.revokeByRoleId(id);
            return null;
        }, null,
                OperLogReq.builder().module("rbac").action("deleteRole").desc("删除角色 " + id)
                        .type(OperTypeEnum.DELETE).success(true).build());
    }

    /**
     * 角色启用/停用（SUPER_ADMIN 内置角色禁停用；变更后踢角色下用户重登同步新快照）
     */
    public void updateRoleStatus(Long id, RoleStatusEnum status) {
        UmsRole role = roleService.getById(id);
        if (role == null) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
        if (YesNoEnum.YES.equals(role.getIsBuiltIn()) && SUPER_ADMIN.equals(role.getRoleCode())
                && RoleStatusEnum.DISABLED.equals(status)) {
            throw new BizException(ResultCode.CONFLICT, "SUPER_ADMIN 不允许停用");
        }
        role.setStatus(status);
        EvictPlan plan = EvictPlan.builder().roleId(id)
                .sourceDesc("角色" + (RoleStatusEnum.NORMAL.equals(status) ? "启用" : "停用") + " roleId=" + id).build();
        manageWrite(() -> {
            roleService.updateById(role);
            return null;
        }, plan,
                OperLogReq.builder().module("rbac").action("updateRoleStatus").desc("角色 " + id + " 状态→" + status.getCode())
                        .type(OperTypeEnum.UPDATE).success(true).build());
    }

    /**
     * 克隆角色：复制字段 + 克隆原角色权限绑定（权限变化 → 新角色，无用户不受影响，无需 evict）
     */
    public Long cloneRole(Long id) {
        UmsRole src = roleService.getById(id);
        if (src == null) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
        UmsRole copy = BeanCopyUtils.copy(src, UmsRole.class, "id", "roleCode", "createTime", "updateTime", "deleted");
        String base = src.getRoleCode();
        if (base.length() > 32) {
            base = base.substring(0, 32);   // 超长截断源段
        }
        String code;
        int seq = 1;
        do {
            code = base + "_COPY_" + seq++;                       // 截断后查重保证唯一
        } while (roleService.count(new DefaultQueryWrapper<UmsRole>().eq("role_code", code)) > 0);
        copy.setRoleCode(code);
        copy.setRoleName(src.getRoleName() + "_副本");
        copy.setStatus(RoleStatusEnum.NORMAL);
        copy.setIsBuiltIn(YesNoEnum.NO);
        Long newId = manageWrite(() -> {
            roleService.insert(copy);
            rolePermissionService.grantBatch(copy.getId(), rolePermissionService.getPermissionIdsByRoleId(id),
                    UserContext.get().getUserId());
            return copy.getId();
        }, null, OperLogReq.builder().module("rbac").action("cloneRole").desc("克隆角色 " + id + " → " + code)
                .type(OperTypeEnum.CREATE).success(true).build());
        return newId;
    }

    /**
     * 角色导出（roleCode/roleName/dataScope/sortOrder/remark + 权限码列表）
     */
    public List<Map<String, Object>> exportRoles() {
        List<UmsRole> roles = roleService.list(new DefaultQueryWrapper<UmsRole>());
        List<Map<String, Object>> out = new ArrayList<>();
        for (UmsRole r : roles) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("roleCode", r.getRoleCode());
            m.put("roleName", r.getRoleName());
            m.put("dataScope", r.getDataScope() == null ? null : r.getDataScope().getCode());
            m.put("sortOrder", r.getSortOrder());
            m.put("remark", r.getRemark());
            m.put("permissionCodes", rolePermissionService.getPermissionCodesByRoleId(r.getId()));
            out.add(m);
        }
        return out;
    }

    /**
     * 角色导入：按 role_code 匹配——已存在跳过 / overwrite 覆盖（权限绑定变化 → evict 该角色下用户）；
     * 未注册权限码忽略该项并告警（不整体失败）
     */
    public SyncReport importRoles(String json, boolean overwrite) {
        // JSON 解析用 Boot4 默认 Jackson 3（tools.jackson.databind.ObjectMapper）——与 MP JacksonTypeHandler 的 Jackson2 不混用
        // Jackson 3 异常为非受检（tools.jackson.core.JacksonException extends RuntimeException），无需 try/catch 声明
        List<RoleImportItem> items = objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, RoleImportItem.class));
        SyncReport report = new SyncReport();
        Set<String> ignoredCodes = new LinkedHashSet<>();
        for (RoleImportItem it : items) {
            UmsRole role = roleService.getOne(new DefaultQueryWrapper<UmsRole>().eq("role_code", it.getRoleCode()));
            if (role != null && !overwrite) {
                report.getDeprecated().add("跳过已存在: " + it.getRoleCode());
                continue;
            }
            // 未注册权限码 → 忽略该项并告警（不整体失败）；permissionCodes 缺省（null）按空处理
            List<Long> validPermIds = new ArrayList<>();
            for (String code : (it.getPermissionCodes() == null ? List.<String>of() : it.getPermissionCodes())) {
                UmsPermission p = permissionService.getOne(new DefaultQueryWrapper<UmsPermission>().eq("permission_code", code));
                if (p == null) {
                    ignoredCodes.add(code);
                    continue;
                }
                validPermIds.add(p.getId());
            }
            if (role == null) {
                UmsRole nr = new UmsRole();
                nr.setRoleCode(it.getRoleCode());
                nr.setRoleName(it.getRoleName());
                nr.setDataScope(DataScopeEnum.SELF);
                nr.setStatus(RoleStatusEnum.NORMAL);
                nr.setIsBuiltIn(YesNoEnum.NO);
                nr.setSortOrder(it.getSortOrder() == null ? 0 : it.getSortOrder());
                nr.setRemark(it.getRemark());
                Long nrId = manageWrite(() -> {
                    roleService.insert(nr);
                    if (!validPermIds.isEmpty()) {
                        rolePermissionService.grantBatch(nr.getId(), validPermIds, UserContext.get().getUserId());
                    }
                    return nr.getId();
                }, null, OperLogReq.builder().module("rbac").action("importRoles").desc("导入角色 " + it.getRoleCode())
                        .type(OperTypeEnum.IMPORT).success(true).build());
                report.getAdded().add(it.getRoleCode());
            } else if (overwrite) {
                if (SUPER_ADMIN.equals(role.getRoleCode()) && validPermIds.isEmpty()) {
                    // SUPER_ADMIN 禁清空权限——跳过该项记告警，不整体失败（保直通兜底）
                    log.warn("角色导入覆盖跳过: SUPER_ADMIN 禁止清空权限 code={}", it.getRoleCode());
                    report.getDeprecated().add("SUPER_ADMIN 禁止清空权限: " + it.getRoleCode());
                    continue;
                }
                Set<Long> before = new HashSet<>(rolePermissionService.getPermissionIdsByRoleId(role.getId()));
                boolean changed = !before.equals(new HashSet<>(validPermIds));
                EvictPlan plan = changed
                        ? EvictPlan.builder().roleId(role.getId()).sourceDesc("角色导入覆盖 roleId=" + role.getId()).build()
                        : null;
                manageWrite(() -> {
                    rolePermissionService.revokeByRoleId(role.getId());
                    if (!validPermIds.isEmpty()) {
                        rolePermissionService.grantBatch(role.getId(), validPermIds, UserContext.get().getUserId());
                    }
                    return null;
                }, plan, OperLogReq.builder().module("rbac").action("importRoles").desc("覆盖导入角色 " + it.getRoleCode() + " 权限变化=" + changed)
                        .type(OperTypeEnum.IMPORT).success(true).build());
                report.getRevived().add(it.getRoleCode());
            }
        }
        report.setIgnored(new ArrayList<>(ignoredCodes));
        if (!ignoredCodes.isEmpty()) {
            log.warn("角色导入忽略未注册权限码: {}", ignoredCodes);
        }
        return report;
    }

    // ============ 权限资源 ============

    /**
     * 新增权限资源（默认 NORMAL/NOT_BUILT_IN；permission_code 非空查重）
     */
    public void createPermission(PermissionDTO dto) {
        if (dto.getPermissionCode() != null && !dto.getPermissionCode().isBlank()
                && permissionService.count(new DefaultQueryWrapper<UmsPermission>().eq("permission_code", dto.getPermissionCode())) > 0) {
            throw new BizException(ResultCode.CONFLICT, "权限码已存在");
        }
        UmsPermission p = new UmsPermission();
        BeanCopyUtils.copy(dto, p);
        p.setStatus(RoleStatusEnum.NORMAL);
        p.setIsBuiltIn(YesNoEnum.NO);
        manageWrite(() -> {
            permissionService.insert(p);
            return null;
        }, null,
                OperLogReq.builder().module("rbac").action("createPermission").desc("新增权限 " + dto.getPermissionCode())
                        .type(OperTypeEnum.CREATE).success(true).build());
    }

    /**
     * 修改权限资源（permissionCode 禁改——改码=换新资源防角色绑定引用断裂；内置资源禁改）
     */
    public void updatePermission(Long id, PermissionDTO dto) {
        UmsPermission p = permissionService.getById(id);
        if (p == null) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
        if (YesNoEnum.YES.equals(p.getIsBuiltIn())) {
            throw new BizException(ResultCode.CONFLICT, "内置资源禁改");
        }
        BeanCopyUtils.copy(dto, p, "id", "permissionCode", "isBuiltIn", "deleted", "createTime", "updateTime");
        manageWrite(() -> {
            permissionService.updateById(p);
            return null;
        }, null,
                OperLogReq.builder().module("rbac").action("updatePermission").desc("修改权限 " + id)
                        .type(OperTypeEnum.UPDATE).success(true).build());
    }

    /**
     * 删除权限资源（内置资源禁删；已绑定角色先解绑）
     */
    public void deletePermission(Long id) {
        UmsPermission p = permissionService.getById(id);
        if (p == null) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
        if (YesNoEnum.YES.equals(p.getIsBuiltIn())) {
            throw new BizException(ResultCode.CONFLICT, "内置资源禁删除");
        }
        if (rolePermissionService.countByPermissionId(id) > 0) {
            throw new BizException(ResultCode.CONFLICT, "权限已绑定角色，先解绑");
        }
        manageWrite(() -> {
            permissionService.deleteById(id);
            return null;
        }, null,
                OperLogReq.builder().module("rbac").action("deletePermission").desc("删除权限 " + id)
                        .type(OperTypeEnum.DELETE).success(true).build());
    }

    /**
     * 权限启用/停用（启用/停用对称：都踢绑定角色下用户重登同步新快照；内置资源禁停用）
     */
    public void updatePermissionStatus(Long id, RoleStatusEnum status) {
        UmsPermission p = permissionService.getById(id);
        if (p == null) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
        if (YesNoEnum.YES.equals(p.getIsBuiltIn()) && RoleStatusEnum.DISABLED.equals(status)) {
            throw new BizException(ResultCode.CONFLICT, "内置资源禁停用");
        }
        p.setStatus(status);
        List<Long> roleIds = rolePermissionService.getRoleIdsByPermissionId(id);
        List<Long> userIds = collectActiveUserIds(roleIds);   // 停用/启用对称：都踢（重登同步新快照）
        EvictPlan plan = userIds.isEmpty() ? null
                : EvictPlan.builder().userIds(userIds)
                        .sourceDesc("权限" + (RoleStatusEnum.NORMAL.equals(status) ? "启用" : "停用") + " permId=" + id).build();
        manageWrite(() -> {
            permissionService.updateById(p);
            return null;
        }, plan,
                OperLogReq.builder().module("rbac").action("updatePermissionStatus").desc("权限 " + id + " 状态→" + status.getCode())
                        .type(OperTypeEnum.UPDATE).success(true).build());
    }

    /**
     * 手动权限同步（dry-run 预览差异不写库；非 dry-run 对复活/残留停用反查绑定角色下用户 evict——对称于权限启用/停用，
     * 复活恢复同权限启用同样踢人；经阈值分流 + EVICT_USER 审计，与其它管理写同一纪律）
     */
    public SyncReport syncPermissions(boolean dryRun) {
        SyncReport r = syncService.sync(dryRun);
        if (!dryRun && (!r.getRevived().isEmpty() || !r.getDeprecated().isEmpty())) {
            // 复活/残留停用对称 evict：反查绑定角色 → 角色下用户（权限变化 → 重登同步新快照）
            Set<Long> userIds = new LinkedHashSet<>();
            for (String code : r.getRevived()) {
                userIds.addAll(collectActiveUserIdsByCode(code));
            }
            for (String code : r.getDeprecated()) {
                userIds.addAll(collectActiveUserIdsByCode(code));
            }
            if (!userIds.isEmpty()) {
                triggerEvict(EvictPlan.builder().userIds(new ArrayList<>(userIds))
                        .sourceDesc("权限同步 复活=" + r.getRevived().size() + " 停用=" + r.getDeprecated().size()).build());
            }
        }
        return r;
    }

    /**
     * 权限码 → 绑定该权限的角色下活动用户集（复活/残留停用反查 evict 用）
     */
    private Set<Long> collectActiveUserIdsByCode(String code) {
        UmsPermission p = permissionService.getOne(new DefaultQueryWrapper<UmsPermission>().eq("permission_code", code));
        if (p == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(collectActiveUserIds(rolePermissionService.getRoleIdsByPermissionId(p.getId())));
    }

    // ============ 用户-角色 ============

    /**
     * 覆盖绑定：清空用户现有角色 → 写入新角色集（权限变化 → 踢该用户重登同步新快照）
     */
    public void replaceUserRoles(Long userId, List<UserRoleAssignDTO> assigns) {
        EvictPlan plan = EvictPlan.builder().userIds(List.of(userId)).sourceDesc("用户角色覆盖 userId=" + userId).build();
        manageWrite(() -> {
            userRoleService.replaceRoles(userId, toEntities(assigns), UserContext.get().getUserId());
            return null;
        }, plan, auditLog("replaceUserRoles", "用户 " + userId + " 角色覆盖 → " + assignDesc(assigns), OperTypeEnum.GRANT));
    }

    /**
     * 批量授角色（begin/end 入参校验；生效时段由 Task 12/13 依赖的 assignRole 契约承载）
     */
    public void assignRolesBatch(List<Long> userIds, Long roleId, LocalDateTime begin, LocalDateTime end) {
        if (begin != null && end != null && !begin.isBefore(end)) {
            throw new BizException(ResultCode.BAD_REQUEST, "begin 必须早于 end");
        }
        EvictPlan plan = EvictPlan.builder().userIds(userIds).sourceDesc("批量授角色 roleId=" + roleId).build();
        manageWrite(() -> {
            for (Long uid : userIds) {
                userRoleService.assignRole(uid, roleId, UserContext.get().getUserId(), begin, end);
            }
            return null;
        }, plan, auditLog("assignRolesBatch", "批量授角色 roleId=" + roleId + " 用户数=" + userIds.size(), OperTypeEnum.GRANT));
    }

    /**
     * 解绑用户某角色（权限变化 → 踢该用户）
     */
    public boolean removeUserRole(Long userId, Long roleId) {
        EvictPlan plan = EvictPlan.builder().userIds(List.of(userId)).sourceDesc("解绑角色 userId=" + userId + " roleId=" + roleId).build();
        return manageWrite(() -> userRoleService.removeUserRole(userId, roleId),
                plan, auditLog("removeUserRole", "解绑", OperTypeEnum.DELETE));
    }

    /**
     * 单角色续期：更新某绑定 end_time（续费原地变更，权限不变化无需踢人，仅审计）
     */
    public boolean renewUserRole(Long userId, Long roleId, LocalDateTime endTime) {
        // 续费原地变更，权限不变化无需踢人，仅审计
        return manageWrite(() -> userRoleService.renew(userId, roleId, endTime), null,
                OperLogReq.builder().module("rbac").action("renewUserRole").desc("续期 userId=" + userId + " roleId=" + roleId + " end=" + endTime)
                        .type(OperTypeEnum.UPDATE).success(true).build());
    }

    /**
     * 批量续期：按绑定行 ID 反查 userId（renewById 走行主键，契约与单角色续期 renew(userId,roleId) 分离）
     */
    public int renewBatch(List<Long> bindIds, LocalDateTime endTime) {
        // 续费原地变更，权限不变化无需踢人，仅审计
        return manageWrite(() -> {
            int n = 0;
            for (Long bindId : bindIds) {
                if (userRoleService.renewById(bindId, endTime)) {
                    n++;
                }
            }
            return n;
        }, null, OperLogReq.builder().module("rbac").action("renewBatch").desc("批量续期 " + bindIds.size() + " 条绑定")
                .type(OperTypeEnum.UPDATE).success(true).build());
    }

    // ============ 角色-权限 ============

    /**
     * 角色权限覆盖：清空 → 批量授权（权限变化 → 踢角色下用户；SUPER_ADMIN 禁清空权限，保直通兜底）
     */
    public void replaceRolePermissions(Long roleId, List<Long> permissionIds) {
        UmsRole role = roleService.getById(roleId);
        if (role != null && SUPER_ADMIN.equals(role.getRoleCode())
                && (permissionIds == null || permissionIds.isEmpty())) {
            throw new BizException(ResultCode.CONFLICT, "SUPER_ADMIN 禁止清空权限");
        }
        EvictPlan plan = EvictPlan.builder().roleId(roleId).sourceDesc("角色权限覆盖 roleId=" + roleId).build();
        manageWrite(() -> {
            rolePermissionService.revokeByRoleId(roleId);
            rolePermissionService.grantBatch(roleId, permissionIds, UserContext.get().getUserId());
            return null;
        }, plan, auditLog("replaceRolePermissions", "角色 " + roleId + " 权限覆盖 → " + permissionIds.size() + " 条", OperTypeEnum.GRANT));
    }

    /**
     * 角色增量授权（INSERT IGNORE 静默去重，权限变化 → 踢角色下用户）
     */
    public void grantRolePermissions(Long roleId, List<Long> permissionIds) {
        EvictPlan plan = EvictPlan.builder().roleId(roleId).sourceDesc("角色增量授权 roleId=" + roleId).build();
        manageWrite(() -> {
            rolePermissionService.grantBatch(roleId, permissionIds, UserContext.get().getUserId());
            return null;
        }, plan, OperLogReq.builder().module("rbac").action("grantRolePermissions").desc("角色 " + roleId + " 增量授权 " + permissionIds.size() + " 条")
                .type(OperTypeEnum.GRANT).success(true).build());
    }

    /**
     * 角色回收权限（逐条回收，权限变化 → 踢角色下用户）
     */
    public void revokeRolePermissions(Long roleId, List<Long> permissionIds) {
        EvictPlan plan = EvictPlan.builder().roleId(roleId).sourceDesc("角色回收权限 roleId=" + roleId).build();
        manageWrite(() -> {
            for (Long pid : permissionIds) {
                rolePermissionService.revoke(roleId, pid);
            }
            return null;
        }, plan, OperLogReq.builder().module("rbac").action("revokeRolePermissions").desc("角色 " + roleId + " 回收权限 " + permissionIds.size() + " 条")
                .type(OperTypeEnum.DELETE).success(true).build());
    }

    // ============ 调试/主动失效（受 debug 开关） ============

    /**
     * 踢单用户（主动失效，直接执行不审计）
     */
    public int evictUser(Long userId) {
        return evictService.evictUsers(List.of(userId));
    }

    /**
     * 手动批量踢角色下用户（async 走异步任务注册表；同步小量直接执行）
     */
    public EvictTaskVO evictBatch(Long roleId, String mode) {
        if ("async".equals(mode)) {
            String taskId = evictTaskRegistry.submit(() -> evictService.evictRoleUsers(roleId), "手动批量踢 roleId=" + roleId);
            return evictTaskRegistry.get(taskId);
        }
        int kicked = evictService.evictRoleUsers(roleId);   // 同步小量
        EvictTaskVO vo = new EvictTaskVO();
        vo.setSourceDesc("手动批量踢(同步) roleId=" + roleId);
        vo.setStatus("SUCCESS");
        vo.setKicked(kicked);
        vo.setCreatedAt(LocalDateTime.now());
        vo.setDoneAt(LocalDateTime.now());
        return vo;
    }

    // ============ 辅助 ============

    /**
     * 角色集 → 活动用户并集（offset 分页去重；权限停用/启用/导入覆盖反查 evict 用）
     */
    private List<Long> collectActiveUserIds(List<Long> roleIds) {
        Set<Long> users = new LinkedHashSet<>();
        for (Long rid : roleIds) {
            long offset = 0;
            List<Long> ids;
            do {
                ids = userRoleService.listActiveUserIdsByRoleId(rid, offset, 500);
                offset += ids.size();
                users.addAll(ids);
            } while (ids.size() == 500);
        }
        return new ArrayList<>(users);
    }

    /**
     * 管理面过滤（DAO 统一封装，禁止复用业务表）：
     * 非 SUPER_ADMIN 仅可见本人创建（create_user_id is null 或 = 当前用户）
     */
    public IWrapper<UmsRole> applyManageScope(IWrapper<UmsRole> wrapper) {
        UserContext ctx = UserContext.get();
        if (ctx == null || ctx.getRoleCodes().contains(SUPER_ADMIN)) {
            return wrapper;
        }
        // 自定义 IWrapper 无 and(Consumer)——OR 组用 nested（CLAUDE.md 约定）
        return wrapper.nested(w -> w.isNull("create_user_id").or().eq("create_user_id", ctx.getUserId()));
    }

    /**
     * 构造操作审计请求（成功态；module 固定 rbac）
     *
     * @param action 操作动作
     * @param desc   操作说明
     * @param type   操作类型
     * @return 审计请求
     */
    private OperLogReq auditLog(String action, String desc, OperTypeEnum type) {
        return OperLogReq.builder().module("rbac").action(action).desc(desc).type(type).success(true).build();
    }

    /**
     * 角色覆盖分配摘要（审计 desc 可读化）
     *
     * @param assigns 角色分配条目
     * @return 可读摘要，如 "2个角色"
     */
    private String assignDesc(List<UserRoleAssignDTO> assigns) {
        return (assigns == null ? 0 : assigns.size()) + "个角色";
    }

    /**
     * 角色分配 DTO → 绑定实体（仅 roleId/beginTime/endTime，userId 由调用方统一取参）
     *
     * @param assigns 角色分配条目
     * @return 绑定实体列表（空输入返回空列表）
     */
    private List<UmsUserRole> toEntities(List<UserRoleAssignDTO> assigns) {
        List<UmsUserRole> entities = new ArrayList<>();
        if (assigns == null || assigns.isEmpty()) {
            return entities;
        }
        for (UserRoleAssignDTO dto : assigns) {
            UmsUserRole ur = new UmsUserRole();
            ur.setRoleId(dto.getRoleId());
            ur.setBeginTime(dto.getBeginTime());
            ur.setEndTime(dto.getEndTime());
            entities.add(ur);
        }
        return entities;
    }
}

package com.sanye.strategy.application.rbac;

import com.sanye.strategy.common.base.DefaultQueryWrapper;
import com.sanye.strategy.domain.enums.PermissionTypeEnum;
import com.sanye.strategy.domain.enums.RoleStatusEnum;
import com.sanye.strategy.domain.enums.YesNoEnum;
import com.sanye.strategy.domain.rbac.entity.UmsPermission;
import com.sanye.strategy.domain.rbac.repository.UmsPermissionService;
import com.sanye.strategy.infrastructure.security.PermissionCodeValidator;
import com.sanye.strategy.infrastructure.security.RequiresPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * <p>
 * 权限资源扫描同步 — 启动新增/复活 + 手动残留停用（dry-run 预览）
 * </p>
 * <p>
 * 设计：
 * <ul>
 *   <li>启动只做新增+复活，残留停用由手动 sync 执行（防误停用，发布流程强制手动 sync）；</li>
 *   <li>状态变更幂等（复活=UPDATE status 1、停用=UPDATE status 0）；</li>
 *   <li>同实例 {@link ReentrantLock} 串行（集群分布式锁见 TODO）；</li>
 *   <li>新增走 {@code uk_permission_code} 防重：冲突捕获 {@link DuplicateKeyException} 忽略（等价 INSERT IGNORE）。</li>
 * </ul>
 * </p>
 * <p>
 * 设计模式：模板方法 + 策略（策略=新增/复活/残留停用三类差异处理，按扫描集与库集比对驱动；
 * 无独立策略接口，差异分支集中于此，避免过度设计）。角色：扫描器（类路径采集注解码）、
 * 比对器（扫描集 vs 库集 → 差异报告）、执行器（按 dryRun 门控写操作）。
 * 优缺点：语义集中在单门面，dry-run 预览免副作用；代价为写操作非批量、逐行 INSERT/UPDATE（权限码基量小可接受）。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PermissionSyncService {

    /**
     * 注解扫描基包 — RBAC 接口控制器统一位于 interfaces 层
     */
    private static final String BASE_PACKAGE = "com.sanye.strategy.interfaces";

    private final UmsPermissionService permissionService;

    /**
     * 同实例串行锁（集群多实例需升级分布式锁，见 TODO）
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 手动同步：新增 + 复活 + 残留停用（dry-run 仅返回差异，不写库）
     *
     * @param dryRun true-预览差异不写库，false-落库
     * @return 差异报告
     */
    public SyncReport sync(boolean dryRun) {
        return doSync(dryRun, true);
    }

    /**
     * 启动同步：只做新增+复活，不做残留停用（防误停用，发布流程强制手动 sync）
     *
     * @return 差异报告（deprecated 恒空）
     */
    public SyncReport syncStartup() {
        return doSync(false, false);
    }

    private SyncReport doSync(boolean dryRun, boolean includeDeprecated) {
        lock.lock();
        try {
            Map<String, String> scanned = scanAnnotatedCodes();
            List<UmsPermission> existing = permissionService.list(new DefaultQueryWrapper<UmsPermission>());
            Map<String, UmsPermission> byCode = existing.stream()
                    .filter(p -> p.getPermissionCode() != null)
                    .collect(Collectors.toMap(UmsPermission::getPermissionCode, p -> p, (a, b) -> a));
            SyncReport report = new SyncReport();
            for (Map.Entry<String, String> e : scanned.entrySet()) {
                UmsPermission p = byCode.get(e.getKey());
                if (p == null) {
                    report.getAdded().add(e.getKey());
                    if (!dryRun) {
                        insertInterfacePermission(e.getKey(), e.getValue());
                    }
                } else if (RoleStatusEnum.DISABLED.equals(p.getStatus())) {
                    report.getRevived().add(e.getKey());
                    if (!dryRun) {
                        p.setStatus(RoleStatusEnum.NORMAL);
                        permissionService.updateById(p);
                    }
                }
            }
            for (UmsPermission p : existing) {
                if (includeDeprecated
                        && p.getPermissionCode() != null && PermissionTypeEnum.INTERFACE.equals(p.getPermissionType())
                        && RoleStatusEnum.NORMAL.equals(p.getStatus())
                        && !scanned.containsKey(p.getPermissionCode())) {
                    report.getDeprecated().add(p.getPermissionCode());
                    if (!dryRun) {
                        p.setStatus(RoleStatusEnum.DISABLED);
                        permissionService.updateById(p);   // 幂等：重复执行不重复停用
                    }
                }
            }
            return report;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 扫 interfaces 包类级 + 方法级 @RequiresPermission，PermissionCodeValidator 校验（fail-fast）
     * <p>
     * 包级私有：允许同包单测以匿名子类覆写注入扫描结果（确定性驱动差异断言），
     * 生产路径类路径扫描真实执行。
     * </p>
     *
     * @return 权限码 → 来源描述（类简单名 或 类简单名#方法名）
     */
    Map<String, String> scanAnnotatedCodes() {
        Map<String, String> result = new LinkedHashMap<>();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RequiresPermission.class));
        // 类级无 @RequiresPermission 但方法级有 → 靠 @RestController 命中（AnnotationTypeFilter 匹配元注解，@RestController 含 @Controller）
        // AssignableTypeFilter(Controller.class) 是错误用法（注解接口不会 isAssignableFrom 具体控制器），已移除
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (BeanDefinition bd : scanner.findCandidateComponents(BASE_PACKAGE)) {
            String className = bd.getBeanClassName();
            try {
                Class<?> clazz = Class.forName(className);
                RequiresPermission classAnn = clazz.getAnnotation(RequiresPermission.class);
                if (classAnn != null) {
                    PermissionCodeValidator.validate(classAnn.value());
                    result.put(classAnn.value(), clazz.getSimpleName());
                }
                for (Method m : clazz.getMethods()) {
                    RequiresPermission methodAnn = m.getAnnotation(RequiresPermission.class);
                    if (methodAnn != null) {
                        PermissionCodeValidator.validate(methodAnn.value());
                        result.put(methodAnn.value(), clazz.getSimpleName() + "#" + m.getName());
                    }
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("RBAC 权限扫描类加载失败: " + className, e);
            }
        }
        return result;
    }

    /**
     * 新增接口权限资源 — 主键回填 + uk_permission_code 防重（冲突忽略，等价 INSERT IGNORE）
     *
     * @param code   权限码
     * @param source 来源描述（控制器类/方法）
     */
    private void insertInterfacePermission(String code, String source) {
        UmsPermission p = new UmsPermission();
        p.setParentId(0L);
        p.setPermissionName(code);
        p.setPermissionType(PermissionTypeEnum.INTERFACE);
        p.setPermissionCode(code);
        p.setStatus(RoleStatusEnum.NORMAL);
        p.setIsBuiltIn(YesNoEnum.NO);
        p.setSortOrder(0);
        p.setRemark("注解自动注册: " + source);
        try {
            permissionService.insert(p);   // 主键回填 + uk_permission_code
        } catch (DuplicateKeyException e) {
            log.warn("权限码 {} 已存在（并发扫描/重复注册），跳过", code);   // 等价 INSERT IGNORE
        }
    }
}

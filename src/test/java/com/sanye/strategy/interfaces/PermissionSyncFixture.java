package com.sanye.strategy.interfaces;

import com.sanye.strategy.infrastructure.security.RequiresPermission;

/**
 * 权限扫描夹具 — 供 {@code PermissionSyncServiceTest.realScanFindsAnnotatedClasses}
 * 验证真实类路径扫描（类级 + 方法级 @RequiresPermission 均被采集）。
 * 仅存在于测试源码，不参与生产运行。
 */
@RequiresPermission("system:fixture:scan")
public class PermissionSyncFixture {

    @RequiresPermission("system:fixture:detail")
    public void detail() {
    }
}

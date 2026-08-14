package com.sanye.strategy.application.rbac;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * 权限同步差异报告 — 启动/手动权限扫描同步的输出载体
 * </p>
 * <p>
 * 记录三类差异（新增/复活/残留停用）与导入忽略的未注册权限码（告警用）。
 * 手动同步 dry-run 时，仅填充差异列表、不做任何写操作；调用方据此预览变更。
 * </p>
 */
@Data
public class SyncReport {

    /**
     * 新增权限码（不在库中）
     */
    private List<String> added = new ArrayList<>();

    /**
     * 复活权限码（已存在但停用，重新启用）
     */
    private List<String> revived = new ArrayList<>();

    /**
     * 残留停用权限码（库中存在、扫描集缺失的接口资源，手动同步才执行）
     */
    private List<String> deprecated = new ArrayList<>();

    /**
     * 导入忽略的未注册权限码（告警用）
     */
    private List<String> ignored = new ArrayList<>();
}

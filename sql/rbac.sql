
create database IF NOT EXISTS sys_strategy;

use sys_strategy;

CREATE TABLE IF NOT EXISTS `ums_role`
(
    `id`             BIGINT UNSIGNED  NOT NULL COMMENT '角色ID',
    `role_code`      VARCHAR(100)     NOT NULL COMMENT '角色编码，如 SUPER_ADMIN',
    `role_name`      VARCHAR(120)     NOT NULL COMMENT '角色名称',
    `data_scope`     TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '数据权限范围：1-全部数据 2-仅本人数据 3-本部门数据(需部门表) 4-本部门及以下(需部门表) 5-自定义(需角色-数据域关联表)',
    `sort_order`     INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '显示顺序',
    `status`         TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '角色状态 0-停用 1-正常',
    `is_built_in`    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否内置角色 0-否 1-是，内置角色不允许删除',
    `remark`         VARCHAR(500)     DEFAULT '' COMMENT '备注',
    `deleted`        TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '删除标识 0-未删除 1-已删除',
    `create_user_id` BIGINT UNSIGNED  DEFAULT NULL COMMENT '创建人ID',
    `update_user_id` BIGINT UNSIGNED  DEFAULT NULL COMMENT '更新人ID',
    `create_time`    DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_role_code` (`role_code`),
    KEY `idx_status` (`status`),
    KEY `idx_deleted` (`deleted`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='角色表';

CREATE TABLE IF NOT EXISTS `ums_permission`
(
    `id`              BIGINT UNSIGNED  NOT NULL COMMENT '权限资源ID',
    `parent_id`       BIGINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '父资源ID，0-根',
    `permission_name` VARCHAR(100)     NOT NULL COMMENT '资源名称',
    `title`           VARCHAR(200)     DEFAULT '' COMMENT '前端标题，菜单/目录展示用，可空',
    `permission_type` TINYINT UNSIGNED NOT NULL COMMENT '资源类型：1-目录 2-菜单 3-按钮 4-接口',
    `permission_code` VARCHAR(200)     DEFAULT NULL COMMENT '权限标识，如 system:user:create，按钮/接口使用，可空',
    `route_path`      VARCHAR(200)     DEFAULT '' COMMENT '前端路由地址',
    `component_path`  VARCHAR(255)     DEFAULT '' COMMENT '前端组件路径',
    `api_method`      VARCHAR(10)      DEFAULT NULL COMMENT '接口请求方法 GET/POST/PUT/DELETE/PATCH',
    `api_path`        VARCHAR(255)     DEFAULT NULL COMMENT '接口路径，如 /api/system/user',
    `icon`            VARCHAR(100)     DEFAULT '' COMMENT '图标',
    `sort_order`      INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '显示顺序',
    `is_frame`        TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否外链 0-否 1-是',
    `is_cache`        TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否缓存 0-否 1-是',
    `is_visible`      TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否显示 0-隐藏 1-显示，按钮/接口忽略',
    `status`          TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '资源状态 0-停用 1-正常',
    `is_built_in`     TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否内置资源 0-否 1-是',
    `requires_auth`   TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否需要权限控制 0-否 1-是，前端据此判断是否展示权限控制',
    `remark`          VARCHAR(500)     DEFAULT '' COMMENT '备注，注解扫描自动注册时记录来源',
    `deleted`         TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '删除标识 0-未删除 1-已删除',
    `create_user_id`  BIGINT UNSIGNED  DEFAULT NULL COMMENT '创建人ID',
    `update_user_id`  BIGINT UNSIGNED  DEFAULT NULL COMMENT '更新人ID',
    `create_time`     DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_permission_code` (`permission_code`),
    UNIQUE KEY `uk_api_method_path` (`api_method`, `api_path`),
    KEY `idx_parent_id` (`parent_id`),
    KEY `idx_permission_type` (`permission_type`),
    KEY `idx_status` (`status`),
    KEY `idx_deleted` (`deleted`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='权限资源表：目录/菜单/按钮/接口';

-- 存量库升级：已存在 ums_permission 表补列（MySQL 无 ADD COLUMN IF NOT EXISTS，
-- 已加列后重复执行本句会报 Duplicate column——已加过请忽略该报错，其余语句仍可重复执行）
ALTER TABLE `ums_permission`
    ADD COLUMN `requires_auth` TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否需要权限控制 0-否 1-是，前端据此判断是否展示权限控制' AFTER `is_built_in`,
    ADD COLUMN `title` VARCHAR(200) DEFAULT '' COMMENT '前端标题，菜单/目录展示用，可空' AFTER `permission_name`;

CREATE TABLE IF NOT EXISTS `ums_user_role`
(
    `id`          BIGINT UNSIGNED NOT NULL COMMENT '主键ID',
    `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    `role_id`     BIGINT UNSIGNED NOT NULL COMMENT '角色ID',
    `begin_time`  DATETIME        DEFAULT NULL COMMENT '角色生效开始时间',
    `end_time`    DATETIME        DEFAULT NULL COMMENT '角色生效结束时间',
    `assigner_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '授权人ID',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
    KEY `idx_role_id` (`role_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户-角色关联表，物理删除；续费/延长有效期经 UPDATE end_time 原地变更，审计走 ums_oper_log';

CREATE TABLE IF NOT EXISTS `ums_role_permission`
(
    `id`             BIGINT UNSIGNED NOT NULL COMMENT '主键ID',
    `role_id`        BIGINT UNSIGNED NOT NULL COMMENT '角色ID',
    `permission_id`  BIGINT UNSIGNED NOT NULL COMMENT '权限资源ID',
    `grant_user_id`  BIGINT UNSIGNED DEFAULT NULL COMMENT '授权人ID',
    `create_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_role_permission` (`role_id`, `permission_id`),
    KEY `idx_permission_id` (`permission_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='角色-权限资源关联表，物理删除';

-- 内置角色 seed（注册默认绑定 NORMAL_USER；uk_role_code 防重，可重复执行）
INSERT IGNORE INTO `ums_role`
    (`id`, `role_code`, `role_name`, `data_scope`, `sort_order`, `status`, `is_built_in`, `remark`,
     `deleted`, `create_user_id`, `update_user_id`, `create_time`, `update_time`)
VALUES
    (1, 'SUPER_ADMIN', '超级管理员', 1, 1, 1, 1, '内置角色，全量数据权限，不可删除', 0, NULL, NULL, NOW(), NOW()),
    (2, 'MERCHANT',     '商家',       2, 2, 1, 1, '内置角色，默认仅本人数据',       0, NULL, NULL, NOW(), NOW()),
    (3, 'OPERATOR',     '运营',       2, 3, 1, 1, '内置角色，默认仅本人数据',       0, NULL, NULL, NOW(), NOW()),
    (4, 'NORMAL_USER',  '普通用户',   2, 4, 1, 1, '内置角色，注册默认绑定，默认仅本人数据', 0, NULL, NULL, NOW(), NOW());

-- =============================================
-- RBAC 菜单/绑定 seed（可重复执行）
-- 约定：INSERT IGNORE 防重；permission_id/role_id 一律按 code 动态取，禁止硬编码 ID。
-- =============================================

-- 1. rbac 目录 + 子菜单（权限码唯一，重复执行 INSERT IGNORE 跳过；存量库已有 rbac 目录则跳过）
INSERT IGNORE INTO ums_permission
(id, parent_id, permission_name, title, permission_type, permission_code, route_path, component_path,
 api_method, api_path, icon, sort_order, is_frame, is_cache, is_visible, status, is_built_in, requires_auth, remark,
 deleted, create_user_id, update_user_id, create_time, update_time)
VALUES
    (UUID_SHORT(), 0, '权限管理', '权限管理', 1, 'rbac', '/rbac', NULL,
     NULL, NULL, '', 0, 0, 0, 1, 1, 1, 1, 'RBAC 目录', 0, NULL, NULL, NOW(), NOW()),
    (UUID_SHORT(), (SELECT id FROM ums_permission WHERE permission_code = 'rbac' LIMIT 1),
     'Debug', 'Debug', 2, 'rbac:debug', '/rbac/debug', '@/views/rbac/debug/index.vue',
     NULL, NULL, '', 1, 0, 0, 1, 1, 0, 1, 'Debug权限排查菜单', 0, NULL, NULL, NOW(), NOW()),
    (UUID_SHORT(), (SELECT id FROM ums_permission WHERE permission_code = 'rbac' LIMIT 1),
     'permission', '权限管理', 2, 'rbac:permission', '/rbac/permission', '@/views/rbac/permission/index.vue',
     NULL, NULL, '', 2, 0, 0, 1, 1, 0, 1, '权限管理菜单', 0, NULL, NULL, NOW(), NOW()),
    (UUID_SHORT(), (SELECT id FROM ums_permission WHERE permission_code = 'rbac' LIMIT 1),
     'role', '角色管理', 2, 'rbac:role', '/rbac/role', '@/views/rbac/role/index.vue',
     NULL, NULL, '', 3, 0, 0, 1, 1, 0, 1, '角色管理菜单', 0, NULL, NULL, NOW(), NOW());

-- 2. SUPER_ADMIN 绑定指定按钮/接口权限（按 permission_code 匹配；代码未注册时 SELECT 空集=无操作）
INSERT IGNORE INTO `ums_role_permission` (`id`, `role_id`, `permission_id`, `grant_user_id`, `create_time`, `update_time`)
SELECT
    UUID_SHORT() AS id,
    r.id AS role_id,
    p.id AS permission_id,
    NULL AS grant_user_id,
    NOW() AS create_time,
    NOW() AS update_time
FROM `ums_role` r, `ums_permission` p
WHERE r.role_code = 'SUPER_ADMIN'
  AND p.permission_code IN ('system:rbac:debug:manage', 'system:permission:manage',
                            'system:role:manage', 'system:role:assign', 'system:user:role:manage');

-- 3. 内置角色绑定仪表盘目录/菜单（按 permission_code 动态取 id，避免硬编码 ID）
INSERT IGNORE INTO ums_role_permission (id, role_id, permission_id, grant_user_id, create_time, update_time)
SELECT
    UUID_SHORT(), r.id, p.id, NULL, NOW(), NOW()
FROM ums_role r, ums_permission p
WHERE r.role_code IN ('SUPER_ADMIN', 'MERCHANT', 'OPERATOR', 'NORMAL_USER')
  AND p.permission_code IN ('dashboard', 'dashboard:index');

-- 4. SUPER_ADMIN 绑定 rbac 子菜单权限（按 permission_code 动态取 id）
INSERT IGNORE INTO ums_role_permission (id, role_id, permission_id, grant_user_id, create_time, update_time)
SELECT
    UUID_SHORT(), r.id, p.id, NULL, NOW(), NOW()
FROM ums_role r, ums_permission p
WHERE r.role_code = 'SUPER_ADMIN'
  AND p.permission_code IN ('rbac', 'rbac:debug', 'rbac:permission', 'rbac:role');

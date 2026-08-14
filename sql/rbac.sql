-- =============================================
-- 1.雪花算法依赖表 & next_snowflake_id()函数
-- =============================================
DROP FUNCTION IF EXISTS next_snowflake_id;

CREATE TABLE IF NOT EXISTS `snowflake_seq` (
                                               `worker_id` TINYINT UNSIGNED NOT NULL COMMENT '机器ID 0‑1023',
                                               `last_timestamp` BIGINT UNSIGNED NOT NULL COMMENT '上一次生成ID的时间戳(ms)',
                                               `sequence` INT UNSIGNED NOT NULL COMMENT '毫秒内序列号 0‑4095',
                                               PRIMARY KEY (`worker_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='雪花算法序列号状态表';

INSERT IGNORE INTO snowflake_seq(worker_id,last_timestamp,sequence) VALUES(0,UNIX_TIMESTAMP()*1000,0);

DELIMITER $$
CREATE FUNCTION next_snowflake_id() RETURNS BIGINT UNSIGNED
    DETERMINISTIC
BEGIN
    DECLARE v_worker_id TINYINT UNSIGNED DEFAULT 0;
    DECLARE v_epoch BIGINT UNSIGNED DEFAULT 1609459200000; -- 2021‑01‑01 00:00:00(ms)
    DECLARE v_now BIGINT UNSIGNED;
    DECLARE v_last_ts BIGINT UNSIGNED;
    DECLARE v_seq INT UNSIGNED;
    DECLARE v_id BIGINT UNSIGNED;

    SET v_now = UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000;

    START TRANSACTION;
    SELECT last_timestamp, sequence INTO v_last_ts, v_seq
    FROM snowflake_seq WHERE worker_id = v_worker_id FOR UPDATE;

    IF v_now < v_last_ts THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'next_snowflake_id: 系统时间回拨，无法生成雪花ID';
    END IF;

    IF v_now = v_last_ts THEN
        SET v_seq = v_seq + 1;
        IF v_seq >= 4096 THEN
            DO SLEEP(0.001);
            SET v_now = UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000;
            SET v_seq = 0;
        END IF;
    ELSE
        SET v_seq = 0;
    END IF;

    UPDATE snowflake_seq
    SET last_timestamp = v_now, sequence = v_seq
    WHERE worker_id = v_worker_id;
    COMMIT;

    SET v_id = ((v_now - v_epoch) << 22) | (v_worker_id << 12) | v_seq;
    RETURN v_id;
END$$
DELIMITER ;

-- =============================================
-- 2. 权限资源表 ums_permission 初始化：目录+菜单
-- component_path 使用 @/views 别名；使用变量保存目录ID关联父子菜单
-- =============================================
-- 插入仪表盘目录
INSERT INTO `ums_permission`
(`id`, `parent_id`, `permission_name`, `title`, `permission_type`, `permission_code`, `route_path`, `component_path`, `api_method`, `api_path`, `icon`, `sort_order`, `is_frame`, `is_cache`, `is_visible`, `status`, `is_built_in`, `requires_auth`, `remark`, `deleted`, `create_user_id`, `update_user_id`)
VALUES(next_snowflake_id(), 0, '仪表盘', '仪表盘', 1, NULL, '/dashboard', '', NULL, NULL, 'dashboard', 1, 0, 1, 1, 1, 1, 1, '系统内置目录', 0, NULL, NULL);
SET @dashboard_id = LAST_INSERT_ID();

-- 插入RBAC权限管理目录
INSERT INTO `ums_permission`
(`id`, `parent_id`, `permission_name`, `title`, `permission_type`, `permission_code`, `route_path`, `component_path`, `api_method`, `api_path`, `icon`, `sort_order`, `is_frame`, `is_cache`, `is_visible`, `status`, `is_built_in`, `requires_auth`, `remark`, `deleted`, `create_user_id`, `update_user_id`)
VALUES(next_snowflake_id(), 0, '权限管理', '权限管理', 1, NULL, '/rbac', '', NULL, NULL, 'permission', 2, 0, 1, 1, 1, 1, 1, '系统内置目录', 0, NULL, NULL);
SET @rbac_id = LAST_INSERT_ID();

-- 批量子菜单
INSERT INTO `ums_permission`
(`id`, `parent_id`, `permission_name`, `title`, `permission_type`, `permission_code`, `route_path`, `component_path`, `api_method`, `api_path`, `icon`, `sort_order`, `is_frame`, `is_cache`, `is_visible`, `status`, `is_built_in`, `requires_auth`, `remark`, `deleted`, `create_user_id`, `update_user_id`)
VALUES
    (next_snowflake_id(), @dashboard_id, '工作台', '工作台', 2, 'dashboard:workspace', '/dashboard', '@/views/dashboard/index.vue', NULL, NULL, 'workspace', 0, 0, 1, 1, 1, 1, 1, '系统内置菜单', 0, NULL, NULL),
    (next_snowflake_id(), @rbac_id, '权限调试', '权限调试', 2, 'rbac:debug', '/rbac/debug', '@/views/rbac/debug/index.vue', NULL, NULL, 'bug', 0, 0, 0, 1, 1, 1, 1, '系统内置菜单', 0, NULL, NULL),
    (next_snowflake_id(), @rbac_id, '资源权限', '资源权限', 2, 'rbac:permission', '/rbac/permission', '@/views/rbac/permission/index.vue', NULL, NULL, 'tree', 1, 0, 1, 1, 1, 1, 1, '系统内置菜单', 0, NULL, NULL),
    (next_snowflake_id(), @rbac_id, '角色管理', '角色管理', 2, 'rbac:role', '/rbac/role', '@/views/rbac/role/index.vue', NULL, NULL, 'role', 2, 0, 1, 1, 1, 1, 1, '系统内置菜单', 0, NULL, NULL);

-- =============================================
-- 5.超级管理员 role_id=1 绑定权限：包含【目录权限】+菜单+接口权限
-- 注意：仪表盘目录无permission_code，使用parent_id=0且permission_type=1、title='仪表盘'过滤获取目录ID
-- =============================================
INSERT IGNORE INTO `ums_role_permission` (`id`, `role_id`, `permission_id`, `grant_user_id`)
SELECT
    next_snowflake_id(),
    1,
    p.id,
    NULL
FROM `ums_permission` p
WHERE
    (
        -- 仪表盘目录（无permission_code）
        (p.parent_id = 0 AND p.permission_type = 1 AND p.title = '仪表盘')
            OR
            -- RBAC权限管理目录（无permission_code）
        (p.parent_id = 0 AND p.permission_type = 1 AND p.title = '权限管理')
            OR
        p.permission_code IN (
            -- Controller接口权限
                              'system:rbac:debug:manage',
                              'system:permission:manage',
                              'system:role:manage',
                              'system:role:assign',
                              'system:user:role:manage',
            -- 前端菜单权限
                              'dashboard:workspace',
                              'rbac:debug',
                              'rbac:permission',
                              'rbac:role'
            )
        ) AND p.deleted = 0;

-- =============================================
-- 6.商家、运营、普通用户(role_id:2,3,4) 仅绑定仪表盘目录 + 工作台菜单
-- =============================================
INSERT IGNORE INTO `ums_role_permission` (`id`, `role_id`, `permission_id`, `grant_user_id`)
SELECT
    next_snowflake_id(),
    t.role_id,
    p.id,
    NULL
FROM
    (
        SELECT 2 AS role_id UNION ALL
        SELECT 3 AS role_id UNION ALL
        SELECT 4 AS role_id
    ) t
        CROSS JOIN `ums_permission` p
WHERE
    (
        -- 仪表盘目录
        (p.parent_id = 0 AND p.permission_type = 1 AND p.title = '仪表盘')
            OR
        p.permission_code = 'dashboard:workspace'
        )
  AND p.deleted = 0;

-- =============================================
-- 校验SQL
-- =============================================
/*
-- 查看超级管理员权限
SELECT rp.role_id, p.permission_type, p.title, p.permission_code
FROM ums_role_permission rp
LEFT JOIN ums_permission p ON rp.permission_id = p.id
WHERE rp.role_id = 1 ORDER BY rp.role_id,p.permission_type;

-- 查看商家/运营/普通用户权限
SELECT rp.role_id, p.permission_type, p.title, p.permission_code
FROM ums_role_permission rp
LEFT JOIN ums_permission p ON rp.permission_id = p.id
WHERE rp.role_id IN (2,3,4) ORDER BY rp.role_id,p.permission_type;
*/
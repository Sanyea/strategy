USE sys_strategy;

-- 临时放开binlog环境下的非确定性函数创建限制（全局生效，无需重启MySQL）
SET GLOBAL log_bin_trust_function_creators = 1;

-- =============================================
-- 1. 雪花算法依赖表 & next_snowflake_id() 函数
-- =============================================
DROP FUNCTION IF EXISTS next_snowflake_id;

CREATE TABLE IF NOT EXISTS `snowflake_seq`
(
    `worker_id`      TINYINT UNSIGNED NOT NULL COMMENT '机器ID 0‑1023',
    `last_timestamp` BIGINT UNSIGNED  NOT NULL COMMENT '上一次生成ID的时间戳(ms)',
    `sequence`       INT UNSIGNED     NOT NULL COMMENT '毫秒内序列号 0‑4095',
    PRIMARY KEY (`worker_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='雪花算法序列号状态表';

INSERT IGNORE INTO snowflake_seq(worker_id, last_timestamp, sequence)
VALUES (0, UNIX_TIMESTAMP() * 1000, 0);

DELIMITER $$
CREATE FUNCTION next_snowflake_id() RETURNS BIGINT UNSIGNED
    NOT DETERMINISTIC
    MODIFIES SQL DATA
BEGIN
    DECLARE v_worker_id TINYINT UNSIGNED DEFAULT 0;
    DECLARE v_epoch BIGINT UNSIGNED DEFAULT 1609459200000; -- 2021‑01‑01 00:00:00(ms)
    DECLARE v_now BIGINT UNSIGNED;
    DECLARE v_last_ts BIGINT UNSIGNED;
    DECLARE v_seq INT UNSIGNED;
    DECLARE v_id BIGINT UNSIGNED;

    -- 获取当前毫秒级时间戳
    SET v_now = ROUND(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000);

    -- 行锁保证并发安全（锁随调用方事务生命周期，无需显式事务）
    SELECT last_timestamp, sequence
    INTO v_last_ts, v_seq
    FROM snowflake_seq
    WHERE worker_id = v_worker_id FOR
    UPDATE;

    -- 时间回拨校验
    IF v_now < v_last_ts THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'next_snowflake_id: 系统时间回拨，无法生成雪花ID';
    END IF;

    -- 同一毫秒内序列号递增，跨毫秒重置为0
    IF v_now = v_last_ts THEN
        SET v_seq = v_seq + 1;
        -- 序列号溢出则等待1毫秒进入下一毫秒
        IF v_seq >= 4096 THEN
            DO SLEEP(0.001);
            SET v_now = ROUND(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000);
            SET v_seq = 0;
        END IF;
    ELSE
        SET v_seq = 0;
    END IF;

    -- 更新序列号状态
    UPDATE snowflake_seq
    SET last_timestamp = v_now,
        sequence       = v_seq
    WHERE worker_id = v_worker_id;

    -- 拼接雪花ID：时间戳差(41位) | worker_id(10位) | 序列号(12位)
    SET v_id = ((v_now - v_epoch) << 22) | (v_worker_id << 12) | v_seq;
    RETURN v_id;
END$$
DELIMITER ;

-- =============================================
-- 2. 权限资源表 ums_permission 初始化：目录+菜单
-- =============================================
-- 插入仪表盘目录（先生成ID再插入，保证父级ID关联正确）
SET @dashboard_id = next_snowflake_id();
INSERT INTO `ums_permission`
(`id`, `parent_id`, `permission_name`, `title`, `permission_type`, `permission_code`, `route_path`, `component_path`,
 `api_method`, `api_path`, `icon`, `sort_order`, `is_frame`, `is_cache`, `is_visible`, `status`, `is_built_in`,
 `requires_auth`, `remark`, `deleted`, `create_user_id`, `update_user_id`)
VALUES (@dashboard_id, 0, '仪表盘', '仪表盘', 1, 'dashboard', '/dashboard', '', NULL, NULL, 'dashboard', 1, 0, 1, 1, 1,
        1, 1, '系统内置目录', 0, NULL, NULL);

-- 插入RBAC权限管理目录
SET @rbac_id = next_snowflake_id();
INSERT INTO `ums_permission`
(`id`, `parent_id`, `permission_name`, `title`, `permission_type`, `permission_code`, `route_path`, `component_path`,
 `api_method`, `api_path`, `icon`, `sort_order`, `is_frame`, `is_cache`, `is_visible`, `status`, `is_built_in`,
 `requires_auth`, `remark`, `deleted`, `create_user_id`, `update_user_id`)
VALUES (@rbac_id, 0, '权限管理', '权限管理', 1, 'rbac', '/rbac', '', NULL, NULL, 'permission', 2, 0, 1, 1, 1, 1, 1,
        '系统内置目录', 0, NULL, NULL);

-- 批量插入子菜单
INSERT INTO `ums_permission`
(`id`, `parent_id`, `permission_name`, `title`, `permission_type`, `permission_code`, `route_path`, `component_path`,
 `api_method`, `api_path`, `icon`, `sort_order`, `is_frame`, `is_cache`, `is_visible`, `status`, `is_built_in`,
 `requires_auth`, `remark`, `deleted`, `create_user_id`, `update_user_id`)
VALUES (next_snowflake_id(), @dashboard_id, '工作台', '工作台', 2, 'dashboard:workspace', '/dashboard',
        '@/views/dashboard/index.vue', NULL, NULL, 'workspace', 0, 0, 1, 1, 1, 1, 1, '系统内置菜单', 0, NULL, NULL),
       (next_snowflake_id(), @rbac_id, '权限调试', '权限调试', 2, 'rbac:debug', '/rbac/debug',
        '@/views/rbac/debug/index.vue', NULL, NULL, 'bug', 0, 0, 0, 1, 1, 1, 1, '系统内置菜单', 0, NULL, NULL),
       (next_snowflake_id(), @rbac_id, '资源权限', '资源权限', 2, 'rbac:permission', '/rbac/permission',
        '@/views/rbac/permission/index.vue', NULL, NULL, 'tree', 1, 0, 1, 1, 1, 1, 1, '系统内置菜单', 0, NULL, NULL),
       (next_snowflake_id(), @rbac_id, '角色管理', '角色管理', 2, 'rbac:role', '/rbac/role',
        '@/views/rbac/role/index.vue', NULL, NULL, 'role', 2, 0, 1, 1, 1, 1, 1, '系统内置菜单', 0, NULL, NULL);

-- =============================================
-- 5. 超级管理员 role_id=1 绑定权限：目录+菜单+接口权限
-- =============================================
INSERT IGNORE INTO `ums_role_permission` (`id`, `role_id`, `permission_id`, `grant_user_id`)
SELECT next_snowflake_id(),
       1,
       p.id,
       NULL
FROM `ums_permission` p
WHERE p.permission_code IN (
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
                            'rbac:role',
    -- 目录权限
                            'rbac',
                            'dashboard'
    )
  AND p.deleted = 0;

-- =============================================
-- 6. 商家、运营、普通用户(role_id:2,3,4) 仅绑定仪表盘目录 + 工作台菜单
-- =============================================
INSERT IGNORE INTO `ums_role_permission` (`id`, `role_id`, `permission_id`, `grant_user_id`)
SELECT next_snowflake_id(),
       t.role_id,
       p.id,
       NULL
FROM (SELECT 2 AS role_id
      UNION ALL
      SELECT 3 AS role_id
      UNION ALL
      SELECT 4 AS role_id) t
         CROSS JOIN `ums_permission` p
WHERE (
    -- 仪表盘目录
    (p.parent_id = 0 AND p.permission_type = 1 AND p.permission_code = 'dashboard')
        OR
    p.permission_code = 'dashboard:workspace'
    )
  AND p.deleted = 0;
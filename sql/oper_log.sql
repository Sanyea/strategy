
create database if not exists sys_strategy;

use sys_strategy;

CREATE TABLE IF NOT EXISTS `ums_oper_log`
(
    `id`             BIGINT UNSIGNED  NOT NULL COMMENT '主键ID',
    `user_id`        BIGINT UNSIGNED  DEFAULT NULL COMMENT '操作用户ID',
    `username`       VARCHAR(120)     DEFAULT '' COMMENT '操作用户账号快照',
    `oper_module`    VARCHAR(100)     DEFAULT '' COMMENT '操作模块',
    `oper_action`    VARCHAR(100)     DEFAULT '' COMMENT '操作动作',
    `oper_desc`      VARCHAR(500)     DEFAULT '' COMMENT '操作说明',
    `oper_type`      TINYINT UNSIGNED DEFAULT 0 COMMENT '操作类型：1-新增 2-修改 3-删除 4-查询 5-授权 6-导入 7-导出 8-其他',
    `request_method` VARCHAR(10)      DEFAULT '' COMMENT 'HTTP方法',
    `request_uri`    VARCHAR(255)     DEFAULT '' COMMENT '请求URI',
    `request_params` TEXT             COMMENT '请求参数，建议存JSON或截断',
    `request_body`   TEXT             COMMENT '请求体',
    `response_code`  VARCHAR(20)      DEFAULT '' COMMENT '响应码',
    `response_msg`   VARCHAR(500)     DEFAULT '' COMMENT '响应信息',
    `cost_time`      INT UNSIGNED     DEFAULT 0 COMMENT '耗时，毫秒',
    `oper_ip`        VARCHAR(64)      DEFAULT '' COMMENT '操作IP',
    `user_agent`     VARCHAR(500)     DEFAULT '' COMMENT '浏览器UA',
    `status`         TINYINT UNSIGNED DEFAULT 1 COMMENT '操作结果 0-失败 1-成功',
    `error_msg`      TEXT             COMMENT '错误信息',
    `oper_time`      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    `create_time`    DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`) USING BTREE,
    KEY `idx_user_id` (`user_id`),
    KEY `idx_oper_module` (`oper_module`),
    KEY `idx_status` (`status`),
    KEY `idx_oper_time` (`oper_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='操作日志表，仅插入，不物理更新，不逻辑删除';

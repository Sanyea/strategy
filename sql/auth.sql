
create database if not exists sys_strategy;

use sys_strategy;

CREATE TABLE if not exists `ums_user`
(
    `id`                   BIGINT UNSIGNED  NOT NULL COMMENT '用户ID(雪花算法)',
    `username`             VARCHAR(120)     NOT NULL COMMENT '登录账号(唯一)',
    `nickname`             VARCHAR(50)               DEFAULT '' COMMENT '用户昵称',
    `real_name`            VARCHAR(50)               DEFAULT '' COMMENT '真实姓名',
    `password`             VARCHAR(100)     NOT NULL COMMENT '加密密码(BCrypt/Argon2)',
    `salt`                 VARCHAR(32)               DEFAULT '' COMMENT '密码盐(兼容旧体系，新密码算法可空)',
    `phone`                VARCHAR(48)               DEFAULT NULL COMMENT '手机号',
    `phone_country_code`   VARCHAR(12)               DEFAULT '+86' COMMENT '手机国家码',
    `email`                VARCHAR(180)              DEFAULT NULL COMMENT '邮箱',
    `avatar`               VARCHAR(255)              DEFAULT '' COMMENT '头像URL',
    `gender`               TINYINT UNSIGNED          DEFAULT 0 COMMENT '性别 0-未知 1-男 2-女',
    `birthday`             DATE                      DEFAULT NULL COMMENT '出生日期',
    `id_card_no`           VARCHAR(64)               DEFAULT '' COMMENT '身份证号(加密存储)',
    `id_card_status`       TINYINT UNSIGNED          DEFAULT 0 COMMENT '实名认证状态 0-未认证 1-认证中 2-已认证 3-认证失败',
    `user_status`          TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '账号状态 0-注销 1-正常 2-冻结 3-禁言',
    `register_channel`     TINYINT UNSIGNED          DEFAULT 0 COMMENT '注册渠道 0-未知 1-APP 2-小程序 3-H5 4-PC 5-第三方授权（前端显式传，后端校验，当前仅 H5/PC）',
    `register_client_ip`   VARCHAR(64)               DEFAULT '' COMMENT '注册IP',
    `register_device_id`   VARCHAR(100)              DEFAULT '' COMMENT '注册设备ID',
    `last_login_time`      DATETIME                  DEFAULT NULL COMMENT '最后登录时间',
    `last_login_ip`        VARCHAR(64)               DEFAULT '' COMMENT '最后登录IP',
    `last_login_device_id` VARCHAR(100)              DEFAULT '' COMMENT '最后登录设备ID',
    `user_level`           TINYINT UNSIGNED          DEFAULT 0 COMMENT '用户等级',
    `user_point`           INT UNSIGNED              DEFAULT 0 COMMENT '用户积分',
    `user_balance`         DECIMAL(12, 2)            DEFAULT 0.00 COMMENT '账户余额(建议独立表)',
    `is_vip`               TINYINT UNSIGNED          DEFAULT 0 COMMENT '是否VIP 0-否 1-是',
    `vip_expire_time`      DATETIME                  DEFAULT NULL COMMENT 'VIP过期时间',
    `remark`               VARCHAR(500)              DEFAULT '' COMMENT '备注',
    `deleted`              TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '删除标识 0-未删除 1-已删除(阿里规范)',
    `create_user_id`       BIGINT UNSIGNED           DEFAULT NULL COMMENT '创建人ID',
    `update_user_id`       BIGINT UNSIGNED           DEFAULT NULL COMMENT '更新人ID',
    `create_time`          DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`          DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_phone` (`phone`, `phone_country_code`) COMMENT '手机号+国家码唯一',
    UNIQUE KEY `uk_email` (`email`),
    KEY `idx_user_status` (`user_status`),
    KEY `idx_deleted` (`deleted`),
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户主表';

CREATE TABLE if not exists `ums_user_account_security`
(
    `id`                       BIGINT UNSIGNED  NOT NULL COMMENT '主键ID',
    `user_id`                  BIGINT UNSIGNED  NOT NULL COMMENT '用户ID',
    `password_error_count`     TINYINT UNSIGNED          DEFAULT 0 COMMENT '密码错误次数',
    `lock_time`                DATETIME                  DEFAULT NULL COMMENT '账号锁定截止时间',
    `last_password_reset_time` DATETIME                  DEFAULT NULL COMMENT '最后修改密码时间',
    `has_set_pay_password`     TINYINT UNSIGNED          DEFAULT 0 COMMENT '是否设置支付密码',
    `pay_password`             VARCHAR(100)              DEFAULT '' COMMENT '支付密码(加密)',
    `pay_salt`                 VARCHAR(32)               DEFAULT '' COMMENT '支付密码盐',
    `secret_question_status`   TINYINT UNSIGNED          DEFAULT 0 COMMENT '是否设置密保问题',
    `mfa_status`               TINYINT UNSIGNED          DEFAULT 0 COMMENT '双因素认证状态 0-关闭 1-开启',
    `mfa_secret`               VARCHAR(100)              DEFAULT '' COMMENT '双因素密钥',
    `deleted`                  TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '删除标识',
    `create_time`              DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`              DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户账号安全表';

CREATE TABLE if not exists `ums_user_login_device`
(
    `id`                  BIGINT UNSIGNED  NOT NULL COMMENT '主键ID',
    `user_id`             BIGINT UNSIGNED  NOT NULL COMMENT '用户ID',
    `device_type`         TINYINT UNSIGNED          DEFAULT 0 COMMENT '设备类型 1-手机 2-平板 3-PC 4-小程序',
    `device_os`           VARCHAR(30)               DEFAULT '' COMMENT '操作系统',
    `device_brand`        VARCHAR(50)               DEFAULT '' COMMENT '设备品牌',
    `device_model`        VARCHAR(50)               DEFAULT '' COMMENT '设备型号',
    `device_id`           VARCHAR(100)     NOT NULL COMMENT '设备唯一ID',
    `app_version`         VARCHAR(30)               DEFAULT '' COMMENT 'APP版本',
    `login_ip`            VARCHAR(64)               DEFAULT '' COMMENT '登录IP',
    `login_time`          DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
    `expire_time`         DATETIME                  DEFAULT NULL COMMENT 'Token过期时间',
    `is_current`          TINYINT UNSIGNED          DEFAULT 1 COMMENT '是否当前有效设备',
    `refresh_token_hash`  CHAR(64)                    DEFAULT NULL COMMENT 'refreshToken SHA-256 哈希（Hex，非明文）',
    `login_type`          TINYINT UNSIGNED          DEFAULT 0 COMMENT '登入方式 0-未知 1-手机号 2-验证码 3-账号密码 4-第三方授权（前端显式传，后端校验，当前仅账号密码）',
    `login_channel`       TINYINT UNSIGNED          DEFAULT 0 COMMENT '登录渠道 0-未知 1-APP 2-小程序 3-H5 4-PC 5-第三方授权（前端显式传，后端校验，当前仅 H5/PC）',
    `deleted`             TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '删除标识',
    `create_time`         DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`         DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    KEY `idx_user_id` (`user_id`),
    KEY `idx_device_id` (`device_id`),
    KEY `idx_user_current` (`user_id`, `is_current`),
    KEY `idx_refresh_token_hash` (`refresh_token_hash`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户登录设备表';
#
# ALTER TABLE `ums_user_login_device`
#     ADD COLUMN `login_type`    TINYINT UNSIGNED DEFAULT 0 COMMENT '登入方式 0-未知 1-手机号 2-验证码 3-账号密码 4-第三方授权（前端显式传，后端校验，当前仅账号密码）',
#     ADD COLUMN `login_channel` TINYINT UNSIGNED DEFAULT 0 COMMENT '登录渠道 0-未知 1-APP 2-小程序 3-H5 4-PC 5-第三方授权（前端显式传，后端校验，当前仅 H5/PC）';

CREATE TABLE if not exists `ums_user_auth`
(
    `id`            BIGINT UNSIGNED  NOT NULL COMMENT '主键ID',
    `user_id`       BIGINT UNSIGNED  NOT NULL COMMENT '用户ID',
    `identity_type` VARCHAR(30)      NOT NULL COMMENT '认证类型 WX_MP/WX_OPEN/ALIPAY/APPLE/WEIBO',
    `identifier`    VARCHAR(100)     NOT NULL COMMENT '第三方唯一标识(openid/unionid)',
    `credential`    VARCHAR(255)              DEFAULT '' COMMENT '凭证(token/密钥)',
    `union_id`      VARCHAR(100)              DEFAULT '' COMMENT '微信unionID',
    `nickname`      VARCHAR(50)               DEFAULT '' COMMENT '第三方昵称',
    `avatar`        VARCHAR(255)              DEFAULT '' COMMENT '第三方头像',
    `status`        TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '状态 0-失效 1-有效',
    `deleted`       TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '删除标识',
    `create_time`   DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_type_identifier` (`identity_type`, `identifier`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='第三方登录关联表';

CREATE TABLE if not exists `ums_user_profile`
(
    `id`            BIGINT UNSIGNED  NOT NULL COMMENT '主键ID',
    `user_id`       BIGINT UNSIGNED  NOT NULL COMMENT '用户ID',
    `country`       VARCHAR(30)               DEFAULT '' COMMENT '国家',
    `province`      VARCHAR(30)               DEFAULT '' COMMENT '省份',
    `city`          VARCHAR(30)               DEFAULT '' COMMENT '城市',
    `district`      VARCHAR(30)               DEFAULT '' COMMENT '区县',
    `address`       VARCHAR(255)              DEFAULT '' COMMENT '详细地址',
    `occupation`    VARCHAR(50)               DEFAULT '' COMMENT '职业',
    `education`     TINYINT UNSIGNED          DEFAULT 0 COMMENT '学历 0-未知 1-初中 2-高中 3-大专 4-本科 5-硕士 6-博士',
    `income_level`  TINYINT UNSIGNED          DEFAULT 0 COMMENT '收入水平 0-未知 1-3k以下 2-3k-8k 3-8k-15k 4-15k-30k 5-30k以上',
    `contact_phone` VARCHAR(20)               DEFAULT '' COMMENT '备用联系电话',
    `contact_email` VARCHAR(100)              DEFAULT '' COMMENT '备用邮箱',
    `signature`     VARCHAR(200)              DEFAULT '' COMMENT '个性签名',
    `bg_image`      VARCHAR(255)              DEFAULT '' COMMENT '个人主页背景图',
    `ext_info`      JSON                      DEFAULT NULL COMMENT '扩展字段(JSON)',
    `deleted`       TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '删除标识',
    `create_time`   DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户扩展信息表';

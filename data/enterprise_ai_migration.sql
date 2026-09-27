-- 企业级 AI 业务主线迁移
-- 目标：任务、计划版本、审计、成本和领域事件可恢复、可追踪、可重放。
-- 兼容 MySQL 5.7/8.0；生产执行前请纳入正式迁移工具并在备份上演练。
USE travel_agent;

CREATE TABLE IF NOT EXISTS ai_task (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id VARCHAR(64) NOT NULL,
  owner_id VARCHAR(64) NOT NULL,
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
  idempotency_key VARCHAR(128) NULL,
  request_json JSON NOT NULL,
  output_json JSON NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  progress INT NOT NULL DEFAULT 0,
  attempt INT NOT NULL DEFAULT 0,
  plan_id VARCHAR(64) NULL,
  error_code VARCHAR(64) NULL,
  error_message VARCHAR(1024) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  started_at DATETIME NULL,
  completed_at DATETIME NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_task_id (task_id),
  UNIQUE KEY uk_ai_task_owner_idempotency (owner_id, idempotency_key),
  KEY idx_ai_task_owner_status (owner_id, status, created_at),
  KEY idx_ai_task_plan (plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI规划任务持久化';

CREATE TABLE IF NOT EXISTS ai_plan (
  id BIGINT NOT NULL AUTO_INCREMENT,
  plan_id VARCHAR(64) NOT NULL,
  task_id VARCHAR(64) NOT NULL,
  owner_id VARCHAR(64) NOT NULL,
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
  destination VARCHAR(128) NOT NULL DEFAULT '',
  lifecycle_status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  current_version INT NOT NULL DEFAULT 1,
  start_date DATE NULL,
  end_date DATE NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_plan_id (plan_id),
  UNIQUE KEY uk_ai_plan_task (task_id),
  KEY idx_ai_plan_owner_status (owner_id, lifecycle_status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI旅行计划主表';

CREATE TABLE IF NOT EXISTS ai_plan_version (
  id BIGINT NOT NULL AUTO_INCREMENT,
  plan_id VARCHAR(64) NOT NULL,
  version_no INT NOT NULL,
  request_json JSON NOT NULL,
  output_json JSON NOT NULL,
  quality_status VARCHAR(24) NOT NULL DEFAULT 'VALIDATED',
  quality_score DECIMAL(5,2) NULL,
  warning_json JSON NULL,
  provider VARCHAR(64) NULL,
  model_name VARCHAR(128) NULL,
  prompt_version VARCHAR(64) NULL,
  created_by VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_plan_version (plan_id, version_no),
  KEY idx_ai_plan_version_created (plan_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI旅行计划不可变版本';

CREATE TABLE IF NOT EXISTS ai_audit_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id VARCHAR(64) NOT NULL,
  actor_id VARCHAR(64) NOT NULL,
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
  action VARCHAR(64) NOT NULL,
  resource_type VARCHAR(64) NOT NULL,
  resource_id VARCHAR(128) NOT NULL,
  outcome VARCHAR(24) NOT NULL DEFAULT 'SUCCESS',
  trace_id VARCHAR(64) NULL,
  metadata_json JSON NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_audit_event_id (event_id),
  KEY idx_ai_audit_resource (resource_type, resource_id, created_at),
  KEY idx_ai_audit_actor (actor_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI业务审计事件';

CREATE TABLE IF NOT EXISTS ai_usage_ledger (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id VARCHAR(64) NOT NULL,
  owner_id VARCHAR(64) NOT NULL,
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
  provider VARCHAR(64) NOT NULL DEFAULT 'unknown',
  model_name VARCHAR(128) NOT NULL DEFAULT 'unknown',
  prompt_tokens BIGINT NULL,
  completion_tokens BIGINT NULL,
  latency_ms BIGINT NOT NULL DEFAULT 0,
  estimated_cost DECIMAL(18,6) NULL,
  status VARCHAR(24) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_ai_usage_owner_time (owner_id, created_at),
  KEY idx_ai_usage_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI调用成本台账';

CREATE TABLE IF NOT EXISTS ai_outbox_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id VARCHAR(64) NOT NULL,
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_id VARCHAR(128) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  payload_json JSON NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_error VARCHAR(1024) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_outbox_event_id (event_id),
  KEY idx_ai_outbox_dispatch (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI领域事件发件箱';

-- 将已有 travel_note 与可恢复 AI 计划关联，旧数据不受影响。
ALTER TABLE travel_note ADD COLUMN plan_id VARCHAR(64) NULL COMMENT '关联 AI 计划';
ALTER TABLE travel_note ADD INDEX idx_travel_note_plan_id (plan_id);

-- 第一条收入路径：计划关联联盟商品，记录点击、订单和支付事件。
CREATE TABLE IF NOT EXISTS commerce_offer (
  id BIGINT NOT NULL AUTO_INCREMENT,
  offer_id VARCHAR(64) NOT NULL,
  provider_code VARCHAR(64) NOT NULL,
  offer_type VARCHAR(32) NOT NULL,
  title VARCHAR(200) NOT NULL,
  destination VARCHAR(128) NOT NULL DEFAULT '',
  redirect_url VARCHAR(1024) NOT NULL,
  price DECIMAL(12,2) NOT NULL DEFAULT 0,
  currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
  commission_rate DECIMAL(8,4) NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_commerce_offer_id (offer_id),
  KEY idx_commerce_offer_destination (destination, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='旅行商业化商品';

CREATE TABLE IF NOT EXISTS commerce_click (
  id BIGINT NOT NULL AUTO_INCREMENT,
  click_id VARCHAR(64) NOT NULL,
  plan_id VARCHAR(64) NOT NULL,
  owner_id VARCHAR(64) NOT NULL,
  offer_id VARCHAR(64) NOT NULL,
  attribution_code VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_commerce_click_id (click_id),
  KEY idx_commerce_click_plan (plan_id, owner_id, created_at),
  KEY idx_commerce_click_offer (offer_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='联盟商品点击归因';

CREATE TABLE IF NOT EXISTS commerce_order (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_no VARCHAR(64) NOT NULL,
  plan_id VARCHAR(64) NOT NULL,
  owner_id VARCHAR(64) NOT NULL,
  offer_id VARCHAR(64) NOT NULL,
  provider_code VARCHAR(64) NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING_PAYMENT',
  idempotency_key VARCHAR(128) NULL,
  paid_at DATETIME NULL,
  cancelled_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_commerce_order_no (order_no),
  UNIQUE KEY uk_commerce_order_idempotency (owner_id, idempotency_key),
  KEY idx_commerce_order_owner_status (owner_id, status, created_at),
  KEY idx_commerce_order_plan (plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='旅行商业订单';

CREATE TABLE IF NOT EXISTS commerce_payment_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id VARCHAR(128) NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  provider_code VARCHAR(64) NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  amount DECIMAL(12,2) NULL,
  payload_json JSON NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_commerce_payment_event (event_id),
  KEY idx_commerce_payment_order (order_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付回调幂等事件';

CREATE TABLE IF NOT EXISTS commerce_entitlement (
  id BIGINT NOT NULL AUTO_INCREMENT,
  entitlement_id VARCHAR(64) NOT NULL,
  owner_id VARCHAR(64) NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  entitlement_type VARCHAR(32) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  valid_until DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_commerce_entitlement_id (entitlement_id),
  UNIQUE KEY uk_commerce_entitlement_order (order_no, entitlement_type),
  KEY idx_commerce_entitlement_owner (owner_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单产生的用户权益';

-- 本地验收种子；生产环境替换为真实供应商同步任务，不直接使用该跳转地址。
INSERT IGNORE INTO commerce_offer(offer_id,provider_code,offer_type,title,destination,redirect_url,price,currency,commission_rate,status)
VALUES ('offer_demo_hotel','sandbox','HOTEL','示例住宿供应商','通用','https://example.com/travel/offer/offer_demo_hotel',199.00,'CNY',0.0500,'ACTIVE');

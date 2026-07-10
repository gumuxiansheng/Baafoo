-- ============================================================
-- Baafoo PostgreSQL Schema
-- Executed by DdlBuilder with per-statement error tolerance.
-- Comments (--) and empty lines are stripped before execution.
-- ============================================================

-- --- Tables ---
CREATE TABLE IF NOT EXISTS rules (
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(255),
  protocol VARCHAR(50),
  service_name VARCHAR(255),
  host VARCHAR(255),
  port INT,
  conditions_json TEXT,
  responses_json TEXT,
  enabled BOOLEAN DEFAULT TRUE,
  priority INT DEFAULT 100,
  tags_json TEXT,
  environments_json TEXT,
  version INT DEFAULT 1,
  created_at BIGINT,
  updated_at BIGINT
);
ALTER TABLE rules ADD COLUMN environments_json TEXT;
ALTER TABLE rules ADD COLUMN tcp_rounds_json TEXT;
ALTER TABLE rules ADD COLUMN tcp_loop BOOLEAN DEFAULT FALSE;
ALTER TABLE rules ADD COLUMN tcp_pattern VARCHAR(1024);
ALTER TABLE rules ADD COLUMN tcp_prefix_hex VARCHAR(1024);
ALTER TABLE rules ADD COLUMN tcp_offset_start INT DEFAULT -1;
ALTER TABLE rules ADD COLUMN tcp_offset_end INT DEFAULT -1;
ALTER TABLE rules ADD COLUMN tcp_offset_hex VARCHAR(1024);
ALTER TABLE rules ADD COLUMN faker_seed BIGINT;
ALTER TABLE rules ADD COLUMN request_count_reset INT;
ALTER TABLE rules ADD COLUMN fault_injection_json TEXT;

CREATE TABLE IF NOT EXISTS rule_history (
  id BIGSERIAL PRIMARY KEY,
  rule_id VARCHAR(36) NOT NULL,
  rule_snapshot TEXT NOT NULL,
  created_at BIGINT
);

CREATE TABLE IF NOT EXISTS environments (
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  mode VARCHAR(50) DEFAULT 'STUB',
  agent_ids_json TEXT,
  variables_json TEXT,
  metadata_json TEXT,
  created_at BIGINT,
  updated_at BIGINT
);

CREATE TABLE IF NOT EXISTS scene_sets (
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(255),
  description TEXT,
  item_ids_json TEXT,
  active BOOLEAN DEFAULT FALSE,
  tags_json TEXT,
  environments_json TEXT,
  created_at BIGINT,
  updated_at BIGINT
);
ALTER TABLE scene_sets ADD COLUMN environments_json TEXT;

CREATE TABLE IF NOT EXISTS rule_sets (
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(255),
  description TEXT,
  rule_ids_json TEXT,
  enabled BOOLEAN DEFAULT TRUE,
  tags_json TEXT,
  created_at BIGINT,
  updated_at BIGINT
);

CREATE TABLE IF NOT EXISTS mq_relationships (
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(255),
  from_protocol VARCHAR(50),
  from_topic VARCHAR(255),
  to_protocol VARCHAR(50),
  to_topic VARCHAR(255),
  key_template TEXT,
  value_template TEXT,
  delay_ms BIGINT DEFAULT 0,
  enabled BOOLEAN DEFAULT TRUE,
  created_at BIGINT,
  updated_at BIGINT
);

CREATE TABLE IF NOT EXISTS recordings (
  id VARCHAR(36) PRIMARY KEY,
  rule_id VARCHAR(36),
  environment_id VARCHAR(36),
  agent_id VARCHAR(36),
  agent_ip VARCHAR(45),
  protocol VARCHAR(50),
  host VARCHAR(255),
  port INT,
  service_name VARCHAR(255),
  method VARCHAR(20),
  path VARCHAR(2000),
  request_headers_json TEXT,
  request_body TEXT,
  response_status_code INT,
  response_headers_json TEXT,
  response_body TEXT,
  response_time_ms BIGINT,
  recorded_at BIGINT,
  tags_json TEXT
);
ALTER TABLE recordings ADD COLUMN agent_ip VARCHAR(45);
ALTER TABLE recordings ADD COLUMN direction VARCHAR(20);
ALTER TABLE recordings ADD COLUMN session_id VARCHAR(36);
ALTER TABLE recordings ADD COLUMN data_hex TEXT;
ALTER TABLE recordings ADD COLUMN duration_ms BIGINT;
ALTER TABLE recordings ADD COLUMN response_source VARCHAR(20);
ALTER TABLE recordings ALTER COLUMN path TYPE VARCHAR(2000);

CREATE TABLE IF NOT EXISTS agents (
  agent_id VARCHAR(36) PRIMARY KEY,
  environment VARCHAR(255),
  hostname VARCHAR(255),
  version VARCHAR(50),
  protocols_json TEXT,
  agent_ip VARCHAR(45),
  registered_at BIGINT,
  last_heartbeat BIGINT
);
ALTER TABLE agents ADD COLUMN agent_ip VARCHAR(45);

CREATE TABLE IF NOT EXISTS users (
  id VARCHAR(36) PRIMARY KEY,
  username VARCHAR(255) NOT NULL UNIQUE,
  password_hash VARCHAR(512) NOT NULL,
  display_name VARCHAR(255),
  email VARCHAR(255),
  role VARCHAR(50) DEFAULT 'guest',
  api_key VARCHAR(255),
  created_at BIGINT,
  updated_at BIGINT,
  last_login_at BIGINT
);
ALTER TABLE users ADD COLUMN display_name VARCHAR(255);
ALTER TABLE users ADD COLUMN email VARCHAR(255);

-- --- Indexes ---
CREATE INDEX IF NOT EXISTS idx_rules_protocol ON rules(protocol);
CREATE INDEX IF NOT EXISTS idx_rules_enabled ON rules(enabled);
CREATE INDEX IF NOT EXISTS idx_rules_priority ON rules(priority);
CREATE INDEX IF NOT EXISTS idx_environments_name ON environments(name);
CREATE INDEX IF NOT EXISTS idx_recordings_rule_id ON recordings(rule_id);
CREATE INDEX IF NOT EXISTS idx_recordings_recorded_at ON recordings(recorded_at);
CREATE INDEX IF NOT EXISTS idx_recordings_agent_id ON recordings(agent_id);
CREATE INDEX IF NOT EXISTS idx_recordings_protocol ON recordings(protocol);
CREATE INDEX IF NOT EXISTS idx_recordings_method ON recordings(method);
CREATE INDEX IF NOT EXISTS idx_recordings_status_code ON recordings(response_status_code);
CREATE INDEX IF NOT EXISTS idx_recordings_agent_ip ON recordings(agent_ip);
CREATE INDEX IF NOT EXISTS idx_recordings_path ON recordings(path);
CREATE INDEX IF NOT EXISTS idx_recordings_session_id ON recordings(session_id);
CREATE INDEX IF NOT EXISTS idx_recordings_direction ON recordings(direction);
CREATE INDEX IF NOT EXISTS idx_agents_environment ON agents(environment);
CREATE INDEX IF NOT EXISTS idx_rule_history_rule_id ON rule_history(rule_id);
CREATE INDEX IF NOT EXISTS idx_mq_relationships_from ON mq_relationships(from_protocol, from_topic);
CREATE INDEX IF NOT EXISTS idx_mq_relationships_to ON mq_relationships(to_protocol, to_topic);
CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);
CREATE INDEX IF NOT EXISTS idx_users_api_key ON users(api_key);

-- --- PostgreSQL Full-Text Search ---
ALTER TABLE recordings ADD COLUMN search_vector tsvector;
CREATE INDEX IF NOT EXISTS idx_recordings_search ON recordings USING GIN(search_vector);

CREATE OR REPLACE FUNCTION recordings_search_trigger() RETURNS trigger AS $$
BEGIN
  NEW.search_vector :=
    setweight(to_tsvector('pg_catalog.english', COALESCE(NEW.path, '')), 'A') ||
    setweight(to_tsvector('pg_catalog.english', COALESCE(NEW.request_headers_json, '')), 'B') ||
    setweight(to_tsvector('pg_catalog.english', COALESCE(NEW.response_headers_json, '')), 'B') ||
    setweight(to_tsvector('pg_catalog.english', COALESCE(NEW.request_body, '')), 'C') ||
    setweight(to_tsvector('pg_catalog.english', COALESCE(NEW.response_body, '')), 'C');
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS recordings_search_update ON recordings;
CREATE TRIGGER recordings_search_update
  BEFORE INSERT OR UPDATE ON recordings
  FOR EACH ROW EXECUTE FUNCTION recordings_search_trigger();

UPDATE recordings SET search_vector =
  setweight(to_tsvector('pg_catalog.english', COALESCE(path, '')), 'A') ||
  setweight(to_tsvector('pg_catalog.english', COALESCE(request_headers_json, '')), 'B') ||
  setweight(to_tsvector('pg_catalog.english', COALESCE(response_headers_json, '')), 'B') ||
  setweight(to_tsvector('pg_catalog.english', COALESCE(request_body, '')), 'C') ||
  setweight(to_tsvector('pg_catalog.english', COALESCE(response_body, '')), 'C')
WHERE search_vector IS NULL;

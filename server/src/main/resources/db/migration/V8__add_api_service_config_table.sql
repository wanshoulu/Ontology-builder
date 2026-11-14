-- 本体 API 服务配置表
CREATE TABLE IF NOT EXISTS tm_api_service_config (
    id          VARCHAR(36) PRIMARY KEY,
    tenant_id   VARCHAR(36) NOT NULL DEFAULT 'default',
    name        VARCHAR(200) NOT NULL,
    ontology_id VARCHAR(36),
    base_url    VARCHAR(500) NOT NULL,
    api_type    VARCHAR(20) NOT NULL DEFAULT 'sparql',
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    max_query_limit INTEGER NOT NULL DEFAULT 1000,
    timeout     INTEGER NOT NULL DEFAULT 30,
    auth_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    auth_token  VARCHAR(500),
    description VARCHAR(500),
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_api_svc_config_tenant ON tm_api_service_config(tenant_id);
CREATE INDEX idx_api_svc_config_ontology ON tm_api_service_config(ontology_id);

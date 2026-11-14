-- 实例化任务表
CREATE TABLE IF NOT EXISTS tm_instantiation_task (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    name VARCHAR(200) NOT NULL,
    ontology_id VARCHAR(36) NOT NULL,
    datasource_id VARCHAR(36) NOT NULL,
    table_class_mapping TEXT,
    field_property_mapping TEXT,
    reasoning_config TEXT,
    preview_info TEXT,
    result_info TEXT,
    error_log TEXT,
    status VARCHAR(50) DEFAULT 'DRAFT',
    step VARCHAR(50) NOT NULL DEFAULT 'DATA_SOURCE_SELECT',
    description VARCHAR(500),
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_inst_task_tenant ON tm_instantiation_task(tenant_id);
CREATE INDEX idx_inst_task_ontology ON tm_instantiation_task(ontology_id);
CREATE INDEX idx_inst_task_status ON tm_instantiation_task(status);

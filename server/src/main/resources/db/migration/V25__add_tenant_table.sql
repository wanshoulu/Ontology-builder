-- V25: 租户表 + 本体实例表（多租户本体实例化管理）
-- 租户 = 组织单元，本体实例 = 租户下的命名图加载目标
-- 同一租户可建多个本体实例，同一实例可被多次流水线作业增量追加

-- 1. 租户表（组织单元）
CREATE TABLE IF NOT EXISTS tm_tenant (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_tenant_name ON tm_tenant(name);

INSERT INTO tm_tenant (id, name, description)
VALUES ('default', '默认租户', '系统默认租户')
ON CONFLICT DO NOTHING;

-- 2. 本体实例表（每个实例对应 Jena TDB2 中一个命名图）
CREATE TABLE IF NOT EXISTS tm_ontology_instance (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tm_tenant(id),
    name VARCHAR(200) NOT NULL,
    graph_uri VARCHAR(500) NOT NULL,
    ontology_id VARCHAR(36),
    description VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_instance_graph_uri UNIQUE (graph_uri)
);

CREATE INDEX IF NOT EXISTS idx_inst_tenant ON tm_ontology_instance(tenant_id);
CREATE INDEX IF NOT EXISTS idx_inst_name ON tm_ontology_instance(name);

-- 插入默认本体实例
INSERT INTO tm_ontology_instance (id, tenant_id, name, graph_uri, description)
VALUES ('default', 'default', '默认实例', 'http://ontomind.com/instance/default', '系统默认本体实例')
ON CONFLICT DO NOTHING;

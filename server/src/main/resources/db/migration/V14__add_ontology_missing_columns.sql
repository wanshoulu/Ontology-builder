-- V14: 补充 tm_ontology 表缺失的列

-- 添加 version_name 列
ALTER TABLE tm_ontology ADD COLUMN IF NOT EXISTS version_name VARCHAR(200);

-- 添加 ttl_path 列
ALTER TABLE tm_ontology ADD COLUMN IF NOT EXISTS ttl_path VARCHAR(500);

-- 添加 instantiated 列
ALTER TABLE tm_ontology ADD COLUMN IF NOT EXISTS instantiated BOOLEAN DEFAULT FALSE;
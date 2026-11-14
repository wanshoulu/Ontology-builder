-- V22: 补充 tm_ontology 表的来源信息列

-- 关联的 MD 文件名
ALTER TABLE tm_ontology ADD COLUMN IF NOT EXISTS source_md_file_name VARCHAR(500);

-- 关联的 MD 文件 ID
ALTER TABLE tm_ontology ADD COLUMN IF NOT EXISTS source_md_file_id VARCHAR(36);

-- 关联的大模型名称
ALTER TABLE tm_ontology ADD COLUMN IF NOT EXISTS source_model_name VARCHAR(200);

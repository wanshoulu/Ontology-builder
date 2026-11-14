-- 本体实例化多版本控制
-- 1. 添加版本号字段
ALTER TABLE tm_instantiation_task ADD COLUMN IF NOT EXISTS version VARCHAR(50) DEFAULT '1.0.0';
-- 2. 添加加载模式字段: OVERWRITE(全量覆盖) / APPEND(增量追加)
ALTER TABLE tm_instantiation_task ADD COLUMN IF NOT EXISTS load_mode VARCHAR(20) DEFAULT 'OVERWRITE';
-- 3. 添加Jena命名图名称字段，记录该版本对应TDB2中的命名图
ALTER TABLE tm_instantiation_task ADD COLUMN IF NOT EXISTS jena_model_name VARCHAR(200);
-- 4. 添加索引
CREATE INDEX IF NOT EXISTS idx_inst_task_version ON tm_instantiation_task(ontology_id, version);

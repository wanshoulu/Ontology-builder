-- V15: 补充 tm_instantiation_task 表缺失的列

-- 添加 version 列（实例化版本号）
ALTER TABLE tm_instantiation_task ADD COLUMN IF NOT EXISTS version VARCHAR(50);

-- 添加 load_mode 列（加载模式：OVERWRITE/APPEND）
ALTER TABLE tm_instantiation_task ADD COLUMN IF NOT EXISTS load_mode VARCHAR(20);

-- 添加 jena_model_name 列（Jena TDB2命名图名称）
ALTER TABLE tm_instantiation_task ADD COLUMN IF NOT EXISTS jena_model_name VARCHAR(200);

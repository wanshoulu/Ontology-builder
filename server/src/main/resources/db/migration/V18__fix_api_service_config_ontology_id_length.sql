-- 修正 ontology_id 字段长度，支持更长的实例名（如 inst_xxx_v1.0.0）
ALTER TABLE tm_api_service_config ALTER COLUMN ontology_id TYPE VARCHAR(200);

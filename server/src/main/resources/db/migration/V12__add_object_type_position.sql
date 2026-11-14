-- 为 object_type 表添加位置信息字段
ALTER TABLE tm_object_type ADD COLUMN position_json TEXT;

COMMENT ON COLUMN tm_object_type.position_json IS '节点在图谱中的位置，JSON格式：{"x": 100.5, "y": 200.3}';

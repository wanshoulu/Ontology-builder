-- 为业务规则表添加 SWRL 规则字段
ALTER TABLE tm_business_rule ADD COLUMN IF NOT EXISTS swrl_rule TEXT;

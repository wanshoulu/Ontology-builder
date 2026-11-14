-- V13: 添加业务规则表的 involved_classes 和 involved_data_properties 列

-- 添加 involved_classes 列（SWRL规则涉及的类IRI列表）
ALTER TABLE tm_business_rule ADD COLUMN IF NOT EXISTS involved_classes TEXT;

-- 添加 involved_data_properties 列（SWRL规则涉及的数据属性IRI列表）
ALTER TABLE tm_business_rule ADD COLUMN IF NOT EXISTS involved_data_properties TEXT;

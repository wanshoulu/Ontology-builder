-- OntoMind 智能本体平台 - 初始化数据库脚本
-- V1__init_schema.sql

-- =============================================
-- 用户表
-- =============================================
CREATE TABLE IF NOT EXISTS tm_user (
    id              VARCHAR(36)  PRIMARY KEY,
    username        VARCHAR(50)  NOT NULL UNIQUE,
    password_hash   VARCHAR(200) NOT NULL,
    email           VARCHAR(100),
    role            VARCHAR(30)  NOT NULL DEFAULT 'VIEWER',
    tenant_id       VARCHAR(36),
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_tenant_id ON tm_user(tenant_id);
CREATE INDEX idx_user_username ON tm_user(username);
CREATE INDEX idx_user_email ON tm_user(email);

-- =============================================
-- 数据源表
-- =============================================
CREATE TABLE IF NOT EXISTS tm_ontology (
    id                VARCHAR(36)  PRIMARY KEY,
    tenant_id         VARCHAR(36),
    name              VARCHAR(100) NOT NULL,
    description       TEXT,
    version           VARCHAR(20),
    industry_ontology VARCHAR(20)  DEFAULT 'NONE',
    status            VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    class_count       INTEGER      DEFAULT 0,
    property_count    INTEGER      DEFAULT 0,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ontology_tenant_id ON tm_ontology(tenant_id);
CREATE INDEX idx_ontology_status ON tm_ontology(status);

-- =============================================
-- 对象类型表（本体类）
-- =============================================
CREATE TABLE IF NOT EXISTS tm_object_type (
    id               VARCHAR(36)   PRIMARY KEY,
    ontology_id      VARCHAR(36)   NOT NULL,
    iri              VARCHAR(500)  NOT NULL,
    label            VARCHAR(200)  NOT NULL,
    parent_iri       VARCHAR(500),
    description      TEXT,
    properties_json  TEXT,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_object_type_ontology_id ON tm_object_type(ontology_id);
CREATE INDEX idx_object_type_iri ON tm_object_type(iri);

-- =============================================
-- 关联类型表（本体属性/关系）
-- =============================================
CREATE TABLE IF NOT EXISTS tm_link_type (
    id           VARCHAR(36)   PRIMARY KEY,
    ontology_id  VARCHAR(36)   NOT NULL,
    iri          VARCHAR(500)  NOT NULL,
    label        VARCHAR(200)  NOT NULL,
    source_type  VARCHAR(500),
    target_type  VARCHAR(500),
    description  TEXT,
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_link_type_ontology_id ON tm_link_type(ontology_id);
CREATE INDEX idx_link_type_iri ON tm_link_type(iri);

-- =============================================
-- 业务规则表
-- =============================================
CREATE TABLE IF NOT EXISTS tm_business_rule (
    id              VARCHAR(36)  PRIMARY KEY,
    ontology_id     VARCHAR(36),
    name            VARCHAR(200) NOT NULL,
    type            VARCHAR(20)  NOT NULL,
    priority        VARCHAR(20)  NOT NULL DEFAULT 'MEDIUM',
    condition_json  TEXT,
    action_json     TEXT,
    swrl_rule       TEXT,
    source          VARCHAR(50),
    confidence      DOUBLE PRECISION DEFAULT 1.0,
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    effective_from  TIMESTAMP,
    effective_until TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_rule_ontology_id ON tm_business_rule(ontology_id);
CREATE INDEX idx_rule_status ON tm_business_rule(status);
CREATE INDEX idx_rule_type ON tm_business_rule(type);

-- =============================================
-- 技能表
-- =============================================

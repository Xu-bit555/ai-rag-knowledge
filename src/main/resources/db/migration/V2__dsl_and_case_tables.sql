-- V2__dsl_and_case_tables.sql
-- Phase 1.5: Canonical DSL 持久化
-- Phase 2+: rag_test_case 作为结构化表,raw DSL JSON 入 JSONB

CREATE TABLE IF NOT EXISTS rag_test_case (
    id BIGSERIAL PRIMARY KEY,
    case_id TEXT NOT NULL,
    rag_tag TEXT NOT NULL,
    title TEXT NOT NULL,
    case_type TEXT,
    priority TEXT,
    automation_candidate TEXT NOT NULL,
    risk TEXT,
    status TEXT NOT NULL DEFAULT 'GENERATED',   -- GENERATED / ADOPTED
    raw_dsl JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    adopted_at TIMESTAMPTZ,
    UNIQUE(rag_tag, case_id)
);

CREATE INDEX IF NOT EXISTS idx_rag_test_case_rag_tag_status
    ON rag_test_case(rag_tag, status);

CREATE INDEX IF NOT EXISTS idx_rag_test_case_automation
    ON rag_test_case(automation_candidate);
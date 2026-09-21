-- V3__test_run_tables.sql
-- Phase 2: Test Run / Case Attempt / Failure Diagnosis / Artifact

CREATE TABLE IF NOT EXISTS rag_test_run (
    run_id TEXT PRIMARY KEY,
    rag_tag TEXT NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    target_url TEXT,
    browser TEXT DEFAULT 'chromium',
    status TEXT NOT NULL DEFAULT 'CREATED',   -- CREATED / RUNNING / COMPLETED / CANCELLED
    total_cases INT NOT NULL DEFAULT 0,
    passed_cases INT NOT NULL DEFAULT 0,
    failed_cases INT NOT NULL DEFAULT 0,
    blocked_cases INT NOT NULL DEFAULT 0,
    overall_outcome TEXT,                    -- PASS / FAIL / ERROR
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    UNIQUE(rag_tag, name)
);
CREATE INDEX idx_rag_test_run_status ON rag_test_run(status);
CREATE INDEX idx_rag_test_run_rag_tag ON rag_test_run(rag_tag);

CREATE TABLE IF NOT EXISTS rag_case_attempt (
    attempt_id BIGSERIAL PRIMARY KEY,
    run_id TEXT NOT NULL,
    case_id TEXT NOT NULL,
    attempt_number INT NOT NULL,
    outcome TEXT NOT NULL,                   -- PASSED / FAILED / BLOCKED
    duration_ms INT,
    error_summary TEXT,
    execution_data JSONB,                    -- 完整 steps / assertion / evidence
    evidence_refs TEXT[],                   -- 关联 rag_artifact_meta.artifact_id
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(run_id, case_id, attempt_number)
);
CREATE INDEX idx_rag_case_attempt_run ON rag_case_attempt(run_id);
CREATE INDEX idx_rag_case_attempt_case ON rag_case_attempt(case_id);
CREATE INDEX idx_rag_case_attempt_outcome ON rag_case_attempt(outcome);

CREATE TABLE IF NOT EXISTS rag_failure_diag (
    diagnosis_id BIGSERIAL PRIMARY KEY,
    run_id TEXT NOT NULL,
    case_id TEXT NOT NULL,
    attempt_id BIGINT NOT NULL,
    category TEXT NOT NULL,                  -- TARGET_NOT_FOUND / ASSERTION_FAILED / PAGE_STATE_UNEXPECTED / INPUT_REJECTED / TIMEOUT / NETWORK_ERROR / APPLICATION_ERROR / UNKNOWN
    summary TEXT,
    root_cause TEXT,
    confidence TEXT,                        -- HIGH / MEDIUM / LOW
    suggested_recovery TEXT,
    raw_response JSONB,                      -- LLM 原始响应
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_rag_failure_diag_run ON rag_failure_diag(run_id);
CREATE INDEX idx_rag_failure_diag_attempt ON rag_failure_diag(attempt_id);

CREATE TABLE IF NOT EXISTS rag_artifact_meta (
    artifact_id TEXT PRIMARY KEY,
    run_id TEXT,
    case_id TEXT,
    attempt_number INT,
    step_order INT,
    artifact_type TEXT NOT NULL,              -- SCREENSHOT / DOM / A11Y / CONSOLE / NETWORK / TRACE / OTHER
    file_name TEXT,                           -- 原始文件名(仅 metadata)
    content_type TEXT NOT NULL,
    file_size BIGINT NOT NULL,
    storage_path TEXT NOT NULL,                -- 服务端生成,避免用户控制
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_rag_artifact_run_case ON rag_artifact_meta(run_id, case_id);
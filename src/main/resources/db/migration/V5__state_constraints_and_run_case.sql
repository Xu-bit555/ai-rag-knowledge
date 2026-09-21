-- V5__state_constraints_and_run_case.sql
-- Phase 2: D5 (状态字段 CHECK 约束) + D6 (rag_run_case 关联表)

-- ─────────────────────────────────────────────────────────────
-- D5: 状态字段 CHECK 约束
-- 修复: 状态字段全裸 TEXT, 脏数据可以写 status='ADOPTEDD' 也能成功
-- ─────────────────────────────────────────────────────────────

-- 先做"现状扫描"用注释保留，便于 DBA 排查越界值
-- 期望迁移前状态值都在下列枚举内
ALTER TABLE rag_test_case
    ADD CONSTRAINT ck_rag_test_case_status
    CHECK (status IN ('GENERATED','ADOPTED'));

ALTER TABLE rag_test_run
    ADD CONSTRAINT ck_rag_test_run_status
    CHECK (status IN ('CREATED','RUNNING','COMPLETED','CANCELLED','FAILED'));

ALTER TABLE rag_case_attempt
    ADD CONSTRAINT ck_rag_case_attempt_outcome
    CHECK (outcome IN ('PASSED','FAILED','BLOCKED'));

-- rag_failure_diag.confidence 是 TEXT (HIGH/MEDIUM/LOW), 不加数值 CHECK

-- ─────────────────────────────────────────────────────────────
-- D6: rag_run_case 关联表
-- 修复: TestRunRepositoryPort.findCaseIdsByRunId 注释撒谎
--   (说"来自 rag_run_case 关联表", 实际 SELECT DISTINCT case_id FROM rag_case_attempt)
--   现在补上真表 + CreateTestRunUseCase 同步写
-- ─────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS rag_run_case (
    id          BIGSERIAL PRIMARY KEY,
    run_id      TEXT        NOT NULL,
    case_id     TEXT        NOT NULL,
    position    INTEGER     NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, case_id)
);
CREATE INDEX IF NOT EXISTS idx_rag_run_case_run  ON rag_run_case(run_id);
CREATE INDEX IF NOT EXISTS idx_rag_run_case_case ON rag_run_case(case_id);

COMMENT ON TABLE rag_run_case IS 'Phase 2 D6: run ↔ case 关联表 (替代 rag_test_run.case_ids JSONB 数组)';

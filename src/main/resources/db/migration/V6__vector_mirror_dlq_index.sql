-- V6__vector_mirror_dlq_index.sql
-- Phase 3: 向量镜像 idempotency check 加速 + 注释

-- 1. spring_ai_vectors 加 caseId 表达式 btree 部分索引
--    Consumer 每次处理前查 metadata->>'caseId', 无索引则 seq scan.
--    只索引 type=test_case 行, 因为其他类型 (knowledge chunks) 没有 caseId 字段.
CREATE INDEX IF NOT EXISTS idx_spring_ai_vectors_case_id
    ON spring_ai_vectors ((metadata->>'caseId'))
    WHERE metadata->>'type' = 'test_case';

-- 2. 注释: rag:vector:dlq 是 Redis Stream, 不需要 DB 索引

-- ─────────────────────────────────────────────────────────────
-- 验证 SQL (应用启动后手动跑)
-- ─────────────────────────────────────────────────────────────
-- EXPLAIN ANALYZE
-- SELECT COUNT(*) FROM spring_ai_vectors
-- WHERE metadata->>'caseId' = 'TC_TEST_xxx';
-- 期望: Index Scan using idx_spring_ai_vectors_case_id
--
-- SELECT indexname FROM pg_indexes
-- WHERE tablename = 'spring_ai_vectors'
-- ORDER BY indexname;
-- 期望: 至少 7 个 (V4 的 4 个 + V6 的 1 个 + V4 的 metadata_gin + knowledge_type 部分索引)

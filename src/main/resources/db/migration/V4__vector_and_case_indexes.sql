-- V4__vector_and_case_indexes.sql
-- Phase 3 P0 修复：性能索引优化
--
-- 内容：
--   1. spring_ai_vectors.metadata GIN 索引 (P0-11 + D4: SafeFilterBuilder 与 metadata 谓词查询)
--   2. spring_ai_vectors 按 (knowledge, type) 组合的部分索引 (D4 性能)
--   3. pg_trgm 扩展 + rag_test_case 标题/case_id trigram 索引 (D5: search_test_cases 全文搜索)
--
-- 幂等：所有 CREATE 都用 IF NOT EXISTS，可重复执行。
-- 索引大小估算：metadata jsonb 约 200-500B × N rows；51 chunks 量级可忽略。

-- ─────────────────────────────────────────────────────────────
-- 1. spring_ai_vectors.metadata GIN 索引
--
-- 注意: Spring AI 自动建的 spring_ai_vectors.metadata 列是 json (非 jsonb),
--   jsonb_path_ops 只支持 jsonb. 索引表达式里显式 cast 一次,
--   存储翻倍但语义等价, 不影响其它 4 个索引.
-- ─────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_spring_ai_vectors_metadata_gin
    ON spring_ai_vectors USING GIN ((metadata::jsonb) jsonb_path_ops);

-- knowledge + type 组合的部分索引（最常见的双字段过滤）
CREATE INDEX IF NOT EXISTS idx_spring_ai_vectors_knowledge_type
    ON spring_ai_vectors ((metadata->>'knowledge'))
    WHERE metadata->>'type' = 'knowledge';

-- knowledge + type='test_case' 的用例检索索引（P0-1 修复后用于 Rerank 召回）
CREATE INDEX IF NOT EXISTS idx_spring_ai_vectors_knowledge_test_case
    ON spring_ai_vectors ((metadata->>'knowledge'))
    WHERE metadata->>'type' = 'test_case';

-- ─────────────────────────────────────────────────────────────
-- 2. rag_test_case 全文搜索索引 (pg_trgm)
-- ─────────────────────────────────────────────────────────────
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 标题 trigram 索引：加速 title ILIKE '%query%' 与相似度排序
CREATE INDEX IF NOT EXISTS idx_rag_test_case_title_trgm
    ON rag_test_case USING GIN (title gin_trgm_ops);

-- case_id trigram 索引：用于模糊查询 (e.g. "TC_AI_*")
CREATE INDEX IF NOT EXISTS idx_rag_test_case_case_id_trgm
    ON rag_test_case USING GIN (case_id gin_trgm_ops);

-- ─────────────────────────────────────────────────────────────
-- 验证 SQL（应用启动后手动跑）
-- ─────────────────────────────────────────────────────────────
-- SELECT indexname FROM pg_indexes
-- WHERE tablename IN ('rag_test_case','spring_ai_vectors')
-- ORDER BY tablename, indexname;
-- 期望: 至少 7 个索引 (含 V2/V3 已建的 + 上述 5 个)
--
-- EXPLAIN ANALYZE
-- SELECT id FROM spring_ai_vectors
-- WHERE metadata @> '{"knowledge":"terabox-ai","type":"test_case"}' LIMIT 5;
-- 期望: Bitmap Index Scan on idx_spring_ai_vectors_metadata_gin

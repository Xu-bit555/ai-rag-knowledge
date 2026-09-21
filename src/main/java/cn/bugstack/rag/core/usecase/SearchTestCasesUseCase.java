package cn.bugstack.rag.core.usecase;

import cn.bugstack.rag.core.domain.dsl.v1.TestCaseEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SearchTestCasesUseCase - 检索历史已采纳用例
 *
 * Phase 2 D3: 改用 SQL ILIKE + 复用 V4 已建的 pg_trgm GIN 索引
 *   (idx_rag_test_case_title_trgm / idx_rag_test_case_case_id_trgm)
 *   替代之前 in-memory LIKE 全表扫描.
 *
 * 当 query 为空时, 退化为按 updated_at DESC 取最近 k 条 ADOPTED case.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchTestCasesUseCase {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * 检索
     *
     * @param ragTag 知识库标签
     * @param query 查询关键词
     * @param topK 返回条数
     * @return List of {caseId, title, automationCandidate, steps, assertions, expectedOutcome}
     */
    public List<Map<String, Object>> execute(String ragTag, String query, Integer topK) {
        int k = (topK != null && topK > 0) ? topK : 10;
        log.info("SearchTestCasesUseCase: ragTag={}, query.length={}, topK={}",
                ragTag, query == null ? 0 : query.length(), k);

        String sql;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("ragTag", ragTag)
                .addValue("limit", k);

        if (query == null || query.isBlank()) {
            // 无关键词: 按 updated_at DESC 取最近 k 条 ADOPTED
            sql = """
                SELECT raw_dsl FROM rag_test_case
                WHERE rag_tag = :ragTag AND status = 'ADOPTED'
                ORDER BY created_at DESC
                LIMIT :limit
                """;
        } else {
            // D3: 用 ILIKE + pg_trgm GIN 索引 (V4 已建)
            // % 和 _ 需要转义, 避免用户输入被当 SQL 通配符
            String safe = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            sql = """
                SELECT raw_dsl FROM rag_test_case
                WHERE rag_tag = :ragTag
                  AND status = 'ADOPTED'
                  AND (title ILIKE :kw ESCAPE '\\' OR case_id ILIKE :kw ESCAPE '\\')
                ORDER BY created_at DESC
                LIMIT :limit
                """;
            p.addValue("kw", "%" + safe + "%");
        }

        List<TestCaseEntity> matched = jdbc.query(sql, p, (rs, rowNum) -> {
            try {
                return objectMapper.readValue(rs.getString(1), TestCaseEntity.class);
            } catch (JsonProcessingException e) {
                log.error("Failed to deserialize DSL for ragTag={}", ragTag, e);
                return null;
            }
        });

        // 过滤反序列化失败
        List<Map<String, Object>> result = new ArrayList<>();
        for (TestCaseEntity tc : matched) {
            if (tc != null) result.add(toMap(tc));
        }
        log.info("SearchTestCasesUseCase: matched={}", result.size());
        return result;
    }

    private Map<String, Object> toMap(TestCaseEntity tc) {
        Map<String, Object> m = new HashMap<>();
        m.put("caseId", tc.getCaseId());
        m.put("title", tc.getTitle());
        m.put("automationCandidate",
                tc.getAutomationCandidate() != null ? tc.getAutomationCandidate().name() : null);
        m.put("priority", tc.getPriority() != null ? tc.getPriority().name() : null);
        m.put("steps", tc.getSteps());
        m.put("assertions", tc.getAssertions());
        m.put("expectedOutcome", tc.getExpectedOutcome());
        return m;
    }
}
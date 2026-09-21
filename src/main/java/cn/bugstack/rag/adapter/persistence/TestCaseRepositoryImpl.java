package cn.bugstack.rag.adapter.persistence;

import cn.bugstack.rag.core.domain.dsl.v1.CanonicalTestCase;
import cn.bugstack.rag.core.domain.dsl.v1.TestCaseEntity;
import cn.bugstack.rag.core.port.TestCaseRepositoryPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * TestCase 仓储实现 - JdbcTemplate + PostgreSQL JSONB
 *
 * P0-1 修复: adopt() 同事务内镜像写入 spring_ai_vectors(type=test_case, adoptionStatus=ADOPTED),
 *   解决 RerankServiceImpl 历史召回永久为空的双源真相分裂 bug.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class TestCaseRepositoryImpl implements TestCaseRepositoryPort {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final VectorStore vectorStore;

    /**
     * adopt 时分配稳定 caseId: 原 caseId 可能来自 LLM,加 UUID 后缀确保全局唯一
     * 格式: TC_LLM_name_<uuid8>
     *
     * P0-1: 同事务内 INSERT rag_test_case + vectorStore.accept() 镜像到 spring_ai_vectors
     */
    @Override
    @Transactional
    public String adopt(String ragTag, TestCaseEntity caseEntity) {
        String baseId = caseEntity.getCaseId() != null ? caseEntity.getCaseId() : "TC_AUTO";
        String stableId = baseId + "_" + UUID.randomUUID().toString().substring(0, 8);

        String title = caseEntity.getTitle() != null ? caseEntity.getTitle() : "";
        String caseType = caseEntity.getCaseType() != null ? caseEntity.getCaseType().name() : null;
        String priority = caseEntity.getPriority() != null ? caseEntity.getPriority().name() : null;
        String automation = caseEntity.getAutomationCandidate() != null
                ? caseEntity.getAutomationCandidate().name() : "UNSUPPORTED";
        String risk = caseEntity.getRisk() != null ? caseEntity.getRisk().name() : null;

        String rawDsl;
        try {
            rawDsl = objectMapper.writeValueAsString(caseEntity);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize DSL to JSON", e);
        }

        // 1. 写结构表 rag_test_case
        jdbcTemplate.update(
                "INSERT INTO rag_test_case " +
                        "(case_id, rag_tag, title, case_type, priority, automation_candidate, risk, status, raw_dsl, adopted_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, 'ADOPTED', ?::jsonb, ?)",
                stableId, ragTag, title, caseType, priority, automation, risk, rawDsl,
                Timestamp.from(Instant.now())
        );

        // 2. P0-1: 同事务内镜像到 spring_ai_vectors(type=test_case, adoptionStatus=ADOPTED)
        //    供 RerankServiceImpl.rerankTestCases 历史召回使用
        Map<String, Object> vectorMeta = new HashMap<>();
        vectorMeta.put("knowledge", ragTag);
        vectorMeta.put("type", "test_case");
        vectorMeta.put("adoptionStatus", "ADOPTED");
        vectorMeta.put("caseId", stableId);
        vectorMeta.put("sourceDoc", caseEntity.getCaseId());

        String embeddingText = title + "\n"
                + (caseEntity.getExpectedOutcome() != null ? caseEntity.getExpectedOutcome() : "");
        Document doc = new Document(stableId, embeddingText, vectorMeta);
        try {
            vectorStore.accept(List.of(doc));
        } catch (Exception e) {
            // 镜像失败 → 抛异常 → @Transactional 整体回滚 (P0-8 已确保)
            log.error("Vector mirror failed for case {}, ragTag={}, rolling back",
                    stableId, ragTag, e);
            throw new IllegalStateException(
                    "Failed to mirror case to vector store (transactional rollback): "
                            + e.getMessage(), e);
        }

        log.info("Adopted test case (with vector mirror): ragTag={}, stableId={}", ragTag, stableId);
        return stableId;
    }

    @Override
    public List<String> adoptAll(String ragTag, List<TestCaseEntity> cases) {
        return cases.stream()
                .map(c -> adopt(ragTag, c))
                .collect(Collectors.toList());
    }

    @Override
    public Optional<TestCaseEntity> findByCaseId(String ragTag, String caseId) {
        List<TestCaseEntity> results = jdbcTemplate.query(
                "SELECT raw_dsl FROM rag_test_case WHERE rag_tag = ? AND case_id = ?",
                (rs, rowNum) -> {
                    try {
                        return objectMapper.readValue(rs.getString(1), TestCaseEntity.class);
                    } catch (JsonProcessingException e) {
                        log.error("Failed to deserialize DSL", e);
                        return null;
                    }
                },
                ragTag, caseId
        );
        return results.isEmpty() ? Optional.empty() : Optional.ofNullable(results.get(0));
    }

    @Override
    public List<TestCaseEntity> findAdoptedByRagTag(String ragTag) {
        return jdbcTemplate.query(
                "SELECT raw_dsl FROM rag_test_case WHERE rag_tag = ? AND status = 'ADOPTED'",
                (rs, rowNum) -> {
                    try {
                        return objectMapper.readValue(rs.getString(1), TestCaseEntity.class);
                    } catch (JsonProcessingException e) {
                        log.error("Failed to deserialize DSL", e);
                        return null;
                    }
                },
                ragTag
        );
    }

    @Override
    @Transactional
    public void saveRawDsl(CanonicalTestCase dsl) {
        // MVP 暂不实现 audit log
        log.debug("saveRawDsl: summary.totalCases={}",
                dsl.getSummary() != null ? dsl.getSummary().getTotalCases() : null);
    }
}
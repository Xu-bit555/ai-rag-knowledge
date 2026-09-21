package cn.bugstack.rag.adapter.persistence;

import cn.bugstack.rag.core.domain.dsl.v1.TestCaseEntity;
import cn.bugstack.rag.core.port.TestCaseRepositoryPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * TestCase 仓储实现 - JdbcTemplate + PostgreSQL JSONB
 *
 * P0-1 修复: adopt() 同事务内镜像写入 spring_ai_vectors(type=test_case, adoptionStatus=ADOPTED),
 *   解决 RerankServiceImpl 历史召回永久为空的双源真相分裂 bug.
 *
 * Phase 2 修复:
 *   D1 - 删除 saveRawDsl() no-op 方法
 *   D7 - adoptAll 用 batchUpdate 替代循环单条 INSERT (结构表)
 *   L7 - UUID 改 36 字符全长 (原 substring(0,8) 仅 ~20 位熵, 千万级 caseId 有碰撞风险)
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class TestCaseRepositoryImpl implements TestCaseRepositoryPort {

    private static final String INSERT_SQL =
            "INSERT INTO rag_test_case " +
                    "(case_id, rag_tag, title, case_type, priority, automation_candidate, risk, status, raw_dsl, adopted_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, 'ADOPTED', ?::jsonb, ?)";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final VectorStore vectorStore;

    /**
     * 单条 adopt - P0-1 双写保持事务性 (结构表 + 向量表)
     *
     * P0-1: 同事务内 INSERT rag_test_case + vectorStore.accept() 镜像到 spring_ai_vectors
     * L7: UUID 36 字符全长 (P0 阶段已改为 UUID.randomUUID() 但还在 substring(0,8))
     */
    @Override
    @Transactional
    public String adopt(String ragTag, TestCaseEntity caseEntity) {
        String baseId = caseEntity.getCaseId() != null ? caseEntity.getCaseId() : "TC_AUTO";
        // L7: UUID 36 字符全长, 替代 substring(0, 8)
        String stableId = baseId + "_" + UUID.randomUUID().toString();

        // 写结构表
        jdbcTemplate.update(INSERT_SQL, ps -> {
            ps.setString(1, stableId);
            ps.setString(2, ragTag);
            ps.setString(3, caseEntity.getTitle() != null ? caseEntity.getTitle() : "");
            ps.setString(4, caseEntity.getCaseType() != null ? caseEntity.getCaseType().name() : null);
            ps.setString(5, caseEntity.getPriority() != null ? caseEntity.getPriority().name() : null);
            ps.setString(6, caseEntity.getAutomationCandidate() != null
                    ? caseEntity.getAutomationCandidate().name() : "UNSUPPORTED");
            ps.setString(7, caseEntity.getRisk() != null ? caseEntity.getRisk().name() : null);
            try {
                ps.setString(8, objectMapper.writeValueAsString(caseEntity));
            } catch (JsonProcessingException e) {
                throw new SQLException("Failed to serialize DSL to JSON", e);
            }
            ps.setTimestamp(9, Timestamp.from(Instant.now()));
        });

        // 镜像到 spring_ai_vectors (事务内, 失败整体回滚)
        mirrorToVectorStore(ragTag, stableId, caseEntity);

        log.info("Adopted test case (with vector mirror): ragTag={}, stableId={}", ragTag, stableId);
        return stableId;
    }

    /**
     * 批量 adopt - D7: 用 batchUpdate 替代循环单条 INSERT
     *
     * 注意: 结构表 batch 一次提交, 但向量表仍按 case 循环 (PgVectorStore 不接受 batch),
     *   任一 vectorStore.accept 失败 → @Transactional 整体回滚 (P0-8 已确保)
     */
    @Override
    @Transactional
    public List<String> adoptAll(String ragTag, List<TestCaseEntity> cases) {
        if (cases == null || cases.isEmpty()) {
            return List.of();
        }

        // 1. 分配所有 stableId (一次性, 不入库)
        List<String> stableIds = new ArrayList<>(cases.size());
        List<String> rawDsls = new ArrayList<>(cases.size());
        for (TestCaseEntity tc : cases) {
            String baseId = tc.getCaseId() != null ? tc.getCaseId() : "TC_AUTO";
            stableIds.add(baseId + "_" + UUID.randomUUID().toString());
            try {
                rawDsls.add(objectMapper.writeValueAsString(tc));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to serialize DSL to JSON", e);
            }
        }

        // 2. 结构表 batch INSERT 一次
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                TestCaseEntity tc = cases.get(i);
                ps.setString(1, stableIds.get(i));
                ps.setString(2, ragTag);
                ps.setString(3, tc.getTitle() != null ? tc.getTitle() : "");
                ps.setString(4, tc.getCaseType() != null ? tc.getCaseType().name() : null);
                ps.setString(5, tc.getPriority() != null ? tc.getPriority().name() : null);
                ps.setString(6, tc.getAutomationCandidate() != null
                        ? tc.getAutomationCandidate().name() : "UNSUPPORTED");
                ps.setString(7, tc.getRisk() != null ? tc.getRisk().name() : null);
                ps.setString(8, rawDsls.get(i));
                ps.setTimestamp(9, Timestamp.from(Instant.now()));
            }

            @Override
            public int getBatchSize() {
                return cases.size();
            }
        });

        // 3. 向量表镜像 (仍按 case 循环, 任何失败 → 整体回滚)
        for (int i = 0; i < cases.size(); i++) {
            mirrorToVectorStore(ragTag, stableIds.get(i), cases.get(i));
        }

        log.info("Batch adopted {} cases for ragTag={}", cases.size(), ragTag);
        return stableIds;
    }

    /**
     * 镜像单条 case 到 spring_ai_vectors
     * spring_ai_vectors.id 是 uuid 类型, 必须用 UUID.randomUUID() 作为 Document.id.
     * 原 caseId (字符串如 "TC_AI_OK_ccce0f94") 放 metadata.caseId, 用于关联回 rag_test_case.
     */
    private void mirrorToVectorStore(String ragTag, String stableId, TestCaseEntity caseEntity) {
        Map<String, Object> vectorMeta = new HashMap<>();
        vectorMeta.put("knowledge", ragTag);
        vectorMeta.put("type", "test_case");
        vectorMeta.put("adoptionStatus", "ADOPTED");
        vectorMeta.put("caseId", stableId);
        vectorMeta.put("sourceDoc", caseEntity.getCaseId());

        String title = caseEntity.getTitle() != null ? caseEntity.getTitle() : "";
        String embeddingText = title + "\n"
                + (caseEntity.getExpectedOutcome() != null ? caseEntity.getExpectedOutcome() : "");
        Document doc = new Document(UUID.randomUUID().toString(), embeddingText, vectorMeta);
        try {
            vectorStore.accept(List.of(doc));
        } catch (Exception e) {
            log.error("Vector mirror failed for case {}, ragTag={}, rolling back",
                    stableId, ragTag, e);
            throw new IllegalStateException(
                    "Failed to mirror case to vector store (transactional rollback): "
                            + e.getMessage(), e);
        }
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
}
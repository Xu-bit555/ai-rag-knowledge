package cn.bugstack.rag.adapter.persistence;

import cn.bugstack.rag.core.domain.dsl.v1.TestCaseEntity;
import cn.bugstack.rag.core.port.TestCaseRepositoryPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * TestCase 仓储实现 - JdbcTemplate + PostgreSQL JSONB
 *
 * Phase 1/2: 结构表 + 向量表同事务双写 (P0-1 双源真相分裂修复)
 *
 * Phase 3: 拆异步双写. adopt() 内部 @Transactional(REQUIRES_NEW) 控制结构表 INSERT 独立事务.
 *   向量镜像改 fire-and-forget 派发到 rag:vector:mirror stream, 由 VectorMirrorConsumer 异步消费.
 *   这样 embedding API 抖动不再阻断 adopt 业务路径 (P0-12 fail-fast 与 P0-1 强一致的矛盾解).
 *
 * 修复历史:
 *   P0-1 - 双源真相分裂 (原同事务双写, Phase 1)
 *   D1 - 删除 saveRawDsl() no-op 方法 (Phase 2)
 *   D7 - adoptAll 用 batchUpdate 替代循环单条 INSERT (Phase 2)
 *   L7 - UUID 改 36 字符全长 (Phase 2)
 *   Phase 3 - 拆异步双写: 结构表独立事务 + 向量镜像 fire-and-forget
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
    private final VectorMirrorDispatcher vectorMirrorDispatcher;

    /**
     * 单条 adopt - Phase 3 拆异步双写
     *
     * 事务边界: @Transactional(REQUIRES_NEW) - 强制开新事务, 不受外层事务影响.
     *   - 结构表 INSERT 在事务内, commit 后立即返回 stableId
     *   - 向量镜像调用 dispatcher.dispatch() (fire-and-forget, 不抛异常)
     *   - 即使 dispatcher 抛 RuntimeException, 也只 log warn 不影响事务提交
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String adopt(String ragTag, TestCaseEntity caseEntity) {
        String baseId = caseEntity.getCaseId() != null ? caseEntity.getCaseId() : "TC_AUTO";
        String stableId = baseId + "_" + UUID.randomUUID().toString();

        // 1. 结构表 INSERT (单条事务)
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

        // 2. Phase 3: 异步派发向量镜像 (fire-and-forget, 不抛异常)
        try {
            vectorMirrorDispatcher.dispatch(ragTag, stableId, caseEntity);
        } catch (Exception e) {
            log.warn("Vector mirror dispatch threw (swallow): ragTag={}, stableId={}",
                    ragTag, stableId, e);
        }

        log.info("Adopted test case (fire-and-forget vector): ragTag={}, stableId={}", ragTag, stableId);
        return stableId;
    }

    /**
     * 批量 adopt - Phase 3 拆异步双写
     *
     * 结构表 batch INSERT 一次提交; 向量镜像 best-effort 循环派发, 失败仅 log.
     * P0-1 双源分裂语义保留: 正常路径下结构表 + 向量表最终一致 (允许 < 1s 异步延迟).
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<String> adoptAll(String ragTag, List<TestCaseEntity> cases) {
        if (cases == null || cases.isEmpty()) {
            return List.of();
        }

        // 1. 分配所有 stableId
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

        // 3. 向量镜像派发 (best-effort, 失败仅 log)
        for (int i = 0; i < cases.size(); i++) {
            try {
                vectorMirrorDispatcher.dispatch(ragTag, stableIds.get(i), cases.get(i));
            } catch (Exception e) {
                log.warn("Vector dispatch failed (swallow): caseId={}", stableIds.get(i), e);
            }
        }

        log.info("Batch adopted {} cases for ragTag={} (fire-and-forget vector)",
                cases.size(), ragTag);
        return stableIds;
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

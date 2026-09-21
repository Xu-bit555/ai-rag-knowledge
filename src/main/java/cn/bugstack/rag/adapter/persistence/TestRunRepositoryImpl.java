package cn.bugstack.rag.adapter.persistence;

import cn.bugstack.rag.core.domain.execution.TestRun;
import cn.bugstack.rag.core.domain.execution.TestRunStatus;
import cn.bugstack.rag.core.port.TestRunRepositoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * TestRun 仓储实现 - JdbcTemplate + PostgreSQL
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class TestRunRepositoryImpl implements TestRunRepositoryPort {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public TestRun create(TestRun run) {
        if (run.getRunId() == null || run.getRunId().isBlank()) {
            run.setRunId("run-" + UUID.randomUUID().toString().substring(0, 12));
        }
        jdbcTemplate.update(
                "INSERT INTO rag_test_run " +
                        "(run_id, rag_tag, name, description, target_url, browser, status, total_cases, " +
                        " passed_cases, failed_cases, blocked_cases, overall_outcome, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                run.getRunId(), run.getRagTag(), run.getName(), run.getDescription(),
                run.getTargetUrl(), run.getBrowser() != null ? run.getBrowser() : "chromium",
                run.getStatus() != null ? run.getStatus().name() : TestRunStatus.CREATED.name(),
                run.getTotalCases(), 0, 0, 0, null,
                Timestamp.from(run.getCreatedAt() != null ? run.getCreatedAt() : Instant.now())
        );
        log.info("Created TestRun: runId={}, name={}, totalCases={}",
                run.getRunId(), run.getName(), run.getTotalCases());
        return run;
    }

    @Override
    public Optional<TestRun> findByRunId(String runId) {
        List<TestRun> results = jdbcTemplate.query(
                "SELECT * FROM rag_test_run WHERE run_id = ?",
                (rs, rowNum) -> mapRun(rs),
                runId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public TestRun updateStatus(String runId, TestRunStatus status, int passed, int failed, int blocked,
                                String overallOutcome) {
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp startedAt = status == TestRunStatus.RUNNING || status == TestRunStatus.COMPLETED
                ? now : null;
        Timestamp completedAt = status == TestRunStatus.COMPLETED ? now : null;
        jdbcTemplate.update(
                "UPDATE rag_test_run SET status = ?, passed_cases = ?, failed_cases = ?, " +
                        "blocked_cases = ?, overall_outcome = ?, started_at = COALESCE(started_at, ?), " +
                        "completed_at = COALESCE(completed_at, ?) WHERE run_id = ?",
                status.name(), passed, failed, blocked, overallOutcome,
                startedAt, completedAt, runId);
        return findByRunId(runId).orElseThrow();
    }

    @Override
    public int[] countLatestOutcomes(String runId) {
        // 最新 attempt = MAX(attempt_number) per (case_id)
        // 然后统计每个 case 的最新 outcome
        Integer passed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (" +
                        "  SELECT DISTINCT ON (case_id) case_id, outcome " +
                        "  FROM rag_case_attempt WHERE run_id = ? " +
                        "  ORDER BY case_id, attempt_number DESC) latest " +
                        "WHERE latest.outcome = 'PASSED'",
                Integer.class, runId);
        Integer failed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (" +
                        "  SELECT DISTINCT ON (case_id) case_id, outcome " +
                        "  FROM rag_case_attempt WHERE run_id = ? " +
                        "  ORDER BY case_id, attempt_number DESC) latest " +
                        "WHERE latest.outcome = 'FAILED'",
                Integer.class, runId);
        Integer blocked = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (" +
                        "  SELECT DISTINCT ON (case_id) case_id, outcome " +
                        "  FROM rag_case_attempt WHERE run_id = ? " +
                        "  ORDER BY case_id, attempt_number DESC) latest " +
                        "WHERE latest.outcome = 'BLOCKED'",
                Integer.class, runId);
        return new int[]{passed != null ? passed : 0, failed != null ? failed : 0,
                blocked != null ? blocked : 0};
    }

    @Override
    public List<TestRun> findByRagTag(String ragTag) {
        return jdbcTemplate.query(
                "SELECT * FROM rag_test_run WHERE rag_tag = ? ORDER BY created_at DESC",
                (rs, rowNum) -> mapRun(rs), ragTag);
    }

    @Override
    public List<String> findCaseIdsByRunId(String runId) {
        List<String> results = new ArrayList<>();
        List<TestRun> runs = jdbcTemplate.query(
                "SELECT * FROM rag_test_run WHERE run_id = ?",
                (rs, rowNum) -> mapRun(rs), runId);
        if (runs.isEmpty()) return results;

        // 通过 rag_test_run.total_cases 知道数,直接列出 rag_case_attempt 涉及的 case
        // 简化用 DISTINCT 从 attempts 取(因为目前没有单独的 run_case 关联表)
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT case_id FROM rag_case_attempt WHERE run_id = ? ORDER BY case_id",
                String.class, runId);
    }

    private TestRun mapRun(ResultSet rs) throws SQLException {
        return TestRun.builder()
                .runId(rs.getString("run_id"))
                .ragTag(rs.getString("rag_tag"))
                .name(rs.getString("name"))
                .description(rs.getString("description"))
                .targetUrl(rs.getString("target_url"))
                .browser(rs.getString("browser"))
                .status(TestRunStatus.valueOf(rs.getString("status")))
                .totalCases(rs.getInt("total_cases"))
                .passedCases(rs.getInt("passed_cases"))
                .failedCases(rs.getInt("failed_cases"))
                .blockedCases(rs.getInt("blocked_cases"))
                .overallOutcome(rs.getString("overall_outcome"))
                .createdAt(rs.getTimestamp("created_at") != null
                        ? rs.getTimestamp("created_at").toInstant() : null)
                .startedAt(rs.getTimestamp("started_at") != null
                        ? rs.getTimestamp("started_at").toInstant() : null)
                .completedAt(rs.getTimestamp("completed_at") != null
                        ? rs.getTimestamp("completed_at").toInstant() : null)
                .build();
    }
}
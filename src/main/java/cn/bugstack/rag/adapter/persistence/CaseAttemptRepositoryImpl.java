package cn.bugstack.rag.adapter.persistence;

import cn.bugstack.rag.core.domain.execution.AttemptOutcome;
import cn.bugstack.rag.core.domain.execution.CaseAttempt;
import cn.bugstack.rag.core.port.CaseAttemptRepositoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class CaseAttemptRepositoryImpl implements CaseAttemptRepositoryPort {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 计算下一个 attemptNumber 并写入
     *
     * 策略: SELECT MAX(attempt_number) + 1,UNIQUE 约束防止并发竞争
     * 若并发写入同 attemptNumber,DB 抛 DataIntegrityViolation → 调用方重试
     */
    @Override
    @Transactional
    public CaseAttempt insertNextAttempt(String runId, String caseId,
                                         AttemptOutcome outcome,
                                         Integer durationMs,
                                         String errorSummary,
                                         String executionDataJson,
                                         List<String> evidenceRefs) {

        Integer nextNum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(attempt_number), 0) + 1 FROM rag_case_attempt " +
                        "WHERE run_id = ? AND case_id = ?",
                Integer.class, runId, caseId);
        if (nextNum == null || nextNum < 1) nextNum = 1;

        String evidenceArraySql = toPgTextArray(evidenceRefs);

        try {
            jdbcTemplate.update(
                    "INSERT INTO rag_case_attempt " +
                            "(run_id, case_id, attempt_number, outcome, duration_ms, " +
                            " error_summary, execution_data, evidence_refs) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                    runId, caseId, nextNum, outcome.name(), durationMs,
                    errorSummary, executionDataJson, evidenceArraySql);
        } catch (DataIntegrityViolationException e) {
            // 并发: 同样 attemptNumber 已存在,简单重试一次
            log.warn("Concurrent insert detected for run={}, case={}, attempt={}, retrying",
                    runId, caseId, nextNum);
            nextNum = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(attempt_number), 0) + 1 FROM rag_case_attempt " +
                            "WHERE run_id = ? AND case_id = ?",
                    Integer.class, runId, caseId);
            jdbcTemplate.update(
                    "INSERT INTO rag_case_attempt " +
                            "(run_id, case_id, attempt_number, outcome, duration_ms, " +
                            " error_summary, execution_data, evidence_refs) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                    runId, caseId, nextNum, outcome.name(), durationMs,
                    errorSummary, executionDataJson, evidenceArraySql);
        }

        log.info("Inserted CaseAttempt: run={}, case={}, attempt={}, outcome={}",
                runId, caseId, nextNum, outcome);

        return findLatestByRunIdAndCaseId(runId, caseId).orElseThrow();
    }

    @Override
    public List<CaseAttempt> findByRunId(String runId) {
        return jdbcTemplate.query(
                "SELECT * FROM rag_case_attempt WHERE run_id = ? ORDER BY case_id, attempt_number",
                (rs, rowNum) -> mapAttempt(rs), runId);
    }

    @Override
    public List<CaseAttempt> findByRunIdAndCaseId(String runId, String caseId) {
        return jdbcTemplate.query(
                "SELECT * FROM rag_case_attempt WHERE run_id = ? AND case_id = ? " +
                        "ORDER BY attempt_number",
                (rs, rowNum) -> mapAttempt(rs), runId, caseId);
    }

    @Override
    public Optional<CaseAttempt> findLatestByRunIdAndCaseId(String runId, String caseId) {
        List<CaseAttempt> results = jdbcTemplate.query(
                "SELECT * FROM rag_case_attempt WHERE run_id = ? AND case_id = ? " +
                        "ORDER BY attempt_number DESC LIMIT 1",
                (rs, rowNum) -> mapAttempt(rs), runId, caseId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public Optional<CaseAttempt> findByAttemptId(long attemptId) {
        List<CaseAttempt> results = jdbcTemplate.query(
                "SELECT * FROM rag_case_attempt WHERE attempt_id = ?",
                (rs, rowNum) -> mapAttempt(rs), attemptId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    private CaseAttempt mapAttempt(ResultSet rs) throws SQLException {
        Array sqlArray = rs.getArray("evidence_refs");
        List<String> refs = new ArrayList<>();
        if (sqlArray != null) {
            String[] arr = (String[]) sqlArray.getArray();
            for (String s : arr) refs.add(s);
        }
        return CaseAttempt.builder()
                .attemptId(rs.getLong("attempt_id"))
                .runId(rs.getString("run_id"))
                .caseId(rs.getString("case_id"))
                .attemptNumber(rs.getInt("attempt_number"))
                .outcome(AttemptOutcome.valueOf(rs.getString("outcome")))
                .durationMs(rs.getInt("duration_ms") == 0 ? null : rs.getInt("duration_ms"))
                .errorSummary(rs.getString("error_summary"))
                .executionData(rs.getString("execution_data"))
                .evidenceRefs(refs)
                .createdAt(rs.getTimestamp("created_at") != null
                        ? rs.getTimestamp("created_at").toInstant() : null)
                .build();
    }

    /**
     * 把 List<String> 转成 PostgreSQL TEXT[] 字面量: {art-1,art-2}
     */
    private String toPgTextArray(List<String> refs) {
        if (refs == null || refs.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < refs.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(refs.get(i).replace("\"", "\\\"")).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }
}
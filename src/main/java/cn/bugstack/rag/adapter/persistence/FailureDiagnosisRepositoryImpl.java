package cn.bugstack.rag.adapter.persistence;

import cn.bugstack.rag.core.domain.execution.DiagnosisCategory;
import cn.bugstack.rag.core.domain.execution.DiagnosisConfidence;
import cn.bugstack.rag.core.domain.execution.FailureDiagnosis;
import cn.bugstack.rag.core.port.FailureDiagnosisRepositoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class FailureDiagnosisRepositoryImpl implements FailureDiagnosisRepositoryPort {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public FailureDiagnosis insert(String runId, String caseId, long attemptId,
                                   DiagnosisCategory category,
                                   String summary,
                                   String rootCause,
                                   DiagnosisConfidence confidence,
                                   String suggestedRecovery,
                                   String rawResponseJson) {

        Long diagnosisId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_failure_diag " +
                        "(run_id, case_id, attempt_id, category, summary, root_cause, " +
                        " confidence, suggested_recovery, raw_response) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) " +
                        "RETURNING diagnosis_id",
                Long.class,
                runId, caseId, attemptId,
                category.name(), summary, rootCause,
                confidence != null ? confidence.name() : null,
                suggestedRecovery, rawResponseJson);

        log.info("Inserted FailureDiagnosis: id={}, run={}, case={}, attempt={}, category={}",
                diagnosisId, runId, caseId, attemptId, category);

        return findByAttemptId(attemptId).orElseThrow();
    }

    @Override
    public Optional<FailureDiagnosis> findByAttemptId(long attemptId) {
        List<FailureDiagnosis> results = jdbcTemplate.query(
                "SELECT * FROM rag_failure_diag WHERE attempt_id = ? ORDER BY created_at DESC LIMIT 1",
                (rs, rowNum) -> mapDiag(rs), attemptId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<FailureDiagnosis> findByRunIdAndCaseId(String runId, String caseId) {
        return jdbcTemplate.query(
                "SELECT * FROM rag_failure_diag WHERE run_id = ? AND case_id = ? " +
                        "ORDER BY created_at DESC",
                (rs, rowNum) -> mapDiag(rs), runId, caseId);
    }

    private FailureDiagnosis mapDiag(ResultSet rs) throws SQLException {
        return FailureDiagnosis.builder()
                .diagnosisId(rs.getLong("diagnosis_id"))
                .runId(rs.getString("run_id"))
                .caseId(rs.getString("case_id"))
                .attemptId(rs.getLong("attempt_id"))
                .category(DiagnosisCategory.valueOf(rs.getString("category")))
                .summary(rs.getString("summary"))
                .rootCause(rs.getString("root_cause"))
                .confidence(rs.getString("confidence") != null
                        ? DiagnosisConfidence.valueOf(rs.getString("confidence")) : null)
                .suggestedRecovery(rs.getString("suggested_recovery"))
                .rawResponse(rs.getString("raw_response"))
                .build();
    }
}
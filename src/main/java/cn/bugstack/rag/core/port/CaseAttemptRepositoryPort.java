package cn.bugstack.rag.core.port;

import cn.bugstack.rag.core.domain.execution.CaseAttempt;
import cn.bugstack.rag.core.domain.execution.AttemptOutcome;

import java.util.List;
import java.util.Optional;

/**
 * CaseAttempt 仓储端口
 *
 * UNIQUE(run_id, case_id, attempt_number) 在 DB 层保证
 */
public interface CaseAttemptRepositoryPort {

    /**
     * 计算并写入下一 attemptNumber (MAX + 1)
     * 同一事务内,UNIQUE 约束兜底并发
     */
    CaseAttempt insertNextAttempt(String runId, String caseId,
                                  AttemptOutcome outcome,
                                  Integer durationMs,
                                  String errorSummary,
                                  String executionDataJson,
                                  List<String> evidenceRefs);

    List<CaseAttempt> findByRunId(String runId);

    List<CaseAttempt> findByRunIdAndCaseId(String runId, String caseId);

    Optional<CaseAttempt> findLatestByRunIdAndCaseId(String runId, String caseId);

    Optional<CaseAttempt> findByAttemptId(long attemptId);
}
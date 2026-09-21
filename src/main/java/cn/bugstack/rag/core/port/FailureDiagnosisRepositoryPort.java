package cn.bugstack.rag.core.port;

import cn.bugstack.rag.core.domain.execution.FailureDiagnosis;
import cn.bugstack.rag.core.domain.execution.DiagnosisCategory;
import cn.bugstack.rag.core.domain.execution.DiagnosisConfidence;

import java.util.List;
import java.util.Optional;

/**
 * FailureDiagnosis 仓储端口
 */
public interface FailureDiagnosisRepositoryPort {

    FailureDiagnosis insert(String runId, String caseId, long attemptId,
                            DiagnosisCategory category,
                            String summary,
                            String rootCause,
                            DiagnosisConfidence confidence,
                            String suggestedRecovery,
                            String rawResponseJson);

    Optional<FailureDiagnosis> findByAttemptId(long attemptId);

    List<FailureDiagnosis> findByRunIdAndCaseId(String runId, String caseId);
}
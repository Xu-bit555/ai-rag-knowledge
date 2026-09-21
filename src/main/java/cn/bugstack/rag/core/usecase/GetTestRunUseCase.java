package cn.bugstack.rag.core.usecase;

import cn.bugstack.rag.core.domain.execution.CaseAttempt;
import cn.bugstack.rag.core.domain.execution.FailureDiagnosis;
import cn.bugstack.rag.core.domain.execution.TestRun;
import cn.bugstack.rag.core.port.CaseAttemptRepositoryPort;
import cn.bugstack.rag.core.port.FailureDiagnosisRepositoryPort;
import cn.bugstack.rag.core.port.TestRunRepositoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * GetTestRunUseCase - 查询 TestRun 完整结构
 *
 * 返回:
 * - Run metadata + status + counts + overallOutcome
 * - 每个 case:
 *     - latestAttempt
 *     - attemptHistory (全部 attempts)
 *     - diagnosis (latest, 如果存在)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GetTestRunUseCase {

    private final TestRunRepositoryPort testRunRepository;
    private final CaseAttemptRepositoryPort caseAttemptRepository;
    private final FailureDiagnosisRepositoryPort failureDiagnosisRepository;

    public Map<String, Object> execute(String runId) {
        TestRun run = testRunRepository.findByRunId(runId)
                .orElseThrow(() -> new IllegalArgumentException("TestRun not found: " + runId));

        List<String> caseIds = testRunRepository.findCaseIdsByRunId(runId);

        Map<String, Object> result = new HashMap<>();
        result.put("runId", run.getRunId());
        result.put("ragTag", run.getRagTag());
        result.put("name", run.getName());
        result.put("description", run.getDescription());
        result.put("status", run.getStatus() != null ? run.getStatus().name() : null);
        result.put("overallOutcome", run.getOverallOutcome());
        result.put("summary", Map.of(
                "total", run.getTotalCases(),
                "passed", run.getPassedCases(),
                "failed", run.getFailedCases(),
                "blocked", run.getBlockedCases()
        ));
        result.put("createdAt", run.getCreatedAt() != null ? run.getCreatedAt().toString() : null);
        result.put("startedAt", run.getStartedAt() != null ? run.getStartedAt().toString() : null);
        result.put("completedAt", run.getCompletedAt() != null ? run.getCompletedAt().toString() : null);

        List<Map<String, Object>> casesView = new ArrayList<>();
        for (String caseId : caseIds) {
            Map<String, Object> caseView = new HashMap<>();
            caseView.put("caseId", caseId);

            List<CaseAttempt> attempts = caseAttemptRepository.findByRunIdAndCaseId(runId, caseId);

            // attemptHistory
            List<Map<String, Object>> history = new ArrayList<>();
            Map<String, Object> latestView = null;
            for (CaseAttempt a : attempts) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("attemptNumber", a.getAttemptNumber());
                entry.put("outcome", a.getOutcome() != null ? a.getOutcome().name() : null);
                entry.put("durationMs", a.getDurationMs());
                entry.put("errorSummary", a.getErrorSummary());
                entry.put("createdAt", a.getCreatedAt() != null ? a.getCreatedAt().toString() : null);
                history.add(entry);

                if (latestView == null) {  // attempts 已按 attemptNumber ASC 排序,最后一个最大
                    latestView = entry;
                }
            }
            caseView.put("latestAttempt", latestView);
            caseView.put("attemptHistory", history);

            // diagnosis (latest)
            if (!attempts.isEmpty()) {
                CaseAttempt lastAttempt = attempts.get(attempts.size() - 1);
                Optional<FailureDiagnosis> diagOpt =
                        failureDiagnosisRepository.findByAttemptId(lastAttempt.getAttemptId());
                if (diagOpt.isPresent()) {
                    FailureDiagnosis d = diagOpt.get();
                    Map<String, Object> diagView = new HashMap<>();
                    diagView.put("diagnosisId", d.getDiagnosisId());
                    diagView.put("category", d.getCategory() != null ? d.getCategory().name() : null);
                    diagView.put("summary", d.getSummary());
                    diagView.put("rootCause", d.getRootCause());
                    diagView.put("confidence", d.getConfidence() != null ? d.getConfidence().name() : null);
                    diagView.put("suggestedRecovery", d.getSuggestedRecovery());
                    caseView.put("diagnosis", diagView);
                }
            }

            casesView.add(caseView);
        }
        result.put("cases", casesView);
        return result;
    }
}
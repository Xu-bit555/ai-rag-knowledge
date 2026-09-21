package cn.bugstack.rag.core.usecase;

import cn.bugstack.rag.core.domain.execution.AttemptOutcome;
import cn.bugstack.rag.core.domain.execution.CaseAttempt;
import cn.bugstack.rag.core.domain.execution.TestRun;
import cn.bugstack.rag.core.domain.execution.TestRunStatus;
import cn.bugstack.rag.core.port.CaseAttemptRepositoryPort;
import cn.bugstack.rag.core.port.TestRunRepositoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RecordCaseAttemptUseCase - 记录一次执行尝试
 *
 * 职责:
 * 1. 计算 attemptNumber (服务端 MAX+1,Agent 不传)
 * 2. 写入 CaseAttempt
 * 3. 触发 TestRun 状态机:
 *    - CREATED → RUNNING (首次 attempt)
 *    - RUNNING → COMPLETED (所有 case 都有最终 outcome)
 * 4. 更新 passed/failed/blocked 计数 (基于最新 attempts)
 *
 * UNIQUE(run_id, case_id, attempt_number) 在 DB 层兜底并发
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordCaseAttemptUseCase {

    private final CaseAttemptRepositoryPort caseAttemptRepository;
    private final TestRunRepositoryPort testRunRepository;

    /**
     * @return Map {attemptId, attemptNumber, runStatusAfter}
     */
    @Transactional
    public Map<String, Object> execute(String runId, String caseId, String outcomeStr,
                                       Integer durationMs, String errorSummary,
                                       String executionDataJson, List<String> evidenceRefs) {

        if (runId == null || caseId == null || outcomeStr == null) {
            throw new IllegalArgumentException("runId, caseId, outcome are required");
        }

        AttemptOutcome outcome;
        try {
            outcome = AttemptOutcome.valueOf(outcomeStr.toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("outcome must be one of PASSED, FAILED, BLOCKED, got: " + outcomeStr);
        }

        log.info("RecordCaseAttempt: run={}, case={}, outcome={}, duration={}ms",
                runId, caseId, outcome, durationMs);

        // 1. 写入 attempt
        CaseAttempt attempt = caseAttemptRepository.insertNextAttempt(
                runId, caseId, outcome, durationMs, errorSummary, executionDataJson, evidenceRefs);

        // 2. 检查是否所有 case 都有最终 outcome
        TestRun run = testRunRepository.findByRunId(runId)
                .orElseThrow(() -> new IllegalStateException("TestRun not found: " + runId));

        TestRunStatus newStatus = TestRunStatus.RUNNING;  // 至少有一个 attempt

        int[] counts = testRunRepository.countLatestOutcomes(runId);
        int passed = counts[0];
        int failed = counts[1];
        int blocked = counts[2];
        int latestCasesCount = passed + failed + blocked;

        // Phase 2 R4: 状态机推进逻辑
        // - 所有 case 都最终 outcome → COMPLETED 或 FAILED
        // - 否则保持 RUNNING
        if (latestCasesCount >= run.getTotalCases()) {
            if (failed + blocked == run.getTotalCases()) {
                // 所有 case 都 FAILED 或 BLOCKED → FAILED 终态
                newStatus = TestRunStatus.FAILED;
            } else {
                newStatus = TestRunStatus.COMPLETED;
            }
        }

        String overall = computeOverall(passed, failed, blocked);
        TestRun updated = testRunRepository.updateStatus(
                runId, newStatus, passed, failed, blocked, overall);

        Map<String, Object> result = new HashMap<>();
        result.put("attemptId", attempt.getAttemptId());
        result.put("attemptNumber", attempt.getAttemptNumber());
        result.put("runStatusAfter", newStatus.name());
        result.put("runId", runId);
        result.put("caseId", caseId);
        result.put("outcome", outcome.name());
        result.put("passed", passed);
        result.put("failed", failed);
        result.put("blocked", blocked);
        return result;
    }

    /**
     * 计算 overall outcome: 至少一个 FAILED → FAIL; 全 PASSED → PASS; 含 BLOCKED → ERROR
     */
    private String computeOverall(int passed, int failed, int blocked) {
        if (failed > 0) return "FAIL";
        if (passed > 0 && failed == 0 && blocked == 0) return "PASS";
        if (blocked > 0) return "ERROR";
        return "UNKNOWN";
    }
}
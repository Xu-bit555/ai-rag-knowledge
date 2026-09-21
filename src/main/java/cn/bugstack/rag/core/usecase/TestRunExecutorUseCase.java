package cn.bugstack.rag.core.usecase;

import cn.bugstack.rag.core.domain.dsl.v1.TestCaseEntity;
import cn.bugstack.rag.core.domain.execution.AttemptOutcome;
import cn.bugstack.rag.core.domain.execution.CaseAttempt;
import cn.bugstack.rag.core.domain.execution.ExecutionResult;
import cn.bugstack.rag.core.domain.execution.TestRun;
import cn.bugstack.rag.core.domain.execution.TestRunStatus;
import cn.bugstack.rag.core.port.CaseAttemptRepositoryPort;
import cn.bugstack.rag.core.port.TestCaseRepositoryPort;
import cn.bugstack.rag.core.port.TestRunRepositoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * TestRunExecutorUseCase - 桥接 Browser Runtime 和 TestRun/Attempt 生命周期
 *
 * 流程:
 * 1. TestRun = CREATED/RUNNING (已有)
 * 2. BrowserExecutionService.executeCase(runId, caseEntity, attemptN) -> ExecutionResult
 * 3. CaseAttemptRepositoryPort.insertNextAttempt(...) 写入 attempt (自动 attemptNumber)
 * 4. TestRunRepositoryPort.updateStatus(...) 推进状态机
 * 5. 如有 FAILED -> 可选: DiagnoseFailureUseCase (Phase 5)
 *
 * 不持有 BrowserExecutionService 引用,避免循环依赖
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestRunExecutorUseCase {

    private final TestRunRepositoryPort testRunRepository;
    private final TestCaseRepositoryPort testCaseRepository;
    private final CaseAttemptRepositoryPort caseAttemptRepository;

    /**
     * 执行一条 Case 并自动写 Attempt + 推进 Run 状态
     *
     * @param runId TestRun ID
     * @param caseEntity 已 adopted 的 Case
     * @param executionResult 来自 BrowserExecutionService 的执行结果
     * @return Map {attemptId, attemptNumber, runStatusAfter, outcome, passed, failed, blocked}
     */
    @Transactional
    public Map<String, Object> recordExecution(String runId, TestCaseEntity caseEntity,
                                                 ExecutionResult executionResult) {

        TestRun run = testRunRepository.findByRunId(runId)
                .orElseThrow(() -> new IllegalStateException("TestRun not found: " + runId));

        String caseId = caseEntity.getCaseId();

        // 1. 写 attempt (attemptNumber 由 repo 自动计算)
        String executionDataJson = serializeExecutionResult(executionResult);
        CaseAttempt attempt = caseAttemptRepository.insertNextAttempt(
                runId, caseId,
                executionResult.getFinalOutcome(),
                executionResult.getDurationMs() != null ? executionResult.getDurationMs().intValue() : null,
                executionResult.getErrorSummary(),
                executionDataJson,
                executionResult.getEvidenceRefs()
        );

        // 2. 推进 Run 状态
        int[] counts = testRunRepository.countLatestOutcomes(runId);
        int passed = counts[0];
        int failed = counts[1];
        int blocked = counts[2];
        int totalLatest = passed + failed + blocked;

        TestRunStatus newStatus = TestRunStatus.RUNNING;
        if (totalLatest >= run.getTotalCases()) {
            newStatus = TestRunStatus.COMPLETED;
        }

        String overall = computeOverall(passed, failed, blocked);
        TestRun updated = testRunRepository.updateStatus(
                runId, newStatus, passed, failed, blocked, overall);

        Map<String, Object> result = new HashMap<>();
        result.put("attemptId", attempt.getAttemptId());
        result.put("attemptNumber", attempt.getAttemptNumber());
        result.put("runId", runId);
        result.put("caseId", caseId);
        result.put("outcome", executionResult.getFinalOutcome().name());
        result.put("runStatusAfter", newStatus.name());
        result.put("passed", passed);
        result.put("failed", failed);
        result.put("blocked", blocked);
        log.info("recordExecution done: run={}, case={}, attempt={}, outcome={}, status={}",
                runId, caseId, attempt.getAttemptNumber(),
                executionResult.getFinalOutcome(), newStatus);
        return result;
    }

    // executeAndRecord 已删除 (P0-3 修复: 死代码 + 写死 findByCaseId("") 永远查不到)
    // 调用方请直接使用 recordExecution(runId, TestCaseEntity, ExecutionResult)
    // 或 MCP onecase_record_case_attempt (RecordCaseAttemptUseCase)

    private String serializeExecutionResult(ExecutionResult r) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(r);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String computeOverall(int passed, int failed, int blocked) {
        if (failed > 0) return "FAIL";
        if (passed > 0 && failed == 0 && blocked == 0) return "PASS";
        if (blocked > 0) return "ERROR";
        return "UNKNOWN";
    }
}
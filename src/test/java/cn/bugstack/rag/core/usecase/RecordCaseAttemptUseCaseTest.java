package cn.bugstack.rag.core.usecase;

import cn.bugstack.rag.core.domain.execution.AttemptOutcome;
import cn.bugstack.rag.core.domain.execution.CaseAttempt;
import cn.bugstack.rag.core.domain.execution.TestRun;
import cn.bugstack.rag.core.domain.execution.TestRunStatus;
import cn.bugstack.rag.core.port.CaseAttemptRepositoryPort;
import cn.bugstack.rag.core.port.TestRunRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * RecordCaseAttemptUseCase 单元测试
 *
 * 覆盖:
 * - 首次 attempt → RUNNING
 * - 全部 attempts 完成 → COMPLETED
 * - retry 保留 attempt history
 * - 最终 outcome 基于最新 attempt
 */
class RecordCaseAttemptUseCaseTest {

    private TestRunRepositoryPort runRepo;
    private CaseAttemptRepositoryPort attemptRepo;
    private RecordCaseAttemptUseCase useCase;

    private final String RUN_ID = "run-test-1";

    @BeforeEach
    void setUp() {
        attemptRepo = new InMemoryCaseAttemptRepo();
        runRepo = new InMemoryTestRunRepo((InMemoryCaseAttemptRepo) attemptRepo);

        // 预先建好 Run
        TestRun run = TestRun.builder()
                .runId(RUN_ID)
                .ragTag("default")
                .name("login-test")
                .status(TestRunStatus.CREATED)
                .totalCases(3)
                .createdAt(Instant.now())
                .build();
        runRepo.create(run);

        useCase = new RecordCaseAttemptUseCase(attemptRepo, runRepo);
    }

    @Test
    @DisplayName("首次 attempt → RUNNING")
    void firstAttemptRunsRun() {
        Map<String, Object> result = useCase.execute(RUN_ID, "TC_001",
                "PASSED", 1000, null, "{}", List.of());

        assertEquals(1, result.get("attemptNumber"));
        assertEquals("RUNNING", result.get("runStatusAfter"));

        TestRun updated = runRepo.findByRunId(RUN_ID).orElseThrow();
        assertEquals(TestRunStatus.RUNNING, updated.getStatus());
    }

    @Test
    @DisplayName("全部完成 → COMPLETED")
    void allCompleteRunCompleted() {
        useCase.execute(RUN_ID, "TC_001", "PASSED", 1000, null, "{}", List.of());
        useCase.execute(RUN_ID, "TC_002", "PASSED", 1100, null, "{}", List.of());

        Map<String, Object> result = useCase.execute(RUN_ID, "TC_003",
                "PASSED", 1200, null, "{}", List.of());

        assertEquals("COMPLETED", result.get("runStatusAfter"));

        TestRun updated = runRepo.findByRunId(RUN_ID).orElseThrow();
        assertEquals(TestRunStatus.COMPLETED, updated.getStatus());
        assertEquals(3, updated.getPassedCases());
        assertEquals(0, updated.getFailedCases());
    }

    @Test
    @DisplayName("Retry 保留 history + 最新 attempt 决定 final outcome")
    void retryPreservesHistory() {
        useCase.execute(RUN_ID, "TC_001", "FAILED", 1000,
                "button not found", "{}", List.of());
        useCase.execute(RUN_ID, "TC_002", "PASSED", 1000, null, "{}", List.of());
        useCase.execute(RUN_ID, "TC_003", "FAILED", 1000,
                "button not found", "{}", List.of());

        // TC_001 retry 成功
        Map<String, Object> retryResult = useCase.execute(RUN_ID, "TC_001",
                "PASSED", 800, null, "{}", List.of());

        assertEquals(2, retryResult.get("attemptNumber"));
        assertEquals("COMPLETED", retryResult.get("runStatusAfter"));

        // TC_001 历史: 2 行 (FAILED + PASSED)
        List<CaseAttempt> tc1Attempts = attemptRepo.findByRunIdAndCaseId(RUN_ID, "TC_001");
        assertEquals(2, tc1Attempts.size());
        assertEquals(AttemptOutcome.FAILED, tc1Attempts.get(0).getOutcome());
        assertEquals(AttemptOutcome.PASSED, tc1Attempts.get(1).getOutcome());

        // Run 最终统计: TC_001=PASSED(latest), TC_002=PASSED, TC_003=FAILED → 2 PASSED, 1 FAILED
        TestRun updated = runRepo.findByRunId(RUN_ID).orElseThrow();
        assertEquals(2, updated.getPassedCases());
        assertEquals(1, updated.getFailedCases());
        assertEquals(TestRunStatus.COMPLETED, updated.getStatus());
    }

    @Test
    @DisplayName("失败 case 存在 → Run overallOutcome = FAIL")
    void failureMarkOverallFail() {
        useCase.execute(RUN_ID, "TC_001", "PASSED", 1000, null, "{}", List.of());
        useCase.execute(RUN_ID, "TC_002", "FAILED", 1000, "err", "{}", List.of());
        useCase.execute(RUN_ID, "TC_003", "PASSED", 1000, null, "{}", List.of());

        TestRun updated = runRepo.findByRunId(RUN_ID).orElseThrow();
        assertEquals("FAIL", updated.getOverallOutcome());
    }

    @Test
    @DisplayName("BLOCKED 触发 ERROR overallOutcome")
    void blockedTriggersError() {
        useCase.execute(RUN_ID, "TC_001", "PASSED", 1000, null, "{}", List.of());
        useCase.execute(RUN_ID, "TC_002", "BLOCKED", 1000, "blocked", "{}", List.of());

        // 还要再补一个 PASS 才能让 run 完成
        useCase.execute(RUN_ID, "TC_003", "PASSED", 1000, null, "{}", List.of());

        TestRun updated = runRepo.findByRunId(RUN_ID).orElseThrow();
        assertEquals("ERROR", updated.getOverallOutcome());
    }

    // ============ In-memory test doubles ============

    /**
     * 极简内存版 TestRun 仓储 - 持有 attempts 引用用于统计
     */
    static class InMemoryTestRunRepo implements TestRunRepositoryPort {

        private final java.util.Map<String, TestRun> store = new java.util.HashMap<>();
        private final InMemoryCaseAttemptRepo attemptsRef;

        InMemoryTestRunRepo(InMemoryCaseAttemptRepo attemptsRef) {
            this.attemptsRef = attemptsRef;
        }

        @Override
        public TestRun create(TestRun run) {
            store.put(run.getRunId(), run);
            return run;
        }

        @Override
        public java.util.Optional<TestRun> findByRunId(String runId) {
            return java.util.Optional.ofNullable(store.get(runId));
        }

        @Override
        public TestRun updateStatus(String runId, TestRunStatus status, int passed, int failed,
                                    int blocked, String overallOutcome) {
            TestRun r = store.get(runId);
            r.setStatus(status);
            r.setPassedCases(passed);
            r.setFailedCases(failed);
            r.setBlockedCases(blocked);
            r.setOverallOutcome(overallOutcome);
            if (status == TestRunStatus.RUNNING && r.getStartedAt() == null) {
                r.setStartedAt(Instant.now());
            }
            if (status == TestRunStatus.COMPLETED) {
                r.setCompletedAt(Instant.now());
            }
            return r;
        }

        @Override
        public int[] countLatestOutcomes(String runId) {
            int p = 0, f = 0, b = 0;
            for (CaseAttempt a : attemptsRef.findByRunId(runId)) {
                if (a.getAttemptNumber() == null) continue;
                // latest per case = max attempt_number
                List<CaseAttempt> all = attemptsRef.findByRunIdAndCaseId(runId, a.getCaseId());
                CaseAttempt latest = all.get(all.size() - 1);
                if (latest != a) continue;  // 不是 latest
                if (latest.getOutcome() == AttemptOutcome.PASSED) p++;
                else if (latest.getOutcome() == AttemptOutcome.FAILED) f++;
                else if (latest.getOutcome() == AttemptOutcome.BLOCKED) b++;
            }
            return new int[]{p, f, b};
        }

        @Override
        public List<TestRun> findByRagTag(String ragTag) {
            return new ArrayList<>(store.values());
        }

        @Override
        public List<String> findCaseIdsByRunId(String runId) {
            TestRun r = store.get(runId);
            return r.getTotalCases() > 0 ? List.of("TC_001", "TC_002", "TC_003") : List.of();
        }
    }

    /**
     * 极简内存版 CaseAttempt 仓储 - 按 attemptNumber 自增
     */
    static class InMemoryCaseAttemptRepo implements CaseAttemptRepositoryPort {

        private final java.util.Map<String, List<CaseAttempt>> store = new java.util.HashMap<>();
        private final AtomicInteger ids = new AtomicInteger(0);

        @Override
        public CaseAttempt insertNextAttempt(String runId, String caseId,
                                             AttemptOutcome outcome, Integer durationMs,
                                             String errorSummary, String executionDataJson,
                                             List<String> evidenceRefs) {
            String key = runId + ":" + caseId;
            List<CaseAttempt> list = store.computeIfAbsent(key, k -> new ArrayList<>());
            int next = list.size() + 1;
            CaseAttempt a = CaseAttempt.builder()
                    .attemptId((long) ids.incrementAndGet())
                    .runId(runId)
                    .caseId(caseId)
                    .attemptNumber(next)
                    .outcome(outcome)
                    .durationMs(durationMs)
                    .errorSummary(errorSummary)
                    .executionData(executionDataJson)
                    .evidenceRefs(evidenceRefs)
                    .createdAt(Instant.now())
                    .build();
            list.add(a);
            return a;
        }

        @Override
        public List<CaseAttempt> findByRunId(String runId) {
            List<CaseAttempt> all = new ArrayList<>();
            store.forEach((k, v) -> {
                if (k.startsWith(runId + ":")) all.addAll(v);
            });
            return all;
        }

        @Override
        public List<CaseAttempt> findByRunIdAndCaseId(String runId, String caseId) {
            return store.getOrDefault(runId + ":" + caseId, List.of());
        }

        @Override
        public java.util.Optional<CaseAttempt> findLatestByRunIdAndCaseId(String runId, String caseId) {
            List<CaseAttempt> list = store.get(runId + ":" + caseId);
            return list == null || list.isEmpty() ? java.util.Optional.empty()
                    : java.util.Optional.of(list.get(list.size() - 1));
        }

        @Override
        public java.util.Optional<CaseAttempt> findByAttemptId(long attemptId) {
            for (List<CaseAttempt> list : store.values()) {
                for (CaseAttempt a : list) {
                    if (a.getAttemptId() != null && a.getAttemptId() == attemptId) return java.util.Optional.of(a);
                }
            }
            return java.util.Optional.empty();
        }
    }
}
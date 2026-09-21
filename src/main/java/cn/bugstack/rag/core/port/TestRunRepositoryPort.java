package cn.bugstack.rag.core.port;

import cn.bugstack.rag.core.domain.execution.TestRun;
import cn.bugstack.rag.core.domain.execution.TestRunStatus;

import java.util.List;
import java.util.Optional;

/**
 * TestRun 仓储端口
 */
public interface TestRunRepositoryPort {

    TestRun create(TestRun run);

    Optional<TestRun> findByRunId(String runId);

    /**
     * 更新运行状态 + 自动计数(passed/failed/blocked + overall_outcome)
     * 入参传入后立即落库。
     */
    TestRun updateStatus(String runId, TestRunStatus status, int passed, int failed, int blocked,
                       String overallOutcome);

    /**
     * 计算最新 attempts 后的统计 (passed/failed/blocked/overall)
     * 纯查询,不修改。
     */
    int[] countLatestOutcomes(String runId);

    List<TestRun> findByRagTag(String ragTag);

    /**
     * 列出 Run 下所有 adopted caseId (来自 rag_run_case 关联)
     */
    List<String> findCaseIdsByRunId(String runId);
}
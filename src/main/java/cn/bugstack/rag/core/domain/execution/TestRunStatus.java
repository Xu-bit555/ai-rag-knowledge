package cn.bugstack.rag.core.domain.execution;

/**
 * TestRun 状态机
 *
 * Phase 2 R4 修复:
 *   - 新增 FAILED 终态 (之前所有 case 都 FAILED 时 run 仍是 COMPLETED, 不合理)
 *   - 加 isTerminal() 方法, 统一判断终态 (PASSED/COMPLETED/FAILED/CANCELLED)
 */
public enum TestRunStatus {
    CREATED,
    RUNNING,
    COMPLETED,
    CANCELLED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}

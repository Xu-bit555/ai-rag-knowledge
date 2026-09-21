package cn.bugstack.rag.core.domain.execution;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 一次 Case Execution 的最终结果
 *
 * 喂给 onecase_record_case_attempt
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionResult {
    private List<StepResult> steps;
    private AttemptOutcome finalOutcome;
    private List<String> evidenceRefs;
    private String errorSummary;        // 失败时一句概要
    private Long durationMs;
    private Integer recoveryLevel;     // 0 / 1 / 2 / 3
}
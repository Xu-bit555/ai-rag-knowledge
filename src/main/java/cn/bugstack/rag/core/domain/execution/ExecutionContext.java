package cn.bugstack.rag.core.domain.execution;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 执行上下文 - 一次 Case Attempt 的运行时状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionContext {
    private String runId;
    private String caseId;
    private Integer attemptNumber;
    private Integer currentStep;
    private String currentPageUrl;
    @Builder.Default
    private List<StepResult> steps = new ArrayList<>();
    @Builder.Default
    private List<String> evidenceRefs = new ArrayList<>();   // artifact IDs
    private Long startTimeMs;
    private Long endTimeMs;

    public void addStep(StepResult step) {
        this.steps.add(step);
        if (step.getArtifactId() != null) {
            this.evidenceRefs.add(step.getArtifactId());
        }
    }
}
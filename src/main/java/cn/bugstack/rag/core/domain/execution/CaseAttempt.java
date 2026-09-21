package cn.bugstack.rag.core.domain.execution;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseAttempt {
    private Long attemptId;
    private String runId;
    private String caseId;
    private Integer attemptNumber;
    private AttemptOutcome outcome;
    private Integer durationMs;
    private String errorSummary;
    private String executionData;        // JSON string
    private List<String> evidenceRefs;
    private Instant createdAt;
}
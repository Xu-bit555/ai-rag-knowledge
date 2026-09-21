package cn.bugstack.rag.core.domain.execution;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailureDiagnosis {
    private Long diagnosisId;
    private String runId;
    private String caseId;
    private Long attemptId;
    private DiagnosisCategory category;
    private String summary;
    private String rootCause;
    private DiagnosisConfidence confidence;
    private String suggestedRecovery;
    private String rawResponse;            // LLM 原始响应
    private Instant createdAt;
}
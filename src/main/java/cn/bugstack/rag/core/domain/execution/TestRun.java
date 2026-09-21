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
public class TestRun {
    private String runId;
    private String ragTag;
    private String name;
    private String description;
    private String targetUrl;
    private String browser;
    private TestRunStatus status;
    private int totalCases;
    private int passedCases;
    private int failedCases;
    private int blockedCases;
    private String overallOutcome;       // PASS / FAIL / ERROR
    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
}
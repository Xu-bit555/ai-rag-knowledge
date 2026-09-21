package cn.bugstack.rag.adapter.mcp.tool;

import cn.bugstack.rag.adapter.mcp.McpToolConfig;
import cn.bugstack.rag.core.domain.execution.FailureDiagnosis;
import cn.bugstack.rag.core.usecase.DiagnoseFailureUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DiagnoseFailureTool {

    private final DiagnoseFailureUseCase diagnoseFailureUseCase;

    @Tool(name = "onecase_diagnose_failure",
            description = "Diagnose a failed attempt via LLM. Returns structured FailureDiagnosis "
                    + "{category, summary, rootCause, confidence, suggestedRecovery}. PERSISTS "
                    + "to rag_failure_diag. DOES NOT modify TestCase - suggestedRecovery is runtime "
                    + "advice only. Call AFTER recording a FAILED attempt. failureContext can include "
                    + "errorSummary, executionData, assertionResult, pageSnapshot, evidenceRefs JSON.")
    public Map<String, Object> diagnose(
            @ToolParam(description = "TestRun ID", required = true) String runId,
            @ToolParam(description = "Case ID", required = true) String caseId,
            @ToolParam(description = "Attempt ID (returned by record_case_attempt)", required = true) Long attemptId,
            @ToolParam(description = "Failure context JSON string", required = false) String failureContext) {

        log.info("MCP onecase_diagnose_failure: run={}, case={}, attempt={}", runId, caseId, attemptId);

        FailureDiagnosis d = diagnoseFailureUseCase.execute(runId, caseId, attemptId, failureContext);

        Map<String, Object> result = new HashMap<>();
        result.put("diagnosisId", d.getDiagnosisId());
        result.put("runId", d.getRunId());
        result.put("caseId", d.getCaseId());
        result.put("attemptId", d.getAttemptId());
        result.put("category", d.getCategory() != null ? d.getCategory().name() : null);
        result.put("summary", d.getSummary());
        result.put("rootCause", d.getRootCause());
        result.put("confidence", d.getConfidence() != null ? d.getConfidence().name() : null);
        result.put("suggestedRecovery", d.getSuggestedRecovery());
        return result;
    }
}
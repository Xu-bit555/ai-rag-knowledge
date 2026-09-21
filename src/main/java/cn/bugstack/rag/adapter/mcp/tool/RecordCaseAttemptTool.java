package cn.bugstack.rag.adapter.mcp.tool;

import cn.bugstack.rag.adapter.mcp.McpToolConfig;
import cn.bugstack.rag.core.usecase.RecordCaseAttemptUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RecordCaseAttemptTool {

    private final RecordCaseAttemptUseCase recordCaseAttemptUseCase;

    @Tool(name = McpToolConfig.TOOL_RECORD_CASE_ATTEMPT,
            description = "Record one execution attempt for a case in a TestRun. Server computes "
                    + "attemptNumber automatically (MAX+1); Agent should NOT pass attemptNumber. "
                    + "Triggers TestRun status machine: first attempt → RUNNING, all cases have "
                    + "final outcome → COMPLETED. outcome must be PASSED, FAILED, or BLOCKED. "
                    + "Returns {attemptId, attemptNumber, runStatusAfter, passed, failed, blocked}. "
                    + "Retry preserves previous attempts (UNIQUE constraint).")
    public Map<String, Object> record(
            @ToolParam(description = "TestRun ID", required = true) String runId,
            @ToolParam(description = "Adopted case ID", required = true) String caseId,
            @ToolParam(description = "Outcome: PASSED / FAILED / BLOCKED", required = true) String outcome,
            @ToolParam(description = "Total duration in milliseconds", required = false) Integer durationMs,
            @ToolParam(description = "One-line error summary", required = false) String errorSummary,
            @ToolParam(description = "Execution data JSON (steps/assertion results)", required = false) String executionDataJson,
            @ToolParam(description = "Artifact references (MVP may be empty list)", required = false) List<String> evidenceRefs) {

        log.info("MCP onecase_record_case_attempt: run={}, case={}, outcome={}", runId, caseId, outcome);
        return recordCaseAttemptUseCase.execute(runId, caseId, outcome, durationMs,
                errorSummary, executionDataJson, evidenceRefs);
    }
}
package cn.bugstack.rag.adapter.mcp.tool;

import cn.bugstack.rag.adapter.mcp.McpToolConfig;
import cn.bugstack.rag.core.domain.execution.TestRun;
import cn.bugstack.rag.core.usecase.CreateTestRunUseCase;
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
public class CreateTestRunTool {

    private final CreateTestRunUseCase createTestRunUseCase;

    @Tool(name = "onecase_create_test_run",
            description = "Create a TestRun from a list of adopted caseIds. Validates that all cases "
                    + "exist, are in ADOPTED status, and have automationCandidate=WEB_FUNCTIONAL. "
                    + "Returns {runId, status:CREATED, totalCases}. Use AFTER onecase_adopt_cases.")
    public Map<String, Object> create(
            @ToolParam(description = "Knowledge base tag", required = true) String ragTag,
            @ToolParam(description = "Run name (unique within ragTag)", required = true) String name,
            @ToolParam(description = "Description", required = false) String description,
            @ToolParam(description = "Target URL to test (passed to Playwright later)",
                    required = false) String targetUrl,
            @ToolParam(description = "Adopted caseIds", required = true) List<String> caseIds) {

        log.info("MCP onecase_create_test_run: ragTag={}, name={}, caseIds.size={}",
                ragTag, name, caseIds == null ? 0 : caseIds.size());

        TestRun run = createTestRunUseCase.execute(ragTag, name, description, targetUrl, caseIds);
        return Map.of(
                "runId", run.getRunId(),
                "status", run.getStatus() != null ? run.getStatus().name() : "CREATED",
                "totalCases", run.getTotalCases(),
                "ragTag", run.getRagTag(),
                "name", run.getName()
        );
    }
}
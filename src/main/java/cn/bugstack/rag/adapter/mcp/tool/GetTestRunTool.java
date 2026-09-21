package cn.bugstack.rag.adapter.mcp.tool;

import cn.bugstack.rag.adapter.mcp.McpToolConfig;
import cn.bugstack.rag.core.usecase.GetTestRunUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class GetTestRunTool {

    private final GetTestRunUseCase getTestRunUseCase;

    @Tool(name = McpToolConfig.TOOL_GET_TEST_RUN,
            description = "Get complete TestRun structure: status, counts, all cases with "
                    + "latestAttempt + attemptHistory + diagnosis. Returns counts based on LATEST "
                    + "attempt per case (not first attempt). Use this to inspect run state, generate "
                    + "reports, or decide whether to retry a case.")
    public Map<String, Object> get(
            @ToolParam(description = "TestRun ID", required = true) String runId) {

        log.info("MCP onecase_get_test_run: runId={}", runId);
        return getTestRunUseCase.execute(runId);
    }
}
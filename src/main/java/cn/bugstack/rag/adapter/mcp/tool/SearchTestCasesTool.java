package cn.bugstack.rag.adapter.mcp.tool;

import cn.bugstack.rag.adapter.mcp.McpToolConfig;
import cn.bugstack.rag.core.usecase.SearchTestCasesUseCase;
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
public class SearchTestCasesTool {

    private final SearchTestCasesUseCase searchTestCasesUseCase;

    @Tool(name = McpToolConfig.TOOL_SEARCH_TEST_CASES,
            description = "Search OneCase historical adopted test cases (rag_test_case table). "
                    + "Returns array of {caseId, title, automationCandidate, priority, steps, assertions, "
                    + "expectedOutcome}. Use for few-shot reference when generating new cases or to "
                    + "avoid duplicates.")
    public Map<String, Object> search(
            @ToolParam(description = "Knowledge base tag", required = true) String ragTag,
            @ToolParam(description = "Search query (matches caseId or title)", required = true) String query,
            @ToolParam(description = "Top K results, default 10", required = false) Integer topK) {

        log.info("MCP onecase_search_test_cases: ragTag={}, query={}", ragTag, query);
        List<Map<String, Object>> results = searchTestCasesUseCase.execute(ragTag, query, topK);
        return Map.of(
                "results", results,
                "count", results.size(),
                "ragTag", ragTag,
                "query", query
        );
    }
}
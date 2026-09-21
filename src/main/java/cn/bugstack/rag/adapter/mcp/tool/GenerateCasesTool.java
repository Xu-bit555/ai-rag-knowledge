package cn.bugstack.rag.adapter.mcp.tool;

import cn.bugstack.rag.adapter.mcp.McpToolConfig;
import cn.bugstack.rag.core.domain.dsl.v1.CanonicalTestCase;
import cn.bugstack.rag.core.usecase.GenerateCasesUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * onecase_generate_cases MCP Tool
 *
 * Phase 2 第一个核心 Tool: PRD → Canonical DSL
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerateCasesTool {

    private final GenerateCasesUseCase generateCasesUseCase;

    @Tool(name = McpToolConfig.TOOL_GENERATE_CASES,
            description = "Generate Canonical DSL test cases from a requirement using OneCase's "
                    + "RAG-augmented generator. Returns a CanonicalTestCaseDSL object (schemaVersion=1.0.0) "
                    + "with a summary and an array of TestCase entities (each with typed assertions, "
                    + "locator strategies, and priority). Use this when the user/Agent wants new test "
                    + "cases for a feature described in Chinese or English. The DSL is validated by "
                    + "the server before return; on validation failure the tool throws an error.")
    public CanonicalTestCase generate(
            @ToolParam(description = "Knowledge base tag", required = true) String ragTag,
            @ToolParam(description = "Natural language requirement (PRD text)", required = true) String requirement,
            @ToolParam(description = "Optional list of existing case IDs for few-shot reference",
                    required = false) List<String> referenceCaseIds,
            @ToolParam(description = "Optional list of historical knowledge context strings",
                    required = false) List<String> knowledgeDocs) {

        log.info("MCP onecase_generate_cases: ragTag={}, req.length={}", ragTag,
                requirement == null ? 0 : requirement.length());
        return generateCasesUseCase.execute(ragTag, requirement,
                referenceCaseIds, knowledgeDocs);
    }

    /**
     * 返回结构(供 MCP client 校验)
     */
    public Map<String, Object> resultSchema() {
        return Map.of(
                "schemaVersion", "1.0.0",
                "summary", Map.of("app", "string", "page", "string", "totalCases", "integer"),
                "cases", "array<TestCaseEntity>"
        );
    }
}
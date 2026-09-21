package cn.bugstack.rag.adapter.mcp.tool;

import cn.bugstack.rag.adapter.mcp.McpToolConfig;
import cn.bugstack.rag.core.domain.dsl.v1.CanonicalTestCase;
import cn.bugstack.rag.core.domain.dsl.v1.TestCaseEntity;
import cn.bugstack.rag.core.usecase.AdoptCasesUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * onecase_adopt_cases MCP Tool
 *
 * Phase 2 第三个核心 Tool: Canonical DSL → rag_test_case 持久化
 *
 * 调用时机: validate_case 之后、create_test_run 之前
 *
 * 返回: 稳定 caseId 列表 (UUID 后缀格式,如 TC_LOGIN_001_a3b9c2d1)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdoptCasesTool {

    private final AdoptCasesUseCase adoptCasesUseCase;

    @Tool(name = McpToolConfig.TOOL_ADOPT_CASES,
            description = "Persist generated Canonical DSL test cases as 'Adopted Test Assets' in the "
                    + "rag_test_case table. Accepts a complete CanonicalTestCaseDSL object (must contain "
                    + "schemaVersion=1.0.0, summary, and cases[]). Each case is assigned a stable caseId "
                    + "(UUID suffix appended to original LLM caseId). Returns {adoptedCaseIds: string[]} in "
                    + "the same order as cases[]. The Agent should keep these caseIds for subsequent "
                    + "onecase_create_test_run call.")
    public Map<String, Object> adopt(
            @ToolParam(description = "Knowledge base tag", required = true) String ragTag,
            @ToolParam(description = "Complete CanonicalTestCaseDSL object", required = true) CanonicalTestCase dsl) {

        log.info("MCP onecase_adopt_cases: ragTag={}, caseCount={}", ragTag,
                dsl == null || dsl.getCases() == null ? 0 : dsl.getCases().size());

        if (dsl == null || dsl.getCases() == null || dsl.getCases().isEmpty()) {
            throw new IllegalArgumentException("DSL must contain at least one case");
        }

        List<String> caseIds = adoptCasesUseCase.execute(ragTag, dsl);
        return Map.of(
                "adoptedCaseIds", caseIds,
                "count", caseIds.size(),
                "ragTag", ragTag
        );
    }

    /**
     * 采纳单个 Case (供特殊场景使用,如 Agent 动态生成单个 edge case)
     */
    public Map<String, Object> adoptSingle(
            @ToolParam(description = "Knowledge base tag", required = true) String ragTag,
            @ToolParam(description = "Single TestCaseEntity", required = true) TestCaseEntity caseEntity) {

        String stableId = adoptCasesUseCase.executeSingle(ragTag, caseEntity);
        return Map.of(
                "adoptedCaseId", stableId,
                "ragTag", ragTag
        );
    }
}
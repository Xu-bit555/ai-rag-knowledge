package cn.bugstack.rag.adapter.mcp.tool;

import cn.bugstack.rag.adapter.mcp.McpToolConfig;
import cn.bugstack.rag.core.domain.dsl.v1.DslValidator;
import cn.bugstack.rag.core.domain.dsl.v1.DslValidator.ValidationResult;
import cn.bugstack.rag.core.domain.dsl.v1.TestCaseEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * onecase_validate_case MCP Tool
 *
 * Phase 2 第二个核心 Tool: 校验 DSL 合法性
 *
 * 调用时机: generate_cases 之后、adopt_cases 之前
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ValidateCaseTool {

    private final DslValidator dslValidator;

    @Tool(name = McpToolConfig.TOOL_VALIDATE_CASE,
            description = "Validate a TestCaseEntity against Canonical DSL v1.0.0 schema and business "
                    + "semantics. Returns {valid, errors[]}. Use this before calling onecase_adopt_cases "
                    + "to fail fast on invalid DSL. The case object must include automationCandidate field "
                    + "(Agent skips cases where this is not WEB_FUNCTIONAL).")
    public Map<String, Object> validate(
            @ToolParam(description = "TestCaseEntity JSON object", required = true) TestCaseEntity caseEntity) {

        log.info("MCP onecase_validate_case: caseId={}", caseEntity.getCaseId());

        // 包装成 Canonical DSL 单 case 形式
        cn.bugstack.rag.core.domain.dsl.v1.CanonicalTestCase wrapper =
                cn.bugstack.rag.core.domain.dsl.v1.CanonicalTestCase.builder()
                        .schemaVersion("1.0.0")
                        .summary(cn.bugstack.rag.core.domain.dsl.v1.RequirementSummary.builder()
                                .app("validation").page("validation").totalCases(1).build())
                        .cases(List.of(caseEntity))
                        .build();

        String json;
        try {
            json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(wrapper);
        } catch (Exception e) {
            return Map.of("valid", false, "errors", List.of("[Serialize] " + e.getMessage()));
        }

        ValidationResult vr = dslValidator.validate(json);
        return Map.of(
                "valid", vr.isValid(),
                "errors", vr.getErrors()
        );
    }

    /**
     * 批量校验多个 Cases
     */
    public Map<String, Object> validateBatch(@ToolParam(description = "Array of TestCaseEntity", required = true)
                                              List<TestCaseEntity> cases) {
        java.util.List<Map<String, Object>> results = new java.util.ArrayList<>();
        for (TestCaseEntity tc : cases) {
            Map<String, Object> r = validate(tc);
            results.add(Map.of(
                    "caseId", tc.getCaseId(),
                    "valid", r.get("valid"),
                    "errors", r.get("errors")
            ));
        }
        return Map.of("results", results);
    }
}
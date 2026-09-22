package cn.bugstack.rag.adapter.mcp.tool;

import cn.bugstack.rag.core.domain.dsl.v1.CanonicalTestCase;
import cn.bugstack.rag.core.domain.dsl.v1.DslValidator;
import cn.bugstack.rag.core.domain.dsl.v1.DslValidator.ValidationResult;
import cn.bugstack.rag.core.domain.dsl.v1.RequirementSummary;
import cn.bugstack.rag.core.domain.dsl.v1.TestCaseEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * onecase_validate_case MCP Tool
 *
 * Phase 2 hotfix: 改接收 CanonicalTestCase (含 schemaVersion) 而非裸 TestCaseEntity,
 *   让 L6 dispatcher 能根据用户传的 schemaVersion 选 schema. 同时支持旧 API:
 *   单 TestCaseEntity 也能调 (会自动包成 CanonicalTestCase v1.0.0).
 *
 * Bug F fix: MCP/Spring AI binding 对复杂类型(Map/List/JSON 对象) 不可靠,
 *   rawCase / rawCaseJson 都曾返回 null. 改为接收 CanonicalTestCase 直接对象,
 *   Spring AI 反射构造器稳定. Tool 内部判定是否为 CanonicalTestCase:
 *   - 含 cases 字段 → CanonicalTestCase
 *   - 只有 caseId/steps → 当 TestCaseEntity, 包成 v1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ValidateCaseTool {

    private final DslValidator dslValidator;
    private final ObjectMapper objectMapper;

    @Tool(name = "onecase_validate_case",
            description = "Validate DSL against Canonical schema and business semantics. "
                    + "Accepts either a full CanonicalTestCase (with schemaVersion) or a single "
                    + "TestCaseEntity (auto-wrapped as v1.0.0). Returns {valid, errors[]}.")
    public Map<String, Object> validate(
            @ToolParam(description = "CanonicalTestCase DSL object", required = true)
            CanonicalTestCase dsl) {

        log.info("MCP onecase_validate_case: cases={}", dsl != null && dsl.getCases() != null ? dsl.getCases().size() : -1);

        // null/empty guard: MCP binding 偶尔会传 null
        if (dsl == null) {
            return Map.of("valid", false, "errors",
                    List.of(Map.of("path", "$", "code", "PARSE_ERROR",
                            "message", "dsl parameter is null — caller must send CanonicalTestCase JSON")));
        }

        // 旧 API 兼容: 若只有 1 个 case 且 summary 缺, 把单个 case 当 TestCaseEntity 重包
        CanonicalTestCase toValidate;
        if (dsl.getSchemaVersion() == null && dsl.getSummary() == null
                && dsl.getCases() != null && dsl.getCases().size() == 1) {
            TestCaseEntity tc = dsl.getCases().get(0);
            if (tc != null && tc.getCaseId() != null) {
                toValidate = CanonicalTestCase.builder()
                        .schemaVersion("1.0.0")
                        .summary(RequirementSummary.builder()
                                .app("validation").page("validation").totalCases(1).build())
                        .cases(List.of(tc))
                        .build();
            } else {
                toValidate = dsl;
            }
        } else {
            toValidate = dsl;
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(toValidate);
        } catch (Exception e) {
            return Map.of("valid", false, "errors",
                    List.of(Map.of("path", "$", "code", "SERIALIZE_ERROR",
                            "message", "Cannot serialize: " + e.getMessage())));
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
    public Map<String, Object> validateBatch(
            @ToolParam(description = "Array of TestCaseEntity", required = true)
            List<TestCaseEntity> cases) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (TestCaseEntity tc : cases) {
            CanonicalTestCase wrapped = CanonicalTestCase.builder()
                    .schemaVersion("1.0.0")
                    .summary(RequirementSummary.builder()
                            .app("validation").page("validation").totalCases(1).build())
                    .cases(List.of(tc))
                    .build();
            Map<String, Object> r = validate(wrapped);
            results.add(Map.of(
                    "caseId", tc.getCaseId(),
                    "valid", r.get("valid"),
                    "errors", r.get("errors")
            ));
        }
        return Map.of("results", results);
    }
}

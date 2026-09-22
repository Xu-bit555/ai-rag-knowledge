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
            @ToolParam(description = "CanonicalTestCase or TestCaseEntity JSON string", required = true)
            String rawCaseJson) {

        log.info("MCP onecase_validate_case: len={}", rawCaseJson != null ? rawCaseJson.length() : -1);

        // Bug F fix: MCP/Spring AI 不稳定地把 JSON object 反序列化为 Map<String,Object> 参数,
        //   改成接收 JSON string, 内部用 Jackson 解析. 同时兼容两种形态:
        //   1) 完整 CanonicalTestCase {schemaVersion, summary, cases}
        //   2) 单个 TestCaseEntity {caseId, steps, ...}  → 自动包成 v1.0.0 CanonicalTestCase
        Map<String, Object> rawCase;
        try {
            rawCase = objectMapper.readValue(rawCaseJson, Map.class);
        } catch (Exception e) {
            return Map.of("valid", false, "errors",
                    List.of(Map.of("path", "$", "code", "PARSE_ERROR",
                            "message", "Cannot parse rawCaseJson: " + e.getMessage())));
        }

        // 判定: 有 schemaVersion 字段 → CanonicalTestCase, 否则 → TestCaseEntity (wrap)
        CanonicalTestCase dsl;
        if (rawCase.containsKey("schemaVersion") || rawCase.containsKey("summary") || rawCase.containsKey("cases")) {
            try {
                dsl = objectMapper.convertValue(rawCase, CanonicalTestCase.class);
            } catch (Exception e) {
                return Map.of("valid", false, "errors",
                        List.of(Map.of("path", "$", "code", "PARSE_ERROR",
                                "message", "Cannot parse as CanonicalTestCase: " + e.getMessage())));
            }
        } else {
            // 旧 API: 接收裸 TestCaseEntity, 包成 CanonicalTestCase v1.0.0
            TestCaseEntity tc;
            try {
                tc = objectMapper.convertValue(rawCase, TestCaseEntity.class);
            } catch (Exception e) {
                return Map.of("valid", false, "errors",
                        List.of(Map.of("path", "$", "code", "PARSE_ERROR",
                                "message", "Cannot parse as TestCaseEntity: " + e.getMessage())));
            }
            dsl = CanonicalTestCase.builder()
                    .schemaVersion("1.0.0")
                    .summary(RequirementSummary.builder()
                            .app("validation").page("validation").totalCases(1).build())
                    .cases(List.of(tc))
                    .build();
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(dsl);
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
            String inputJson;
            try {
                inputJson = objectMapper.writeValueAsString(tc);
            } catch (Exception e) {
                results.add(Map.of(
                        "caseId", tc.getCaseId(),
                        "valid", false,
                        "errors", List.of(Map.of("path", "$", "code", "SERIALIZE_ERROR",
                                "message", e.getMessage()))
                ));
                continue;
            }
            Map<String, Object> r = validate(inputJson);
            results.add(Map.of(
                    "caseId", tc.getCaseId(),
                    "valid", r.get("valid"),
                    "errors", r.get("errors")
            ));
        }
        return Map.of("results", results);
    }
}

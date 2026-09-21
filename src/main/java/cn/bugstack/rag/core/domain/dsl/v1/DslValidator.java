package cn.bugstack.rag.core.domain.dsl.v1;

import cn.bugstack.rag.core.domain.dsl.v1.enums.AssertionKind;
import cn.bugstack.rag.core.domain.dsl.v1.enums.LocatorStrategyKind;
import cn.bugstack.rag.core.domain.dsl.v1.enums.StepAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * DslValidator - Canonical Test Case DSL 验证器
 *
 * 双重校验:
 * 1. JSON Schema 校验 (结构 / 必填 / 枚举值)
 * 2. 业务语义校验 (locator 字段策略一致性 / assertion expected 必填等)
 *
 * P0-4 修复: ValidationResult.errors 从 List<String> 升级为 List<FieldError>,
 *   每条错误带 path (JSON pointer 如 $.cases[0].steps[2].target.role) + code + message,
 *   方便 Agent LLM 精准定位重试. 兼容旧方法 getErrorMessages() 返回 List<String>.
 */
@Slf4j
@Component
public class DslValidator {

    private static final String SCHEMA_PATH = "/schemas/canonical-test-case/v1.json";
    private static final String CURRENT_SCHEMA_VERSION = "1.0.0";

    private final ObjectMapper objectMapper;
    private final Schema jsonSchema;

    public DslValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.jsonSchema = loadSchema(objectMapper);
    }

    private Schema loadSchema(ObjectMapper mapper) {
        try (InputStream is = getClass().getResourceAsStream(SCHEMA_PATH)) {
            if (is == null) {
                throw new IllegalStateException("Schema not found: " + SCHEMA_PATH);
            }
            JsonNode schemaNode = mapper.readTree(is);
            SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
            return registry.getSchema(schemaNode);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load DSL schema", e);
        }
    }

    /**
     * 结构化字段错误 — 路径 + 错误码 + 可读消息
     * path 用 JSON pointer 格式 ($.cases[0].steps[2].target.role)
     */
    public record FieldError(String path, String code, String message) {
        public String toLine() {
            return path + ": " + message;
        }
    }

    /**
     * 验证 DSL JSON 字符串
     */
    public ValidationResult validate(String dslJson) {
        List<FieldError> errors = new ArrayList<>();

        // 阶段 1: JSON Schema 校验
        try {
            JsonNode jsonNode = objectMapper.readTree(dslJson);
            List<Error> schemaErrors = jsonSchema.validate(jsonNode);
            for (Error err : schemaErrors) {
                errors.add(new FieldError(
                        pointerFromInstanceLocation(err.getInstanceLocation()),
                        "SCHEMA_" + err.getType(),
                        err.getMessage()));
            }
        } catch (Exception e) {
            errors.add(new FieldError("$", "PARSE_ERROR", "Invalid JSON: " + e.getMessage()));
            return ValidationResult.invalid(errors);
        }

        // 阶段 2: 业务语义校验
        try {
            CanonicalTestCase dsl = objectMapper.readValue(dslJson, CanonicalTestCase.class);
            validateSemantics(dsl, errors);
        } catch (Exception e) {
            errors.add(new FieldError("$", "SEMANTIC_PARSE", e.getMessage()));
        }

        return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
    }

    /**
     * 把 networknt Error 的 instanceLocation 转成 JSON pointer
     * (networknt 返回的是 JsonNode/Set<String>, 简化处理: 取 toString 后规范化为 $.a.b[0].c 形式)
     */
    private String pointerFromInstanceLocation(Object location) {
        if (location == null) return "$";
        String s = location.toString();
        if (s == null || s.isBlank() || s.equals("/")) return "$";
        // networknt 输出形如 "/cases/0/steps/2/target/role"
        // 转换为 JSON pointer: $/cases/0/steps/2/target/role
        return "$" + s.replace("/", "/");
    }

    /**
     * 业务语义校验
     */
    private void validateSemantics(CanonicalTestCase dsl, List<FieldError> errors) {
        if (!CURRENT_SCHEMA_VERSION.equals(dsl.getSchemaVersion())) {
            errors.add(new FieldError("$.schemaVersion", "SEM_SCHEMA_VERSION",
                    "schemaVersion must be '" + CURRENT_SCHEMA_VERSION
                            + "', got: " + dsl.getSchemaVersion()));
        }

        if (dsl.getSummary() == null) {
            errors.add(new FieldError("$.summary", "SEM_SUMMARY_REQUIRED", "summary is required"));
        } else if (dsl.getSummary().getTotalCases() == null) {
            errors.add(new FieldError("$.summary.totalCases", "SEM_FIELD_REQUIRED",
                    "summary.totalCases is required"));
        }

        if (dsl.getCases() == null || dsl.getCases().isEmpty()) {
            errors.add(new FieldError("$.cases", "SEM_CASES_EMPTY",
                    "cases must contain at least one test case"));
        } else {
            int index = 0;
            for (TestCaseEntity tc : dsl.getCases()) {
                index++;
                validateTestCase(tc, index, errors);
            }
        }
    }

    private void validateTestCase(TestCaseEntity tc, int index, List<FieldError> errors) {
        String base = "$.cases[" + (index - 1) + "]";

        if (tc.getCaseId() == null || !tc.getCaseId().matches("^TC_[A-Za-z0-9_]+$")) {
            errors.add(new FieldError(base + ".caseId", "SEM_CASE_ID_FORMAT",
                    "caseId must match ^TC_[A-Za-z0-9_]+$, got: " + tc.getCaseId()));
        }

        if (tc.getTitle() == null || tc.getTitle().isBlank()) {
            errors.add(new FieldError(base + ".title", "SEM_FIELD_REQUIRED", "title is required"));
        }

        if (tc.getAutomationCandidate() == null) {
            errors.add(new FieldError(base + ".automationCandidate", "SEM_FIELD_REQUIRED",
                    "automationCandidate is required"));
        }

        if (tc.getSteps() == null || tc.getSteps().isEmpty()) {
            errors.add(new FieldError(base + ".steps", "SEM_STEPS_EMPTY",
                    "steps must contain at least one step"));
        } else {
            int order = 0;
            for (TestStepEntity step : tc.getSteps()) {
                order++;
                validateStep(base, tc.getCaseId(), step, order, errors);
            }
        }

        if (tc.getAssertions() != null) {
            int aIdx = 0;
            for (Assertion a : tc.getAssertions()) {
                aIdx++;
                validateAssertion(base, tc.getCaseId(), a, aIdx, errors);
            }
        }
    }

    private void validateStep(String caseBase, String caseId, TestStepEntity step,
                               int expectedOrder, List<FieldError> errors) {
        String base = caseBase + ".steps[" + (expectedOrder - 1) + "]";

        if (step.getOrder() == null) {
            errors.add(new FieldError(base + ".order", "SEM_FIELD_REQUIRED", "order is required"));
        } else if (!step.getOrder().equals(expectedOrder)) {
            errors.add(new FieldError(base + ".order", "SEM_ORDER_SEQUENTIAL",
                    "order must be sequential (" + expectedOrder + "), got: " + step.getOrder()));
        }

        if (step.getAction() == null) {
            errors.add(new FieldError(base + ".action", "SEM_FIELD_REQUIRED", "action is required"));
        }

        StepAction action = step.getAction();
        if (action == StepAction.NAVIGATE) {
            if (step.getUrl() == null || step.getUrl().isBlank()) {
                errors.add(new FieldError(base + ".url", "SEM_URL_REQUIRED_NAVIGATE",
                        "url is required for action: NAVIGATE"));
            }
            if (step.getTarget() != null) {
                errors.add(new FieldError(base + ".target", "SEM_TARGET_FORBIDDEN_NAVIGATE",
                        "target should be omitted for action: NAVIGATE"));
            }
        } else if (action != null && step.getTarget() == null
                && action != StepAction.WAIT
                && action != StepAction.SCREENSHOT) {
            errors.add(new FieldError(base + ".target", "SEM_TARGET_REQUIRED",
                    "target is required for action: " + action));
        }

        if (step.getTarget() != null) {
            validateLocator(base + ".target", step.getTarget(), errors);
        }

        if (action == StepAction.INPUT || action == StepAction.SELECT) {
            if (step.getValue() == null || step.getValue().isBlank()) {
                errors.add(new FieldError(base + ".value", "SEM_VALUE_REQUIRED",
                        "value is required for action: " + action));
            }
        }
    }

    private void validateLocator(String base, TargetLocator loc, List<FieldError> errors) {
        if (loc.getStrategy() == null) {
            errors.add(new FieldError(base + ".strategy", "SEM_FIELD_REQUIRED",
                    "target.strategy is required"));
            return;
        }

        LocatorStrategyKind kind = loc.getStrategy();
        switch (kind) {
            case ROLE:
                if (loc.getRole() == null || loc.getRole().isBlank()) {
                    errors.add(new FieldError(base + ".role", "SEM_LOCATOR_FIELD_REQUIRED",
                            "target.role is required for strategy ROLE"));
                }
                if (loc.getName() == null || loc.getName().isBlank()) {
                    errors.add(new FieldError(base + ".name", "SEM_LOCATOR_FIELD_REQUIRED",
                            "target.name is required for strategy ROLE"));
                }
                break;
            case TEXT:
                if (loc.getText() == null || loc.getText().isBlank()) {
                    errors.add(new FieldError(base + ".text", "SEM_LOCATOR_FIELD_REQUIRED",
                            "target.text is required for strategy TEXT"));
                }
                break;
            case LABEL:
                if (loc.getLabel() == null && loc.getText() == null) {
                    errors.add(new FieldError(base + ".label", "SEM_LOCATOR_FIELD_REQUIRED",
                            "target.label or target.text is required for strategy LABEL"));
                }
                break;
            case TESTID:
                if (loc.getTestId() == null || loc.getTestId().isBlank()) {
                    errors.add(new FieldError(base + ".testId", "SEM_LOCATOR_FIELD_REQUIRED",
                            "target.testId is required for strategy TESTID"));
                }
                break;
            case CSS:
                if (loc.getSelector() == null || loc.getSelector().isBlank()) {
                    errors.add(new FieldError(base + ".selector", "SEM_LOCATOR_FIELD_REQUIRED",
                            "target.selector is required for strategy CSS"));
                }
                break;
            case XPATH:
                if (loc.getXpath() == null || loc.getXpath().isBlank()) {
                    errors.add(new FieldError(base + ".xpath", "SEM_LOCATOR_FIELD_REQUIRED",
                            "target.xpath is required for strategy XPATH"));
                }
                break;
            default:
                break;
        }
    }

    private void validateAssertion(String caseBase, String caseId, Assertion a,
                                    int idx, List<FieldError> errors) {
        String base = caseBase + ".assertions[" + (idx - 1) + "]";
        if (a.getKind() == null) {
            errors.add(new FieldError(base + ".kind", "SEM_FIELD_REQUIRED", "kind is required"));
            return;
        }

        AssertionKind kind = a.getKind();
        if (kind == AssertionKind.URL_EQUALS
                || kind == AssertionKind.TEXT_EQUALS
                || kind == AssertionKind.VALUE_EQUALS) {
            if (a.getExpected() == null || a.getExpected().isBlank()) {
                errors.add(new FieldError(base + ".expected", "SEM_EXPECTED_REQUIRED",
                        "expected is required for kind: " + kind));
            }
        }

        if (kind == AssertionKind.ELEMENT_VISIBLE
                || kind == AssertionKind.ELEMENT_NOT_PRESENT
                || kind == AssertionKind.TEXT_CONTAINS) {
            if (a.getTarget() == null) {
                errors.add(new FieldError(base + ".target", "SEM_TARGET_REQUIRED_ASSERTION",
                        "target is required for kind: " + kind));
            }
        }

        if (kind == AssertionKind.TEXT_CONTAINS
                || kind == AssertionKind.URL_CONTAINS) {
            if (a.getExpected() == null || a.getExpected().isBlank()) {
                errors.add(new FieldError(base + ".expected", "SEM_EXPECTED_REQUIRED",
                        "expected is required for kind: " + kind));
            }
        }
    }

    /**
     * 验证结果 — P0-4 升级为 List<FieldError>, 保留 getErrorMessages() 兼容旧调用
     */
    public static class ValidationResult {
        private final boolean valid;
        private final List<FieldError> errors;

        private ValidationResult(boolean valid, List<FieldError> errors) {
            this.valid = valid;
            this.errors = errors;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult invalid(List<FieldError> errors) {
            return new ValidationResult(false, errors);
        }

        public boolean isValid() {
            return valid;
        }

        public List<FieldError> getErrors() {
            return errors;
        }

        /**
         * 兼容旧 List<String> 调用方: "$.path: message" 格式
         */
        public List<String> getErrorMessages() {
            return errors.stream().map(FieldError::toLine).toList();
        }
    }
}
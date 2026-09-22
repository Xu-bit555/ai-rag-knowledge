package cn.bugstack.rag.core.domain.dsl;

import cn.bugstack.rag.core.domain.dsl.v1.CanonicalTestCase;
import cn.bugstack.rag.core.domain.dsl.v1.DslValidator;
import cn.bugstack.rag.core.domain.dsl.v1.DslValidator.ValidationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DslValidator Test - Phase 1.5 验收测试
 *
 * 覆盖:
 * - JSON → DTO 反序列化
 * - DTO → JSON 序列化
 * - 合法 DSL → validator pass
 * - 非法 DSL (schema 不匹配) → validator reject
 * - 非法 DSL (语义错误: locator 字段缺失 / order 不连续 / assertion expected 缺失) → validator reject
 */
class DslValidatorTest {

    private static DslValidator validator;
    private static ObjectMapper mapper;

    @BeforeAll
    static void setUp() {
        mapper = new ObjectMapper();
        validator = new DslValidator(mapper);
    }

    /**
     * 1. 完整合法 DSL - 应通过
     */
    @Test
    @DisplayName("合法 DSL - 登录成功")
    void validDsl_loginSuccess() {
        String dsl = """
                {
                  "schemaVersion": "1.0.0",
                  "summary": {
                    "app": "登录模块",
                    "page": "登录页",
                    "totalCases": 1
                  },
                  "cases": [
                    {
                      "caseId": "TC_LOGIN_001",
                      "title": "登录成功跳转欢迎页",
                      "automationCandidate": "WEB_FUNCTIONAL",
                      "priority": "P0",
                      "precondition": ["用户已注册"],
                      "testData": { "username": "alice", "password": "secret123" },
                      "steps": [
                        {
                          "order": 1,
                          "action": "NAVIGATE",
                          "url": "http://localhost:8081/login"
                        },
                        {
                          "order": 2,
                          "action": "INPUT",
                          "target": { "strategy": "LABEL", "label": "用户名" },
                          "value": "alice"
                        },
                        {
                          "order": 3,
                          "action": "INPUT",
                          "target": { "strategy": "LABEL", "label": "密码" },
                          "value": "secret123"
                        },
                        {
                          "order": 4,
                          "action": "CLICK",
                          "target": { "strategy": "ROLE", "role": "button", "name": "登录" }
                        }
                      ],
                      "expectedOutcome": "跳转 /dashboard,显示欢迎信息",
                      "assertions": [
                        { "kind": "URL_CONTAINS", "expected": "/dashboard" },
                        { "kind": "ELEMENT_VISIBLE", "target": { "strategy": "TEXT", "text": "欢迎回来" } }
                      ]
                    }
                  ]
                }
                """;
        ValidationResult r = validator.validate(dsl);
        assertTrue(r.isValid(), () -> "Expected valid, got errors: " + r.getErrors());
    }

    /**
     * 2. JSON 反序列化 → CanonicalTestCase
     */
    @Test
    @DisplayName("JSON 反序列化为 Java DTO")
    void jsonToDto() throws Exception {
        String dsl = minimalValidDsl();
        CanonicalTestCase root = mapper.readValue(dsl, CanonicalTestCase.class);
        assertNotNull(root);
        assertEquals("1.0.0", root.getSchemaVersion());
        assertEquals(1, root.getCases().size());
        assertEquals("TC_LOGIN_001", root.getCases().get(0).getCaseId());
        assertEquals("alice", root.getCases().get(0).getSteps().get(1).getValue());
    }

    /**
     * 3. DTO → JSON 反向序列化
     */
    @Test
    @DisplayName("DTO 序列化为 JSON")
    void dtoToJson() throws Exception {
        String original = minimalValidDsl();
        CanonicalTestCase root = mapper.readValue(original, CanonicalTestCase.class);
        String back = mapper.writeValueAsString(root);
        // 关键字段 round-trip
        assertTrue(back.contains("\"schemaVersion\":\"1.0.0\""));
        assertTrue(back.contains("\"caseId\":\"TC_LOGIN_001\""));
        assertTrue(back.contains("\"strategy\":\"ROLE\""));
        assertTrue(back.contains("\"alice\""));
    }

    /**
     * 4. schemaVersion 错误 - 应被拒绝
     */
    @Test
    @DisplayName("非法 DSL - 错误 schemaVersion")
    void invalidSchemaVersion() {
        String dsl = minimalValidDsl().replace("\"1.0.0\"", "\"2.0.0\"");
        ValidationResult r = validator.validate(dsl);
        assertFalse(r.isValid());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("schemaVersion")),
                () -> "Errors: " + r.getErrors());
    }

    /**
     * 5. 必填字段缺失 - cases 为空
     */
    @Test
    @DisplayName("非法 DSL - cases 为空数组")
    void emptyCases() {
        String dsl = """
                {
                  "schemaVersion": "1.0.0",
                  "summary": { "app": "x", "page": "y", "totalCases": 0 },
                  "cases": []
                }
                """;
        ValidationResult r = validator.validate(dsl);
        assertFalse(r.isValid());
    }

    /**
     * 6. 业务语义 - ROLE 策略缺 role/name
     */
    @Test
    @DisplayName("非法 DSL - ROLE 策略缺 role/name 字段")
    void invalidRoleLocator() {
        String dsl = """
                {
                  "schemaVersion": "1.0.0",
                  "summary": { "app": "x", "page": "y", "totalCases": 1 },
                  "cases": [
                    {
                      "caseId": "TC_X_001",
                      "title": "test",
                      "automationCandidate": "WEB_FUNCTIONAL",
                      "steps": [
                        { "order": 1, "action": "CLICK", "target": { "strategy": "ROLE" } }
                      ],
                      "expectedOutcome": "x"
                    }
                  ]
                }
                """;
        ValidationResult r = validator.validate(dsl);
        assertFalse(r.isValid());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("target.role is required")));
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("target.name is required")));
    }

    /**
     * 7. 业务语义 - INPUT action 缺 value
     */
    @Test
    @DisplayName("非法 DSL - INPUT 缺 value")
    void inputMissingValue() {
        String dsl = """
                {
                  "schemaVersion": "1.0.0",
                  "summary": { "app": "x", "page": "y", "totalCases": 1 },
                  "cases": [
                    {
                      "caseId": "TC_X_002",
                      "title": "test input",
                      "automationCandidate": "WEB_FUNCTIONAL",
                      "steps": [
                        {
                          "order": 1,
                          "action": "INPUT",
                          "target": { "strategy": "LABEL", "label": "用户名" }
                        }
                      ],
                      "expectedOutcome": "x"
                    }
                  ]
                }
                """;
        ValidationResult r = validator.validate(dsl);
        assertFalse(r.isValid());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("value is required")));
    }

    /**
     * 8. 业务语义 - step order 不连续
     */
    @Test
    @DisplayName("非法 DSL - step order 跳号")
    void stepOrderGap() {
        String dsl = """
                {
                  "schemaVersion": "1.0.0",
                  "summary": { "app": "x", "page": "y", "totalCases": 1 },
                  "cases": [
                    {
                      "caseId": "TC_X_003",
                      "title": "test order",
                      "automationCandidate": "WEB_FUNCTIONAL",
                      "steps": [
                        { "order": 1, "action": "NAVIGATE", "url": "/login" },
                        { "order": 3, "action": "CLICK", "target": { "strategy": "ROLE", "role": "button", "name": "go" } }
                      ],
                      "expectedOutcome": "x"
                    }
                  ]
                }
                """;
        ValidationResult r = validator.validate(dsl);
        assertFalse(r.isValid());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("order must be sequential")));
    }

    /**
     * 9. 业务语义 - TEXT_EQUALS assertion 缺 expected
     */
    @Test
    @DisplayName("非法 DSL - TEXT_EQUALS assertion 缺 expected")
    void textEqualsAssertionMissingExpected() {
        String dsl = """
                {
                  "schemaVersion": "1.0.0",
                  "summary": { "app": "x", "page": "y", "totalCases": 1 },
                  "cases": [
                    {
                      "caseId": "TC_X_004",
                      "title": "test assertion",
                      "automationCandidate": "WEB_FUNCTIONAL",
                      "steps": [
                        { "order": 1, "action": "NAVIGATE", "url": "/login" }
                      ],
                      "expectedOutcome": "x",
                      "assertions": [
                        { "kind": "TEXT_EQUALS", "target": { "strategy": "TEXT", "text": "标题" } }
                      ]
                    }
                  ]
                }
                """;
        ValidationResult r = validator.validate(dsl);
        assertFalse(r.isValid());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("expected is required for kind: TEXT_EQUALS")));
    }

    /**
     * 10. caseId 格式错误
     */
    @Test
    @DisplayName("非法 DSL - caseId 不符合 TC_xxx 格式")
    void invalidCaseIdFormat() {
        String dsl = minimalValidDsl().replace("TC_LOGIN_001", "LOGIN_001");
        ValidationResult r = validator.validate(dsl);
        assertFalse(r.isValid());
        // Phase 2 L5: caseId 格式校验由 JSON Schema pattern 单一权威, 错误码 SCHEMA_pattern
        assertTrue(r.getErrors().stream().anyMatch(e -> e.code().contains("pattern")),
                () -> "Errors: " + r.getErrors());
    }

    private String minimalValidDsl() {
        return """
                {
                  "schemaVersion": "1.0.0",
                  "summary": { "app": "x", "page": "y", "totalCases": 1 },
                  "cases": [
                    {
                      "caseId": "TC_LOGIN_001",
                      "title": "minimal test case",
                      "automationCandidate": "WEB_FUNCTIONAL",
                      "steps": [
                        { "order": 1, "action": "NAVIGATE", "url": "/login" },
                        { "order": 2, "action": "INPUT", "target": { "strategy": "ROLE", "role": "textbox", "name": "用户名" }, "value": "alice" }
                      ],
                      "expectedOutcome": "ok"
                    }
                  ]
                }
                """;
    }
}
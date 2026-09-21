package cn.bugstack.rag.core.domain.dsl.v1;

import cn.bugstack.rag.core.domain.dsl.v1.enums.AutomationCandidate;
import cn.bugstack.rag.core.domain.dsl.v1.enums.CaseType;
import cn.bugstack.rag.core.domain.dsl.v1.enums.DesignMethod;
import cn.bugstack.rag.core.domain.dsl.v1.enums.Priority;
import cn.bugstack.rag.core.domain.dsl.v1.enums.Risk;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * TestCase - DSL 测试用例
 *
 * automationCandidate 必填,Agent 据此判断是否交给 Playwright。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TestCaseEntity {
    @JsonProperty("caseId")
    private String caseId;

    @JsonProperty("title")
    private String title;

    @JsonProperty("caseType")
    private CaseType caseType;

    @JsonProperty("designMethod")
    private DesignMethod designMethod;

    @JsonProperty("priority")
    private Priority priority;

    @JsonProperty("risk")
    private Risk risk;

    /** 必填 - 决定 Agent 是否自动执行 */
    @JsonProperty("automationCandidate")
    private AutomationCandidate automationCandidate;

    @JsonProperty("precondition")
    private List<String> precondition;

    @JsonProperty("testData")
    private Map<String, Object> testData;   // P0-7: 改为 Object 以支持 number/boolean/array (LLM 真实业务数据)

    @JsonProperty("steps")
    private List<TestStepEntity> steps;

    @JsonProperty("expectedOutcome")
    private String expectedOutcome;

    @JsonProperty("assertions")
    private List<Assertion> assertions;
}
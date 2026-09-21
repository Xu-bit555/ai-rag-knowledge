package cn.bugstack.rag.core.domain.dsl.v1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * CanonicalTestCase - DSL 根对象
 *
 * schemaVersion 固定为 "1.0.0",防止 LLM prompt 微调后旧数据出错。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CanonicalTestCase {
    @JsonProperty("schemaVersion")
    private String schemaVersion;

    @JsonProperty("generatedAt")
    private String generatedAt;

    @JsonProperty("sourceRequirementRef")
    private String sourceRequirementRef;

    @JsonProperty("summary")
    private RequirementSummary summary;

    @JsonProperty("cases")
    private List<TestCaseEntity> cases;
}
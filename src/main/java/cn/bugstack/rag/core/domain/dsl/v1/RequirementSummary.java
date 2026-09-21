package cn.bugstack.rag.core.domain.dsl.v1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RequirementSummary - DSL summary 块
 *
 * 描述当前 PRD 摘要（应用、页面、生成的用例总数）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RequirementSummary {
    @JsonProperty("app")
    private String app;

    @JsonProperty("page")
    private String page;

    @JsonProperty("totalCases")
    private Integer totalCases;

    @JsonProperty("testScope")
    private String testScope;
}
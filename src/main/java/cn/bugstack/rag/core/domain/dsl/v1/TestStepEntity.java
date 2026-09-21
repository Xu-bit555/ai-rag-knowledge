package cn.bugstack.rag.core.domain.dsl.v1;

import cn.bugstack.rag.core.domain.dsl.v1.enums.StepAction;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * TestStep - DSL 测试步骤
 *
 * 字段语义:
 * - order: 步骤序号(1-based, 必须连续)
 * - action: 动作类型(NAVIGATE / CLICK / INPUT / ...)
 * - target: 元素定位 (CLICK / INPUT / SELECT / ASSERT 等需要)
 * - url: 目标 URL (仅 NAVIGATE 使用, 与 target 二选一)
 * - value: 输入值 (仅 INPUT / SELECT 需要)
 * - expectedResult: 该步预期结果(描述性,非机器断言)
 * - timeoutMs: 步骤超时(默认 5000)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TestStepEntity {
    @JsonProperty("order")
    private Integer order;

    @JsonProperty("action")
    private StepAction action;

    /** 元素定位 (CLICK / INPUT / SELECT / WAIT 等待元素出现 / ASSERT) */
    @JsonProperty("target")
    private TargetLocator target;

    /** 目标 URL (仅 NAVIGATE 使用) */
    @JsonProperty("url")
    private String url;

    @JsonProperty("value")
    private String value;

    @JsonProperty("expectedResult")
    private String expectedResult;

    @JsonProperty("timeoutMs")
    private Integer timeoutMs;
}
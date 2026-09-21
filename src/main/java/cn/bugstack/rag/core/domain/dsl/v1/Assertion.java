package cn.bugstack.rag.core.domain.dsl.v1;

import cn.bugstack.rag.core.domain.dsl.v1.enums.AssertionKind;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Assertion - 测试断言
 *
 * Executor 阶段根据 kind 选择调用 Playwright MCP 的 browser_verify_* 系列:
 * - URL_* → browser_evaluate / browser_snapshot
 * - ELEMENT_VISIBLE → browser_verify_element_visible
 * - ELEMENT_NOT_PRESENT → browser_evaluate
 * - TEXT_* → browser_verify_text_visible / browser_evaluate
 * - VALUE_EQUALS → browser_verify_value
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Assertion {
    @JsonProperty("kind")
    private AssertionKind kind;

    /** 用于定位断言目标元素 (URL/TEXT 类不需要) */
    @JsonProperty("target")
    private TargetLocator target;

    /** 期望值 (URL_EQUALS / TEXT_EQUALS / VALUE_EQUALS 必填) */
    @JsonProperty("expected")
    private String expected;

    /** 反向断言 (默认 false, Phase 2 L1: Boolean → boolean + @Builder.Default) */
    @Builder.Default
    @JsonProperty("negate")
    private boolean negate = false;
}
package cn.bugstack.rag.core.domain.dsl.v1;

import cn.bugstack.rag.core.domain.dsl.v1.enums.LocatorStrategyKind;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * TargetLocator - 元素定位策略
 *
 * 所有字段都可选,根据 strategy 选择性使用:
 * - ROLE    → role + name (+ exact)
 * - TEXT    → text (+ exact)
 * - LABEL   → label
 * - TESTID  → testId
 * - CSS     → selector
 * - XPATH   → xpath
 *
 * Executor 阶段负责根据 strategy 选择性读取对应字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TargetLocator {
    @JsonProperty("strategy")
    private LocatorStrategyKind strategy;

    /** ROLE: ARIA role (button / textbox / link / ...) */
    @JsonProperty("role")
    private String role;

    /** ROLE: accessible name */
    @JsonProperty("name")
    private String name;

    /** TEXT / LABEL: visible text or label text */
    @JsonProperty("text")
    private String text;

    /** LABEL 专用(与 text 等价,语义更明确) */
    @JsonProperty("label")
    private String label;

    /** TESTID: data-testid */
    @JsonProperty("testId")
    private String testId;

    /** CSS: css selector */
    @JsonProperty("selector")
    private String selector;

    /** XPATH: xpath expression */
    @JsonProperty("xpath")
    private String xpath;

    /** ROLE/TEXT: exact match? (默认 false, Phase 2 L1: Boolean → boolean + @Builder.Default) */
    @Builder.Default
    @JsonProperty("exact")
    private boolean exact = false;
}
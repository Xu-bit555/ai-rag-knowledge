package cn.bugstack.rag.core.domain.dsl.v1.enums;

/**
 * 断言类型 - Agent 据此决定调用 Playwright 的 browser_verify_* 系列
 *
 * 故意不加入: SCREENSHOT_MATCHES / STATUS_CODE
 */
public enum AssertionKind {
    URL_EQUALS,
    URL_CONTAINS,
    ELEMENT_VISIBLE,
    ELEMENT_NOT_PRESENT,
    TEXT_EQUALS,
    TEXT_CONTAINS,
    VALUE_EQUALS
}
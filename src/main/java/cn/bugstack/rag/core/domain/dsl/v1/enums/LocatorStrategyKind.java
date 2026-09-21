package cn.bugstack.rag.core.domain.dsl.v1.enums;

/**
 * 元素定位策略 - 映射 Playwright Locator API
 *
 * ROLE → getByRole
 * TEXT → getByText
 * LABEL → getByLabel
 * TESTID → getByTestId
 * CSS → locator(cssSelector)
 * XPATH → locator(xpath=...)
 */
public enum LocatorStrategyKind {
    ROLE,
    TEXT,
    LABEL,
    TESTID,
    CSS,
    XPATH
}
package cn.bugstack.rag.core.domain.dsl.v1.enums;

/**
 * 自动化能力候选 - Agent 据此判断哪些 Case 应该交给 Playwright 执行
 */
public enum AutomationCandidate {
    /** Web 功能测试 - 默认，可由 Playwright 自动化 */
    WEB_FUNCTIONAL,
    /** 人工视觉验证 - Agent 跳过，由人类执行 */
    MANUAL_VISUAL,
    /** 后端 API 测试 - 未来 Phase 8+，MVP 跳过 */
    API,
    /** 完全无法自动化 */
    UNSUPPORTED
}
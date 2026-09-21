package cn.bugstack.rag.core.domain.dsl.v1.enums;

/**
 * 测试步骤动作 - 仅 MVP 必要 7 种 Web 动作
 *
 * 故意不加入: SWIPE / LONG_PRESS / DOUBLE_CLICK / KEY_PRESS / Mobile action
 */
public enum StepAction {
    NAVIGATE,
    CLICK,
    INPUT,
    SELECT,
    WAIT,
    ASSERT,
    SCREENSHOT
}
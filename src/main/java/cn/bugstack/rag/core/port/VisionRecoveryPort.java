package cn.bugstack.rag.core.port;

import java.util.Map;

/**
 * VisionRecoveryPort - Phase 7 接口
 *
 * 用于 DOM/A11y 无法表达的元素恢复(Phase 6 Recovery 失败后的兜底)
 *
 * 当前实现: NONE (仅接口,Phase 7+ 接入 Playwright vision / 外部 CUA)
 */
public interface VisionRecoveryPort {

    /**
     * 通过 screenshot + 视觉模型识别目标坐标
     *
     * @param screenshotPath 截图绝对路径
     * @param intent 用户意图描述(如 "登录按钮")
     * @return {x, y, confidence} 坐标 + 置信度
     */
    Map<String, Object> resolveByVision(String screenshotPath, String intent);
}
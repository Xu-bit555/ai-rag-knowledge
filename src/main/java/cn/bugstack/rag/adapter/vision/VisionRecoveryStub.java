package cn.bugstack.rag.adapter.vision;

import cn.bugstack.rag.core.port.VisionRecoveryPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

/**
 * VisionRecoveryPort 的 no-op 实现 (Phase 7 占位)
 *
 * MVP 阶段 Vision Recovery 不接入真实视觉模型, 只保证:
 *   1. Spring 容器里存在 VisionRecoveryPort bean, SemanticRecoveryService 能装配
 *   2. 调用方无需判空 (返回空 Map, 表示"未识别到坐标")
 *
 * SemanticRecoveryService 中对该端口的调用被 try/catch 包裹且不消费返回值,
 * 因此返回空 Map 不会改变 Recovery 语义 —— 仍会退回到 DOM/A11y 重解析,
 * 最终失败则进入 Human Escalation (永不假 PASS)。
 *
 * Phase 7+ 接入真实实现时, 替换本类为调用 Playwright MCP vision 或外部 CUA 的适配器。
 */
@Slf4j
@Component
public class VisionRecoveryStub implements VisionRecoveryPort {

    @Override
    public Map<String, Object> resolveByVision(String screenshotPath, String intent) {
        log.debug("VisionRecoveryStub: vision recovery not wired in MVP (intent={}, screenshot={})",
                intent, screenshotPath);
        return Collections.emptyMap();
    }
}

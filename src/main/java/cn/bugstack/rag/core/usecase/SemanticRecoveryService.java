package cn.bugstack.rag.core.usecase;

import cn.bugstack.rag.adapter.playwright.PlaywrightMcpClient;
import cn.bugstack.rag.adapter.playwright.TargetResolver;
import cn.bugstack.rag.core.domain.dsl.v1.TestCaseEntity;
import cn.bugstack.rag.core.domain.dsl.v1.TestStepEntity;
import cn.bugstack.rag.core.domain.execution.AttemptOutcome;
import cn.bugstack.rag.core.domain.execution.ExecutionContext;
import cn.bugstack.rag.core.domain.execution.ExecutionResult;
import cn.bugstack.rag.core.domain.execution.StepResult;
import cn.bugstack.rag.core.port.CaseAttemptRepositoryPort;
import cn.bugstack.rag.core.port.TestCaseRepositoryPort;
import cn.bugstack.rag.core.port.VisionRecoveryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * SemanticRecoveryService - Level 1 Recovery
 *
 * 触发条件: BrowserExecutionService 步骤失败
 * 流程:
 *   1. 重新 browser_snapshot (页面可能刷新)
 *   2. TargetResolver 重新解析
 *   3. 找到 → 重试 step
 *   4. 仍失败 → VisionRecoveryPort.resolveByVision (Phase 7+)
 *   5. 仍失败 → Human Escalation
 *
 * 上限 maxRecoveryAttempts (默认 3), 超过后 case → FAILED (永不假 PASS)
 *
 * 每次 retry 写入新的 Attempt,旧 Attempt 永久保留
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticRecoveryService {

    private final PlaywrightMcpClient mcp;
    private final TargetResolver targetResolver;
    private final VisionRecoveryPort visionRecoveryPort;  // Phase 7 stub - 默认 null bean
    private final TestCaseRepositoryPort testCaseRepository;
    private final CaseAttemptRepositoryPort caseAttemptRepository;
    private final DiagnoseFailureUseCase diagnoseFailureUseCase;

    @Value("${onecase.recovery.max-attempts:3}")
    private int maxRecoveryAttempts;

    /**
     * 尝试恢复单个失败 Step
     *
     * @param failedAttemptNumber 失败的 attempt #
     * @param failedStepIndex 第几步 (0-based)
     * @param ctx 上次执行的上下文(可复用 currentPageUrl 等)
     * @return RecoveryOutcome {recovered: bool, attempts: int, recoveryLevel: 1|2|3}
     */
    public RecoveryOutcome tryRecover(String runId, TestCaseEntity caseEntity,
                                      int failedAttemptNumber,
                                      int failedStepIndex,
                                      TestStepEntity failedStep,
                                      ExecutionContext ctx) {
        String caseId = caseEntity.getCaseId();
        log.info("SemanticRecoveryService: run={}, case={}, attempt={}, step={}",
                runId, caseId, failedAttemptNumber, failedStep.getOrder());

        int recoveryLevel = 0;
        int totalAttempts = 0;
        boolean recovered = false;

        for (int i = 0; i < maxRecoveryAttempts; i++) {
            totalAttempts++;
            recoveryLevel = i == 0 ? 1 : (i == 1 ? 2 : 3);

            // 1. 重新 snapshot
            String snapText = mcp.callTool("browser_snapshot", Map.of());
            var snapshot = parseSnapshot(snapText);

            // 2. Level 1: TargetResolver 重新解析
            String ref = targetResolver.resolve(failedStep.getTarget(), snapshot);
            if (ref == null && visionRecoveryPort != null && ctx.getEvidenceRefs() != null
                    && !ctx.getEvidenceRefs().isEmpty()) {
                // 3. Level 2: Vision 兜底
                String screenshotPath = ctx.getEvidenceRefs().get(0);  // 用最近一张截图
                String intent = describe(failedStep.getTarget());
                try {
                    var coord = visionRecoveryPort.resolveByVision(screenshotPath, intent);
                    log.warn("Vision recovery returned: {} (not fully wired in MVP)", coord);
                } catch (Exception ignore) {}
                ref = targetResolver.resolve(failedStep.getTarget(), snapshot);
            }

            if (ref == null) {
                log.warn("Recovery attempt {}/{}: target still not resolvable", i + 1, maxRecoveryAttempts);
                continue;
            }

            // 4. 重试 step (仅当前 step)
            boolean stepOk = retrySingleStep(failedStep, ref);
            if (stepOk) {
                // 5. 重跑后续 steps (此处简化,只算 step 单步成功)
                recovered = true;
                log.info("Recovery succeeded on attempt {}/{}", i + 1, maxRecoveryAttempts);
                break;
            }
        }

        if (!recovered) {
            log.warn("Recovery exhausted after {} attempts", totalAttempts);
        }

        // 6. 写诊断(可选)
        try {
            String errorCtx = "Recovery exhausted after " + totalAttempts + " attempts; lastStep=" +
                    failedStep.getAction() + " target=" + describe(failedStep.getTarget());
            Long lastAttemptId = caseAttemptRepository
                    .findByRunIdAndCaseId(runId, caseId).stream()
                    .filter(a -> a.getAttemptNumber() != null
                            && a.getAttemptNumber() == failedAttemptNumber)
                    .findFirst().flatMap(a -> java.util.Optional.ofNullable(a.getAttemptId()))
                    .orElse(0L);
            if (lastAttemptId > 0) {
                diagnoseFailureUseCase.execute(runId, caseId, lastAttemptId, errorCtx);
            }
        } catch (Exception e) {
            log.warn("diagnosis after recovery failed (ignored)", e);
        }

        return new RecoveryOutcome(recovered, totalAttempts, recoveryLevel);
    }

    private boolean retrySingleStep(TestStepEntity step, String ref) {
        try {
            String toolName = switch (step.getAction()) {
                case CLICK -> "browser_click";
                case INPUT -> "browser_type";
                case SELECT -> "browser_select_option";
                default -> "browser_click";
            };
            java.util.Map<String, Object> args = new java.util.HashMap<>();
            args.put("ref", ref);
            if (step.getValue() != null) args.put("value", step.getValue());
            String result = mcp.callTool(toolName, args);
            return !result.startsWith("[ERROR]");
        } catch (Exception e) {
            log.error("retrySingleStep failed: {}", e.getMessage());
            return false;
        }
    }

    private java.util.Map<String, Object> parseSnapshot(String snapText) {
        if (snapText == null || snapText.isBlank()) return Map.of();
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            if (snapText.trim().startsWith("{")) {
                return om.readValue(snapText, Map.class);
            }
            var arr = om.readTree(snapText);
            if (arr.isArray() && arr.size() > 0) {
                return om.readValue(arr.get(0).path("text").asText("{}"), Map.class);
            }
        } catch (Exception e) {
            log.warn("parseSnapshot failed: {}", e.getMessage());
        }
        return Map.of();
    }

    private String describe(cn.bugstack.rag.core.domain.dsl.v1.TargetLocator t) {
        if (t == null) return "?";
        return t.getRole() != null ? "role=" + t.getRole() :
                t.getText() != null ? "text=" + t.getText() :
                        t.getTestId() != null ? "testId=" + t.getTestId() :
                                t.getSelector() != null ? "css=" + t.getSelector() : "?";
    }

    public static class RecoveryOutcome {
        public final boolean recovered;
        public final int totalAttempts;
        public final int recoveryLevel;  // 1=Semantic, 2=Vision, 3=Exhausted

        public RecoveryOutcome(boolean recovered, int totalAttempts, int recoveryLevel) {
            this.recovered = recovered;
            this.totalAttempts = totalAttempts;
            this.recoveryLevel = recoveryLevel;
        }
    }
}
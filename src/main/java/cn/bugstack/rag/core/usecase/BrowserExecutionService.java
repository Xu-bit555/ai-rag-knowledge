package cn.bugstack.rag.core.usecase;

import cn.bugstack.rag.adapter.playwright.PlaywrightMcpClient;
import cn.bugstack.rag.adapter.playwright.TargetResolver;
import cn.bugstack.rag.core.domain.dsl.v1.Assertion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.bugstack.rag.core.domain.dsl.v1.CanonicalTestCase;
import cn.bugstack.rag.core.domain.dsl.v1.TestCaseEntity;
import cn.bugstack.rag.core.domain.dsl.v1.TestStepEntity;
import cn.bugstack.rag.core.domain.dsl.v1.enums.AssertionKind;
import cn.bugstack.rag.core.domain.dsl.v1.enums.StepAction;
import cn.bugstack.rag.core.domain.execution.AttemptOutcome;
import cn.bugstack.rag.core.domain.execution.ExecutionContext;
import cn.bugstack.rag.core.domain.execution.ExecutionResult;
import cn.bugstack.rag.core.domain.execution.StepResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BrowserExecutionService - 执行单个 CanonicalTestCase via Playwright MCP
 *
 * 流程:
 * 1. 对每条 DSL step:
 *    - 若 action=NAVIGATE → browser_navigate(url)
 *    - 若 action 需要 target → 先 browser_snapshot → TargetResolver 找 ref
 *    - browser_click / browser_type 用 ref
 *    - ASSERT 类 → browser_verify_*
 * 2. 对每个 case assertion:
 *    - ELEMENT_VISIBLE → browser_verify_element_visible(ref)
 *    - TEXT_CONTAINS → browser_verify_text_visible(text)
 *    - URL_* → browser_evaluate("location.href ...")
 * 3. 收集 StepResult + evidenceRefs
 *
 * 最终判定:
 * - 所有 step PASS + 所有 assertion PASS → PASSED
 * - 任何 step FAIL → FAILED
 * - 关键元素无法 resolve → FAILED (不假装成功)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrowserExecutionService {

    private final PlaywrightMcpClient mcp;
    private final TargetResolver targetResolver;
    private final ArtifactService artifactService;

    /**
     * 执行一个 Canonical DSL 下的单个 Case
     *
     * @param runId TestRun ID
     * @param caseEntity 来自 rag_test_case 的 TestCaseEntity
     * @param attemptNumber 1, 2, ...
     * @return ExecutionResult (含 steps + finalOutcome + evidenceRefs)
     */
    public ExecutionResult executeCase(String runId, TestCaseEntity caseEntity, int attemptNumber) {
        String caseId = caseEntity.getCaseId();
        long start = System.currentTimeMillis();
        ExecutionContext ctx = ExecutionContext.builder()
                .runId(runId)
                .caseId(caseId)
                .attemptNumber(attemptNumber)
                .startTimeMs(start)
                .build();

        log.info("BrowserExecutionService.executeCase: run={}, case={}, attempt={}", runId, caseId, attemptNumber);

        // 1. 执行 steps
        boolean allStepPassed = true;
        if (caseEntity.getSteps() != null) {
            for (TestStepEntity step : caseEntity.getSteps()) {
                StepResult sr = executeStep(ctx, step);
                ctx.addStep(sr);
                if (sr.getPassed() == null || !sr.getPassed()) {
                    allStepPassed = false;
                }
            }
        }

        // 2. 执行 assertions
        boolean allAssertionPassed = true;
        if (caseEntity.getAssertions() != null) {
            for (Assertion a : caseEntity.getAssertions()) {
                StepResult ar = executeAssertion(ctx, a);
                ctx.addStep(ar);
                if (ar.getPassed() == null || !ar.getPassed()) {
                    allAssertionPassed = false;
                }
            }
        }

        ctx.setEndTimeMs(System.currentTimeMillis());
        long duration = ctx.getEndTimeMs() - start;

        AttemptOutcome outcome = (allStepPassed && allAssertionPassed) ? AttemptOutcome.PASSED : AttemptOutcome.FAILED;

        String errorSummary = null;
        if (outcome == AttemptOutcome.FAILED) {
            errorSummary = ctx.getSteps().stream()
                    .filter(s -> s.getPassed() == null || !s.getPassed())
                    .findFirst()
                    .map(s -> "Step " + s.getOrder() + " " + s.getAction() + " failed: " + s.getErrorMessage())
                    .orElse("Unknown failure");
        }

        ExecutionResult result = ExecutionResult.builder()
                .steps(ctx.getSteps())
                .finalOutcome(outcome)
                .evidenceRefs(ctx.getEvidenceRefs())
                .errorSummary(errorSummary)
                .durationMs(duration)
                .recoveryLevel(0)
                .build();
        log.info("BrowserExecutionService.executeCase done: outcome={}, steps={}, evidence={}",
                outcome, ctx.getSteps().size(), ctx.getEvidenceRefs().size());
        return result;
    }

    /**
     * 执行单条 step
     */
    private StepResult executeStep(ExecutionContext ctx, TestStepEntity step) {
        StepAction action = step.getAction();
        long stepStart = System.currentTimeMillis();
        String actualResult;
        Boolean passed = false;
        String errorMessage = null;
        String artifactId = null;
        String resolvedRef = null;

        try {
            if (action == StepAction.NAVIGATE) {
                actualResult = mcp.callTool("browser_navigate", Map.of("url", step.getUrl()));
                passed = !actualResult.startsWith("[ERROR]");
                ctx.setCurrentPageUrl(step.getUrl());
                // 截图证据
                artifactId = takeScreenshot(ctx, step.getOrder());
            } else if (action == StepAction.WAIT) {
                int ms = step.getTimeoutMs() != null ? step.getTimeoutMs() : 1000;
                Thread.sleep(ms);
                actualResult = "waited " + ms + "ms";
                passed = true;
            } else if (action == StepAction.SCREENSHOT) {
                artifactId = takeScreenshot(ctx, step.getOrder());
                actualResult = "screenshot saved";
                passed = artifactId != null;
            } else if (action == StepAction.CLICK || action == StepAction.INPUT
                    || action == StepAction.SELECT) {
                String snapshotText = mcp.callTool("browser_snapshot", Map.of());
                Map<String, Object> snapshot = parseSnapshot(snapshotText);
                ctx.setCurrentPageUrl(currentPageUrl(snapshot));
                resolvedRef = targetResolver.resolve(step.getTarget(), snapshot);

                if (resolvedRef == null && (action == StepAction.CLICK || action == StepAction.INPUT)) {
                    // Snapshot 没找到 → 重新 snapshot 一次(可能页面加载未完成)
                    Thread.sleep(500);
                    snapshotText = mcp.callTool("browser_snapshot", Map.of());
                    snapshot = parseSnapshot(snapshotText);
                    resolvedRef = targetResolver.resolve(step.getTarget(), snapshot);
                }

                if (resolvedRef == null) {
                    passed = false;
                    errorMessage = "Cannot resolve target: " + describeTarget(step.getTarget());
                    actualResult = "[ERROR] target not found";
                } else {
                    String toolName = switch (action) {
                        case CLICK -> "browser_click";
                        case INPUT -> "browser_type";
                        case SELECT -> "browser_select_option";
                        default -> "browser_click";
                    };
                    Map<String, Object> args = new HashMap<>();
                    args.put("ref", resolvedRef);
                    if (action == StepAction.INPUT || action == StepAction.SELECT) {
                        args.put("value", step.getValue());
                    }
                    actualResult = mcp.callTool(toolName, args);
                    passed = !actualResult.startsWith("[ERROR]");
                    if (!passed) errorMessage = actualResult;
                }
                // step 后自动截图
                if (passed) artifactId = takeScreenshot(ctx, step.getOrder());
            } else if (action == StepAction.ASSERT) {
                // ASSERT 作为独立 step 通常无需;放在 case-level assertions 中处理
                actualResult = "ASSERT handled in case-level assertions";
                passed = true;
            } else {
                actualResult = "[UNKNOWN ACTION] " + action;
                passed = false;
                errorMessage = "Unsupported action: " + action;
            }
        } catch (Exception e) {
            log.error("step {} failed: {}", step.getOrder(), e.getMessage());
            actualResult = "[EXCEPTION] " + e.getMessage();
            errorMessage = e.getMessage();
        }

        long dur = System.currentTimeMillis() - stepStart;
        return StepResult.builder()
                .order(step.getOrder())
                .action(action != null ? action.name() : null)
                .targetDescription(describeTarget(step.getTarget()))
                .resolvedRef(resolvedRef)
                .actualResult(actualResult)
                .passed(passed)
                .errorMessage(errorMessage)
                .durationMs(dur)
                .artifactId(artifactId)
                .build();
    }

    /**
     * 执行 case-level assertion
     */
    private StepResult executeAssertion(ExecutionContext ctx, Assertion a) {
        long start = System.currentTimeMillis();
        Boolean passed = false;
        String actualResult;
        String errorMessage = null;

        try {
            AssertionKind kind = a.getKind();
            switch (kind) {
                case ELEMENT_VISIBLE -> {
                    String snapshotText = mcp.callTool("browser_snapshot", Map.of());
                    Map<String, Object> snapshot = parseSnapshot(snapshotText);
                    String ref = targetResolver.resolve(a.getTarget(), snapshot);
                    actualResult = mcp.callTool("browser_verify_element_visible",
                            Map.of("ref", ref != null ? ref : ""));
                    passed = !actualResult.toLowerCase().contains("[error]") && !actualResult.toLowerCase().contains("fail");
                }
                case ELEMENT_NOT_PRESENT -> {
                    String snapshotText = mcp.callTool("browser_snapshot", Map.of());
                    Map<String, Object> snapshot = parseSnapshot(snapshotText);
                    String ref = targetResolver.resolve(a.getTarget(), snapshot);
                    passed = (ref == null);
                    actualResult = passed ? "element not present" : "element still present: " + ref;
                }
                case TEXT_CONTAINS -> {
                    actualResult = mcp.callTool("browser_verify_text_visible",
                            Map.of("text", a.getExpected()));
                    passed = !actualResult.toLowerCase().contains("[error]") && !actualResult.toLowerCase().contains("fail");
                }
                case TEXT_EQUALS -> {
                    String snapshotText = mcp.callTool("browser_snapshot", Map.of());
                    Map<String, Object> snapshot = parseSnapshot(snapshotText);
                    String actualText = extractPageText(snapshot);
                    passed = a.getExpected() != null && actualText.contains(a.getExpected());
                    actualResult = "page text contains: " + actualText.substring(0, Math.min(100, actualText.length()));
                }
                case URL_CONTAINS -> {
                    actualResult = mcp.callTool("browser_evaluate",
                            Map.of("function", "() => location.href"));
                    passed = a.getExpected() != null && actualResult.contains(a.getExpected());
                }
                case URL_EQUALS -> {
                    actualResult = mcp.callTool("browser_evaluate",
                            Map.of("function", "() => location.href"));
                    passed = a.getExpected() != null && actualResult.trim().contains(a.getExpected().trim());
                }
                case VALUE_EQUALS -> {
                    String ref = null;
                    if (a.getTarget() != null) {
                        String snapshotText = mcp.callTool("browser_snapshot", Map.of());
                        Map<String, Object> snapshot = parseSnapshot(snapshotText);
                        ref = targetResolver.resolve(a.getTarget(), snapshot);
                    }
                    actualResult = mcp.callTool("browser_verify_value",
                            Map.of("ref", ref != null ? ref : "", "value", a.getExpected()));
                    passed = !actualResult.toLowerCase().contains("[error]") && !actualResult.toLowerCase().contains("fail");
                }
                default -> {
                    actualResult = "[UNSUPPORTED] " + kind;
                    errorMessage = "Unsupported assertion kind";
                }
            }
        } catch (Exception e) {
            actualResult = "[EXCEPTION] " + e.getMessage();
            errorMessage = e.getMessage();
        }

        return StepResult.builder()
                .order(9000 + ctx.getSteps().size())  // assertion order after steps
                .action("ASSERT:" + a.getKind())
                .targetDescription("expected: " + a.getExpected())
                .actualResult(actualResult)
                .passed(passed)
                .errorMessage(errorMessage)
                .durationMs(System.currentTimeMillis() - start)
                .build();
    }

    /**
     * 截图 + 上传到 OneCase Artifact
     */
    private String takeScreenshot(ExecutionContext ctx, int stepOrder) {
        try {
            String filename = ctx.getRunId() + "-" + ctx.getCaseId() + "-step" + stepOrder + ".png";
            String rawResult = mcp.callTool("browser_take_screenshot", Map.of("filename", filename));

            // Playwright MCP 可能返回文件路径(若配置 --output-dir)或 base64
            String filePath = extractScreenshotPath(rawResult);

            if (filePath != null && java.nio.file.Files.exists(java.nio.file.Paths.get(filePath))) {
                // 上传到 OneCase
                var artifact = artifactService.upload(
                        ctx.getRunId(), ctx.getCaseId(), ctx.getAttemptNumber(),
                        stepOrder, "SCREENSHOT",
                        fileToMultipart(filePath, filename));
                return artifact.getArtifactId();
            }
            log.warn("Screenshot file not accessible: {}", filePath);
            return null;
        } catch (Exception e) {
            log.error("takeScreenshot failed: {}", e.getMessage());
            return null;
        }
    }

    private org.springframework.web.multipart.MultipartFile fileToMultipart(String filePath, String filename) throws java.io.IOException {
        byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath));
        return new SimpleInMemoryMultipartFile(filename, "image/png", bytes);
    }

    /**
     * 简易 in-memory MultipartFile (避免依赖 spring-test)
     */
    static class SimpleInMemoryMultipartFile implements org.springframework.web.multipart.MultipartFile {
        private final String name;
        private final String contentType;
        private final byte[] bytes;

        SimpleInMemoryMultipartFile(String name, String contentType, byte[] bytes) {
            this.name = name;
            this.contentType = contentType;
            this.bytes = bytes;
        }

        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return name; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return bytes == null || bytes.length == 0; }
        @Override public long getSize() { return bytes == null ? 0 : bytes.length; }
        @Override public byte[] getBytes() { return bytes; }
        @Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(bytes); }
        @Override public void transferTo(java.io.File dest) throws java.io.IOException, IllegalStateException {
            java.nio.file.Files.write(dest.toPath(), bytes);
        }
    }

    private String extractScreenshotPath(String raw) {
        if (raw == null) return null;
        // 常见格式: {"path":"/path/to/file.png"} 或 "Saved to /path"
        try {
            if (raw.contains("\"path\"")) {
                int i = raw.indexOf("\"path\"");
                int colon = raw.indexOf(':', i);
                int q1 = raw.indexOf('"', colon);
                int q2 = raw.indexOf('"', q1 + 1);
                return raw.substring(q1 + 1, q2);
            }
        } catch (Exception ignore) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSnapshot(String snapshotText) {
        if (snapshotText == null || snapshotText.isBlank()) return Map.of();
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            // snapshot 返回可能是嵌套 text/JSON 字符串
            if (snapshotText.trim().startsWith("{")) {
                return om.readValue(snapshotText, Map.class);
            }
            // wrapped: [{"type":"text","text":"{...}"}]
            JsonNode arr = om.readTree(snapshotText);
            if (arr.isArray() && arr.size() > 0) {
                return om.readValue(arr.get(0).path("text").asText("{}"), Map.class);
            }
        } catch (Exception e) {
            log.warn("parseSnapshot failed: {}", e.getMessage());
        }
        return Map.of();
    }

    private String currentPageUrl(Map<String, Object> snapshot) {
        Object url = snapshot.get("url");
        if (url == null) url = snapshot.get("pageUrl");
        return url != null ? url.toString() : null;
    }

    @SuppressWarnings("unchecked")
    private String extractPageText(Map<String, Object> snapshot) {
        StringBuilder sb = new StringBuilder();
        walkText(snapshot, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void walkText(Map<String, Object> node, StringBuilder sb) {
        if (node == null) return;
        Object name = node.get("name");
        if (name != null) sb.append(name).append(" ");
        Object children = node.get("children");
        if (children instanceof List<?> list) {
            for (Object c : list) if (c instanceof Map<?, ?> m) walkText((Map<String, Object>) m, sb);
        }
    }

    private String describeTarget(cn.bugstack.rag.core.domain.dsl.v1.TargetLocator t) {
        if (t == null) return null;
        return String.format("%s=%s", t.getStrategy() != null ? t.getStrategy().name() : "?",
                t.getRole() != null ? t.getRole() :
                        t.getText() != null ? t.getText() :
                                t.getTestId() != null ? t.getTestId() :
                                        t.getSelector() != null ? t.getSelector() : "?");
    }
}
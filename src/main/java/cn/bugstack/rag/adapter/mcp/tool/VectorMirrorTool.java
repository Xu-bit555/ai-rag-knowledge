package cn.bugstack.rag.adapter.mcp.tool;

import cn.bugstack.rag.model.response.Response;
import cn.bugstack.rag.service.VectorMirrorRetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Phase 3: 向量镜像 DLQ MCP Tool (admin 视角)
 *
 * - onecase_get_dlq_stats: 看 DLQ 长度 (告警决策依据)
 * - onecase_retry_dlq: 手动 retry 单条 / 批量
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorMirrorTool {

    private final VectorMirrorRetryService vectorMirrorRetryService;

    @Tool(name = "onecase_get_dlq_stats",
            description = "Get current stats of the vector mirror DLQ (rag:vector:dlq). "
                    + "Returns dlqStreamKey + size + recentCount. Use to monitor embedding API failures "
                    + "and decide whether to call onecase_retry_dlq. A non-zero size indicates pending "
                    + "vector mirror tasks that need manual retry or root-cause investigation.")
    public Map<String, Object> getDlqStats() {
        log.info("MCP onecase_get_dlq_stats called");
        Response<Map<String, Object>> r = vectorMirrorRetryService.getStats();
        Map<String, Object> data = r.getData();
        return Map.of(
                "code", r.getCode(),
                "info", r.getInfo(),
                "dlqStreamKey", data != null ? data.get("dlqStreamKey") : "",
                "size", data != null ? data.get("size") : 0L,
                "recentCount", data != null ? data.get("recentCount") : 0
        );
    }

    @Tool(name = "onecase_retry_dlq",
            description = "Retry vector mirror DLQ entries. Pass caseId to retry one case, "
                    + "or pass retryAll=true to retry all DLQ entries (max 1000). "
                    + "Re-dispatched entries will be processed by VectorMirrorConsumer; "
                    + "successful cases will get a fresh spring_ai_vectors row.")
    public Map<String, Object> retryDlq(
            @ToolParam(description = "Case ID to retry (omit if retryAll=true)", required = false) String caseId,
            @ToolParam(description = "Set true to retry all DLQ entries", required = false) Boolean retryAll) {

        log.info("MCP onecase_retry_dlq: caseId={}, retryAll={}", caseId, retryAll);

        if (retryAll != null && retryAll) {
            Response<Integer> r = vectorMirrorRetryService.retryAll();
            return Map.of("code", r.getCode(), "info", r.getInfo(), "retriedCount", r.getData());
        } else if (caseId != null && !caseId.isBlank()) {
            Response<String> r = vectorMirrorRetryService.retry(caseId);
            return Map.of("code", r.getCode(), "info", r.getInfo());
        } else {
            return Map.of("code", "400", "info", "Provide either caseId or retryAll=true");
        }
    }
}

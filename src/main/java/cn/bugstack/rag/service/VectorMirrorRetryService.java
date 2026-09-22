package cn.bugstack.rag.service;

import cn.bugstack.rag.adapter.persistence.VectorMirrorDispatcher;
import cn.bugstack.rag.config.RedisStreamConfigProperties;
import cn.bugstack.rag.core.domain.dsl.v1.TestCaseEntity;
import cn.bugstack.rag.model.response.Response;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Phase 3: 向量镜像 DLQ 重试服务
 *
 * 用法:
 *   POST /api/v1/knowledge/vector/retry?caseId=xxx
 *   POST /api/v1/knowledge/vector/retry-all
 *   GET  /api/v1/knowledge/vector/dlq-stats
 *   MCP onecase_get_dlq_stats
 *   MCP onecase_retry_dlq
 *
 * 复用 Phase 1 IngestRetryService 模式 (range DLQ → re-add 主 stream → 移除 DLQ entry)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorMirrorRetryService {

    private static final int DLQ_SCAN_LIMIT = 1000;

    private final RedissonClient redissonClient;
    private final RedisStreamConfigProperties streamConfig;
    private final ObjectMapper objectMapper;
    private final VectorMirrorDispatcher dispatcher;

    /** 重试单条 case: 从 DLQ 重新派发到主 stream */
    public Response<String> retry(String caseId) {
        RStream<String, String> dlq = redissonClient.getStream(streamConfig.getVectorDlqStreamKey());

        for (var e : dlq.range(DLQ_SCAN_LIMIT, StreamMessageId.MIN, StreamMessageId.MAX).entrySet()) {
            Map<String, String> v = e.getValue();
            if (caseId.equals(v.get("caseId"))) {
                try {
                    String ragTag = v.get("ragTag");
                    String dslJson = v.get("dslJson");
                    TestCaseEntity tc = objectMapper.readValue(dslJson, TestCaseEntity.class);
                    boolean ok = dispatcher.dispatch(ragTag, caseId, tc);
                    dlq.remove(e.getKey());
                    if (ok) {
                        log.info("Vector mirror DLQ retry: caseId={}", caseId);
                        return Response.ok("Case " + caseId + " re-dispatched to vector mirror stream");
                    } else {
                        return Response.error("500", "Re-dispatch failed (still in DLQ)");
                    }
                } catch (Exception ex) {
                    return Response.error("500", "Re-dispatch exception: " + ex.getMessage());
                }
            }
        }
        return Response.error("404", "CaseId not found in DLQ: " + caseId);
    }

    /** 批量重试: 遍历整个 DLQ, 全部重派发 */
    public Response<Integer> retryAll() {
        RStream<String, String> dlq = redissonClient.getStream(streamConfig.getVectorDlqStreamKey());
        int count = 0;
        for (var e : dlq.range(DLQ_SCAN_LIMIT, StreamMessageId.MIN, StreamMessageId.MAX).entrySet()) {
            String caseId = e.getValue().get("caseId");
            if (caseId == null) continue;
            Response<String> r = retry(caseId);
            if ("200".equals(r.getCode())) count++;
        }
        log.info("Vector mirror DLQ batch retry: {} cases", count);
        return Response.ok(count);
    }

    /** DLQ 统计 */
    public Response<Map<String, Object>> getStats() {
        RStream<String, String> dlq = redissonClient.getStream(streamConfig.getVectorDlqStreamKey());
        long size = dlq.size();
        var recent = dlq.range(5, StreamMessageId.MIN, StreamMessageId.MAX);
        return Response.ok(Map.of(
                "dlqStreamKey", streamConfig.getVectorDlqStreamKey(),
                "size", size,
                "recentCount", recent.size()
        ));
    }
}

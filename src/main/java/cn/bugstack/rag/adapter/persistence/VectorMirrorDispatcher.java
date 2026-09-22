package cn.bugstack.rag.adapter.persistence;

import cn.bugstack.rag.config.RedisStreamConfigProperties;
import cn.bugstack.rag.core.domain.dsl.v1.TestCaseEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RList;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 3: 向量镜像派发器
 *
 * 把 adopt 成功的 case 异步推送到 rag:vector:mirror stream,
 * 由 VectorMirrorConsumer 异步调 embedding + 写 spring_ai_vectors.
 *
 * 设计要点:
 *   1. fire-and-forget: 派发失败不抛异常 (仅 log error), 不影响 adopt 返回
 *   2. 三级降级: 主 stream → fallback list → DLQ
 *      - 主 stream: 正常路径
 *      - fallback list: Redis 临时不可用时落地, 启动时由 Consumer 一次性补偿
 *      - DLQ: payload 序列化失败 / Redis 完全不可用, 供人工排查
 *   3. payload 是纯 String (caseId + ragTag + JSON DSL), consumer 端不依赖外部 schema
 *
 * P0-1 (双源真相分裂修复) 语义保留: 正常路径下结构表 + 向量表最终一致
 * (允许 < 1s 异步延迟); 异常路径下结构表优先, 向量后续补偿.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorMirrorDispatcher {

    private static final String FALLBACK_LIST_KEY = "rag:vector:fallback";

    private final RedissonClient redissonClient;
    private final RedisStreamConfigProperties streamConfig;
    private final ObjectMapper objectMapper;

    /**
     * 派发单条 case 到向量镜像 stream
     *
     * @return true = 已入主 stream; false = 派发失败 (fallback 或 DLQ, 不影响 adopt 返回)
     */
    public boolean dispatch(String ragTag, String stableId, TestCaseEntity caseEntity) {
        String dslJson;
        try {
            dslJson = objectMapper.writeValueAsString(caseEntity);
        } catch (JsonProcessingException e) {
            log.error("Vector mirror dispatch: JSON serialize failed for case={}, ragTag={}. " +
                    "Case committed to rag_test_case but vector mirror dropped.",
                    stableId, ragTag, e);
            writeToDlq(ragTag, stableId, "JSON serialize failed: " + e.getMessage(), "");
            return false;
        }

        // 1. 尝试入主 stream
        try {
            RStream<String, String> stream = redissonClient.getStream(streamConfig.getVectorMirrorStreamKey());
            stream.add(StreamAddArgs.<String, String>entries(
                    "ragTag", ragTag != null ? ragTag : "",
                    "caseId", stableId,
                    "dslJson", dslJson,
                    "retryCount", "0",
                    "dispatchedAt", String.valueOf(System.currentTimeMillis())
            ));
            log.info("Vector mirror dispatched: ragTag={}, caseId={}", ragTag, stableId);
            return true;
        } catch (Exception streamEx) {
            log.error("Vector mirror stream add failed for case={}, falling back to local list",
                    stableId, streamEx);
        }

        // 2. 主 stream 不可用 → 入 fallback list (Consumer 启动时扫描补偿)
        try {
            RList<String> fallback = redissonClient.getList(FALLBACK_LIST_KEY);
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("ragTag", ragTag != null ? ragTag : "");
            payload.put("caseId", stableId);
            payload.put("dslJson", dslJson);
            fallback.add(objectMapper.writeValueAsString(payload));
            log.warn("Vector mirror fallback list add: caseId={}", stableId);
            return false;
        } catch (Exception fbEx) {
            log.error("Vector mirror fallback list also failed for case={}. DLQ it.",
                    stableId, fbEx);
            writeToDlq(ragTag, stableId, "Dispatch failed: " + fbEx.getMessage(), dslJson);
            return false;
        }
    }

    private void writeToDlq(String ragTag, String caseId, String error, String dslJson) {
        try {
            RStream<String, String> dlq = redissonClient.getStream(streamConfig.getVectorDlqStreamKey());
            dlq.add(StreamAddArgs.<String, String>entries(
                    "ragTag", ragTag != null ? ragTag : "",
                    "caseId", caseId,
                    "dslJson", dslJson,
                    "errorMessage", error,
                    "failedAt", String.valueOf(System.currentTimeMillis())
            ));
            log.warn("Vector mirror DLQ write: caseId={}, error={}", caseId, error);
        } catch (Exception e) {
            log.error("Vector mirror DLQ write failed for case={}", caseId, e);
        }
    }
}

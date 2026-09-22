package cn.bugstack.rag.infrastructure.messaging;

import cn.bugstack.rag.config.RedisStreamConfigProperties;
import cn.bugstack.rag.core.domain.dsl.v1.TestCaseEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.RedisBusyException;
import org.redisson.client.RedisException;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phase 3: 向量镜像 Consumer
 *
 * 消费 rag:vector:mirror → 调 embedding (P0-12 fail-fast) → 写 spring_ai_vectors
 * 失败重试 maxRetry 次 → 入 rag:vector:dlq
 *
 * 关键不变量 (P0-1 修复保护):
 *   - 写 vector 前先查 metadata->>'caseId' 是否已存在 (V6 索引加速)
 *   - 已存在则 skip + ack (幂等, 防重复消费)
 *   - Semaphore 限流 (默认 4 并发), 防 SiliconFlow QPS 限流
 *
 * 重试策略:
 *   - 失败 N 次 (N < maxRetry): 退避 (N * baseBackoff ms) 后重新入队
 *   - 失败 maxRetry 次: 入 rag:vector:dlq, 供 VectorMirrorRetryService 手动 retry
 *
 * 启动补偿:
 *   - 启动时扫描 rag:vector:fallback list (dispatcher 失败时写入), 全部转回主 stream
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorMirrorConsumer {

    private static final String FALLBACK_LIST_KEY = "rag:vector:fallback";

    private final RedissonClient redissonClient;
    private final RedisStreamConfigProperties streamConfig;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final VectorStore vectorStore;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread workerThread;
    private Semaphore concurrencyLimiter;

    @PostConstruct
    public void start() {
        this.concurrencyLimiter = new Semaphore(streamConfig.getVectorDispatchConcurrency());

        // 1. 创建 consumer group
        RStream<String, String> stream = redissonClient.getStream(streamConfig.getVectorMirrorStreamKey());
        try {
            stream.createGroup(StreamCreateGroupArgs.name(streamConfig.getVectorMirrorGroup()).makeStream());
        } catch (RedisBusyException ignored) {
            log.info("Vector mirror stream group 已存在");
        } catch (RedisException e) {
            log.debug("createGroup: {}", e.getMessage());
        }

        // 2. 启动时一次性扫描 fallback list → 转回主 stream
        scanFallbackList();

        // 3. 启动消费线程
        workerThread = new Thread(this::consumeLoop, "vector-mirror-consumer");
        workerThread.setDaemon(true);
        workerThread.start();

        log.info("Vector mirror consumer 已启动 (concurrency={})",
                streamConfig.getVectorDispatchConcurrency());
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (workerThread != null) workerThread.interrupt();
    }

    private void scanFallbackList() {
        try {
            org.redisson.api.RList<String> fallback = redissonClient.getList(FALLBACK_LIST_KEY);
            int size = fallback.size();
            if (size == 0) return;
            log.info("Vector mirror fallback list 启动补偿: {} 条", size);

            RStream<String, String> stream = redissonClient.getStream(streamConfig.getVectorMirrorStreamKey());
            for (int i = 0; i < size; i++) {
                String json = fallback.remove(0);
                Map<String, String> payload = objectMapper.readValue(json, Map.class);
                stream.add(org.redisson.api.stream.StreamAddArgs.<String, String>entries(
                        "ragTag", payload.getOrDefault("ragTag", ""),
                        "caseId", payload.getOrDefault("caseId", ""),
                        "dslJson", payload.getOrDefault("dslJson", ""),
                        "retryCount", "0",
                        "dispatchedAt", String.valueOf(System.currentTimeMillis())));
            }
            log.info("Vector mirror fallback list 补偿完成: {} 条已转回主 stream", size);
        } catch (Exception e) {
            log.warn("Vector mirror fallback list 启动补偿失败 (不影响主流程): {}", e.getMessage());
        }
    }

    private void consumeLoop() {
        RStream<String, String> stream = redissonClient.getStream(streamConfig.getVectorMirrorStreamKey());
        String group = streamConfig.getVectorMirrorGroup();
        String consumer = streamConfig.getVectorMirrorConsumer();

        while (running.get()) {
            try {
                Map<StreamMessageId, Map<String, String>> messages = stream.readGroup(
                        group, consumer,
                        StreamReadGroupArgs.neverDelivered().count(1).timeout(Duration.ofSeconds(5))
                );
                if (messages == null || messages.isEmpty()) continue;

                for (Map.Entry<StreamMessageId, Map<String, String>> entry : messages.entrySet()) {
                    processMessage(entry.getKey(), entry.getValue(), stream, group);
                }
            } catch (RedisException e) {
                log.warn("Vector mirror Redis 异常: {}", e.getMessage());
                sleep(1000);
            } catch (Exception e) {
                log.error("Vector mirror 消费异常", e);
                sleep(1000);
            }
        }
    }

    private void processMessage(StreamMessageId id, Map<String, String> body,
                                RStream<String, String> stream, String group) {
        String ragTag = body.get("ragTag");
        String caseId = body.get("caseId");
        String dslJson = body.get("dslJson");
        int retryCount = Integer.parseInt(body.getOrDefault("retryCount", "0"));

        // 1. Idempotency check (V6 索引加速)
        if (isVectorMirrorExists(caseId)) {
            log.info("Vector mirror 已存在 (幂等跳过): caseId={}", caseId);
            stream.ack(group, id);
            return;
        }

        // 2. Semaphore 限流
        try {
            concurrencyLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        Exception businessEx = null;
        try {
            TestCaseEntity caseEntity = objectMapper.readValue(dslJson, TestCaseEntity.class);
            String title = caseEntity.getTitle() != null ? caseEntity.getTitle() : "";
            String outcome = caseEntity.getExpectedOutcome() != null
                    ? caseEntity.getExpectedOutcome() : "";
            String embeddingText = title + "\n" + outcome;

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("knowledge", ragTag);
            meta.put("type", "test_case");
            meta.put("adoptionStatus", "ADOPTED");
            meta.put("caseId", caseId);
            meta.put("sourceDoc", caseEntity.getCaseId());

            // P0-12 fail-fast: API Key 缺失 / 402 / 全 0 向量 → IllegalStateException
            Document doc = new Document(UUID.randomUUID().toString(), embeddingText, meta);
            vectorStore.accept(List.of(doc));
        } catch (Exception e) {
            businessEx = e;
        } finally {
            concurrencyLimiter.release();
        }

        // 3. 失败处理: 重试 or DLQ
        if (businessEx == null) {
            stream.ack(group, id);
            log.info("Vector mirror done: caseId={}", caseId);
        } else {
            handleFailure(id, body, stream, group, businessEx, retryCount);
        }
    }

    private void handleFailure(StreamMessageId id, Map<String, String> body,
                               RStream<String, String> stream, String group,
                               Exception businessEx, int retryCount) {
        String ragTag = body.get("ragTag");
        String caseId = body.get("caseId");
        String dslJson = body.get("dslJson");

        int maxRetry = streamConfig.getVectorMaxRetry();
        if (retryCount < maxRetry) {
            // 退避后重新入队
            long backoff = streamConfig.getVectorRetryBackoffMs() * (retryCount + 1);
            sleep(backoff);
            try {
                stream.add(org.redisson.api.stream.StreamAddArgs.<String, String>entries(
                        "ragTag", ragTag,
                        "caseId", caseId,
                        "dslJson", dslJson != null ? dslJson : "",
                        "retryCount", String.valueOf(retryCount + 1),
                        "dispatchedAt", String.valueOf(System.currentTimeMillis())));
                stream.ack(group, id);
                log.warn("Vector mirror 重试 {}/{}: caseId={}, err={}",
                        retryCount + 1, maxRetry, caseId, businessEx.getMessage());
            } catch (Exception e) {
                log.error("Vector mirror re-add failed for case={}, falling through to DLQ", caseId, e);
                writeToDlq(ragTag, caseId, dslJson, businessEx);
                stream.ack(group, id);
            }
        } else {
            // 重试耗尽 → DLQ
            writeToDlq(ragTag, caseId, dslJson, businessEx);
            stream.ack(group, id);
            log.error("Vector mirror 失败入 DLQ: caseId={}, retries={}, err={}",
                    caseId, maxRetry, businessEx.getMessage());
        }
    }

    private boolean isVectorMirrorExists(String caseId) {
        if (caseId == null) return false;
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM spring_ai_vectors WHERE metadata->>'caseId' = ?",
                    Integer.class, caseId);
            return count != null && count > 0;
        } catch (Exception e) {
            // fail-soft: 宁可重复不可漏
            log.warn("Idempotency check failed for caseId={} (assume not exists): {}",
                    caseId, e.getMessage());
            return false;
        }
    }

    private void writeToDlq(String ragTag, String caseId, String dslJson, Exception e) {
        try {
            RStream<String, String> dlq = redissonClient.getStream(streamConfig.getVectorDlqStreamKey());
            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            if (errMsg.length() > 2000) errMsg = errMsg.substring(0, 2000);
            dlq.add(org.redisson.api.stream.StreamAddArgs.<String, String>entries(
                    "ragTag", ragTag != null ? ragTag : "",
                    "caseId", caseId != null ? caseId : "",
                    "dslJson", dslJson != null ? dslJson : "",
                    "errorMessage", errMsg,
                    "failedAt", String.valueOf(System.currentTimeMillis())));
        } catch (Exception dlqEx) {
            log.error("Vector mirror DLQ write failed: caseId={}", caseId, dlqEx);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

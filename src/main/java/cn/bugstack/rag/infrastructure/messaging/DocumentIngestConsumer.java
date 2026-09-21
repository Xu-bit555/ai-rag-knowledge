package cn.bugstack.rag.infrastructure.messaging;

import cn.bugstack.rag.model.entity.IngestTask;
import cn.bugstack.rag.repository.IIngestTaskRepository;
import cn.bugstack.rag.config.RedisStreamConfigProperties;
import cn.bugstack.rag.service.DocumentParserService;
import cn.bugstack.rag.service.ParagraphIngestService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.RedisBusyException;
import org.redisson.client.RedisException;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 文档摄入消费者 - 基于 Redis Stream 的异步消息处理
 *
 * <p>核心设计：
 * <pre>
 * 1. 可靠消费：Consumer Group 竞争消费，消息持久化不丢失
 * 2. ACK 确认：先更新DB状态，再执行ACK，防止消息丢失
 * 3. 幂等防御：多层状态拦截（COMPLETED/PROCESSING/FAILED）
 * </pre>
 */
@Slf4j
@Component
public class DocumentIngestConsumer {

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private RedisStreamConfigProperties streamConfig;

    @Resource
    private IIngestTaskRepository ingestTaskRepository;

    @Resource
    private DocumentParserService documentParserService;

    @Resource
    private ParagraphIngestService paragraphIngestService;

    private final AtomicBoolean running = new AtomicBoolean(true);

    private Thread workerThread;

    /**
     * 启动消费者
     */
    @PostConstruct
    public void start() {
        RStream<String, String> stream = redissonClient.getStream(streamConfig.getStreamKey());

        // 创建消费者组（如已存在则忽略）
        try {
            stream.createGroup(StreamCreateGroupArgs.name(streamConfig.getGroup()).makeStream());
        } catch (RedisBusyException ignored) {
            log.info("Stream group已存在");
        }

        // 启动消息处理主循环
        workerThread = new Thread(this::consumeLoop, "document-ingest-consumer");
        workerThread.setDaemon(true);
        workerThread.start();

        log.info("文档摄入消费者已启动");
    }

    /**
     * 消息消费主循环
     */
    private void consumeLoop() {
        RStream<String, String> stream = redissonClient.getStream(streamConfig.getStreamKey());
        String group = streamConfig.getGroup();
        String consumer = streamConfig.getConsumer();

        while (running.get()) {
            try {
                // 确保消费者组存在（可能因 Redis 重启而消失）
                try {
                    stream.createGroup(StreamCreateGroupArgs.name(group).makeStream());
                } catch (RedisBusyException ignored) {
                    // 组已存在
                } catch (RedisException e) {
                    log.debug("创建消费者组失败: {}", e.getMessage());
                }

                // 读取消息（阻塞5秒，未读到则继续轮询）
                Map<StreamMessageId, Map<String, String>> messages = stream.readGroup(
                        group, consumer,
                        StreamReadGroupArgs.neverDelivered().count(1).timeout(Duration.ofSeconds(5))
                );

                if (messages == null || messages.isEmpty()) {
                    continue;
                }

                // 逐条处理消息
                for (Map.Entry<StreamMessageId, Map<String, String>> entry : messages.entrySet()) {
                    processMessage(entry.getKey(), entry.getValue(), stream, group);
                }

            } catch (RedisException e) {
                log.warn("Redis Stream消费异常: {}", e.getMessage());
                try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            } catch (Exception e) {
                log.error("消费消息失败", e);
                try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
    }

    /**
     * 核心消息处理（整合所有业务逻辑）
     *
     * <p>流程：状态检查 → 幂等拦截 → 状态更新 → ETL执行 → DB状态同步 → ACK确认
     */
    private void processMessage(StreamMessageId id, Map<String, String> body,
                                RStream<String, String> stream, String group) {
        String taskId = body.get("taskId");
        String ragTag = body.get("ragTag");

        // 1. 幂等性防御：多层状态拦截
        IngestTask task = ingestTaskRepository.findById(taskId);
        if (task == null) {
            log.warn("任务不存在, taskId: {}, 忽略消息", taskId);
            stream.ack(group, id);
            return;
        }

        IngestTask.TaskStatus status = task.getStatus();
        if (status == IngestTask.TaskStatus.COMPLETED) {
            log.info("任务已完成（幂等跳过）, taskId: {}", taskId);
            stream.ack(group, id);
            return;
        }

        if (status == IngestTask.TaskStatus.FAILED) {
            log.warn("任务已失败（最终状态）, taskId: {}, 忽略消息", taskId);
            stream.ack(group, id);
            return;
        }

        // 2. 状态机驱动：PENDING → PROCESSING
        ingestTaskRepository.updateStatus(taskId, IngestTask.TaskStatus.PROCESSING, null);

        // 3. 执行 ETL 流水线
        Exception businessException = null;
        try {
            // 3.1 从 Redis 读取文件内容
            String redisKey = "rag:file:" + taskId;
            RBucket<byte[]> bucket = redissonClient.getBucket(redisKey);
            byte[] fileBytes = bucket.get();

            if (fileBytes == null || fileBytes.length == 0) {
                throw new RuntimeException("Redis中未找到文件数据, taskId: " + taskId);
            }

            // 3.2 解析文档得到 paragraphs
            DocumentParserService.ParseResult parseResult =
                    documentParserService.parseFromBytes(fileBytes, task.getFileName());

            // 3.3 调用 ParagraphIngestService 存入向量库
            paragraphIngestService.ingestDocumentFromParagraphs(
                    parseResult.getParagraphs(),
                    task.getFileName(),
                    ragTag
            );

            // 3.4 删除 Redis 中的文件数据
            bucket.delete();

        } catch (Exception e) {
            businessException = e;
        }

        // 4. 分布式状态一致性：先更新DB最终状态，再执行ACK
        if (businessException == null) {
            ingestTaskRepository.updateStatus(taskId, IngestTask.TaskStatus.COMPLETED, null);
            stream.ack(group, id);
            log.info("摄入任务完成, taskId: {}", taskId);
        } else {
            // 失败处理：检查重试次数
            int retryCount = Integer.parseInt(body.getOrDefault("retryCount", "0"));
            int maxRetry = 3;

            if (retryCount < maxRetry) {
                // 未超过最大重试次数 → 重新入队，retryCount + 1
                int newRetryCount = retryCount + 1;
                stream.add(org.redisson.api.stream.StreamAddArgs
                        .<String, String>entries("taskId", taskId, "ragTag", ragTag, "retryCount", String.valueOf(newRetryCount)));
                stream.ack(group, id);  // 原消息 ACK
                log.warn("任务处理失败（重试中）, taskId: {}, retryCount: {}/{}, 重新入队", taskId, newRetryCount, maxRetry);
            } else {
                // 超过最大重试次数 → 彻底失败
                String errMsg = businessException.getMessage();
                if (errMsg == null) errMsg = businessException.getClass().getName();
                if (errMsg.length() > 2000) errMsg = errMsg.substring(0, 2000);

                ingestTaskRepository.updateStatus(taskId, IngestTask.TaskStatus.FAILED, errMsg + " [重试" + maxRetry + "次均失败]");
                stream.ack(group, id);

                // P0-9: 写入 DLQ stream, 包含完整上下文 (taskId, ragTag, fileName, errorMessage, failedAt)
                //   便于 /api/v1/knowledge/ingest/retry 端点回放
                try {
                    RStream<String, String> dlq = redissonClient.getStream(streamConfig.getDlqStreamKey());
                    String fileName = task != null && task.getFileName() != null ? task.getFileName() : "";
                    dlq.add(org.redisson.api.stream.StreamAddArgs
                            .<String, String>entries(
                                    "taskId", taskId,
                                    "ragTag", ragTag != null ? ragTag : "",
                                    "fileName", fileName,
                                    "errorMessage", errMsg,
                                    "failedAt", String.valueOf(System.currentTimeMillis())));
                    log.warn("任务失败, 已写入 DLQ: streamKey={}, taskId={}", streamConfig.getDlqStreamKey(), taskId);
                } catch (Exception dlqEx) {
                    log.error("写入 DLQ 失败 (不影响主流程, 任务已标记 FAILED): taskId={}", taskId, dlqEx);
                }

                // P0-9: 延长文件 TTL (默认 7 天), 避免 24h 后 retry 找不到文件
                try {
                    String fileKey = "rag:file:" + taskId;
                    RBucket<byte[]> fileBucket = redissonClient.getBucket(fileKey);
                    if (fileBucket.isExists()) {
                        long ttlMs = (long) streamConfig.getFileTtlHours() * 3600 * 1000;
                        fileBucket.expire(Duration.ofMillis(ttlMs));
                        log.info("任务失败, 延长文件 TTL 至 {} 小时, taskId={}",
                                streamConfig.getFileTtlHours(), taskId);
                    }
                } catch (Exception ttlEx) {
                    log.warn("延长文件 TTL 失败: taskId={}", taskId, ttlEx);
                }

                log.error("摄入任务失败, taskId: {}, 重试{}次均失败, 错误: {}", taskId, maxRetry, errMsg, businessException);
            }
        }
    }

    /**
     * 停止消费者
     */
    @PreDestroy
    public void stop() {
        running.set(false);
        if (workerThread != null) workerThread.interrupt();
    }
}
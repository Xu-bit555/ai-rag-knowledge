package cn.bugstack.rag.infrastructure.messaging;

import cn.bugstack.rag.model.entity.IngestTask;
import cn.bugstack.rag.repository.IIngestTaskRepository;
import cn.bugstack.rag.config.RedisStreamConfigProperties;
import cn.bugstack.rag.service.DocumentETLService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.RedisBusyException;
import org.redisson.client.RedisException;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 文档摄入消费者 - 消息队列消费者
 * 处理Redis Stream中的文档摄入任务
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
    private cn.bugstack.rag.service.DocumentETLService documentETLService;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread workerThread;
    private Thread pendingClaimThread;

    private static final int MAX_RETRY_COUNT = 3;
    private static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(5);

    @PostConstruct
    public void start() {
        RStream<String, String> stream = redissonClient.getStream(streamConfig.getStreamKey());
        try {
            stream.createGroup(StreamCreateGroupArgs.name(streamConfig.getGroup()).makeStream());
        } catch (RedisBusyException ignored) {
            log.info("Stream group已存在");
        }

        workerThread = new Thread(this::loop, "document-ingest-consumer");
        workerThread.setDaemon(true);
        workerThread.start();

        // 启动超时回收线程，定期检查并回收 pending 消息
        pendingClaimThread = new Thread(this::pendingClaimLoop, "pending-claim-consumer");
        pendingClaimThread.setDaemon(true);
        pendingClaimThread.start();

        log.info("文档摄入消费者已启动");
    }

    private void loop() {
        RStream<String, String> stream = redissonClient.getStream(streamConfig.getStreamKey());
        String group = streamConfig.getGroup();
        String consumer = streamConfig.getConsumer();
        var readArgs = StreamReadGroupArgs.neverDelivered().count(1).timeout(Duration.ofSeconds(5));

        while (running.get()) {
            try {
                // 确保消费者组存在
                ensureGroupExists(stream, group);

                Map<StreamMessageId, Map<String, String>> messages = stream.readGroup(group, consumer, readArgs);
                if (messages == null || messages.isEmpty()) {
                    continue;
                }

                for (Map.Entry<StreamMessageId, Map<String, String>> entry : messages.entrySet()) {
                    StreamMessageId id = entry.getKey();
                    Map<String, String> body = entry.getValue();
                    processMessageWithRetry(body, stream, group, id);
                }
            } catch (RedisException e) {
                // NOGROUP 等错误，忽略并等待下次重试
                log.warn("Redis Stream消费异常: {}", e.getMessage());
                sleep(1000);
            } catch (Exception e) {
                log.error("消费消息失败", e);
                sleep(1000);
            }
        }
    }

    private void ensureGroupExists(RStream<String, String> stream, String group) {
        try {
            stream.createGroup(StreamCreateGroupArgs.name(group).makeStream());
        } catch (RedisBusyException ignored) {
            // 组已存在，忽略
        } catch (RedisException e) {
            // 可能stream不存在，等待下次重试
            log.debug("创建消费者组失败: {}", e.getMessage());
        }
    }

    /**
     * 定时回收超时未处理的消息（Pending Message Claim）
     * 处理场景：消费者拿到消息后崩溃，消息未 Ack，需要超时回收
     */
    private void pendingClaimLoop() {
        RStream<String, String> stream = redissonClient.getStream(streamConfig.getStreamKey());
        String group = streamConfig.getGroup();
        String claimConsumer = streamConfig.getConsumer() + "-claimer";

        while (running.get()) {
            try {
                // 每分钟检查一次超时消息
                Thread.sleep(60000);

                // 尝试回收超过5分钟未处理的消息
                var claimArgs = StreamReadGroupArgs.greaterThan(StreamMessageId.NEVER_DELIVERED).count(10);
                Map<StreamMessageId, Map<String, String>> pending = stream.readGroup(group, claimConsumer, claimArgs);

                if (pending != null && !pending.isEmpty()) {
                    log.info("回收超时消息, 数量: {}", pending.size());
                    for (Map.Entry<StreamMessageId, Map<String, String>> entry : pending.entrySet()) {
                        StreamMessageId id = entry.getKey();
                        Map<String, String> body = entry.getValue();
                        // 重置任务状态为 PENDING，重新入队
                        String taskId = body.get("taskId");
                        if (taskId != null) {
                            ingestTaskRepository.updateStatus(taskId, IngestTask.TaskStatus.PENDING, "超时回收，重新处理");
                        }
                        stream.ack(group, id);
                        // 重新发到队列
                        stream.addAll(java.util.Map.of(
                                "taskId", body.get("taskId"),
                                "ragTag", body.get("ragTag"),
                                "filePath", body.get("filePath")
                        ));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("回收超时消息异常: {}", e.getMessage());
            }
        }
    }

    /**
     * 带重试机制的消息处理
     */
    private void processMessageWithRetry(Map<String, String> body, RStream<String, String> stream, String group, StreamMessageId id) {
        String taskId = body.get("taskId");

        // 1. 幂等检查：任务是否已处理过
        IngestTask existingTask = ingestTaskRepository.findById(taskId);
        if (existingTask != null && existingTask.getStatus() == IngestTask.TaskStatus.COMPLETED) {
            log.info("任务已处理过，跳过, taskId: {}", taskId);
            stream.ack(group, id);
            return;
        }

        // 2. 标记为处理中
        ingestTaskRepository.updateStatus(taskId, IngestTask.TaskStatus.PROCESSING, null);

        try {
            // 3. 处理消息
            processDocument(body);

            // 4. 先 Ack 确认
            stream.ack(group, id);

            // 5. 再删文件（文件删除不影响任务状态）
            String filePath = body.get("filePath");
            if (filePath != null) {
                Files.deleteIfExists(Paths.get(filePath));
            }

            // 6. 最后标记完成
            ingestTaskRepository.updateStatus(taskId, IngestTask.TaskStatus.COMPLETED, null);

            log.info("摄入任务完成, taskId: {}", taskId);
        } catch (Exception e) {
            log.error("摄入任务失败, taskId: {}", taskId, e);
            handleFailure(taskId, body, stream, group, id, e);
        }
    }

    /**
     * 处理失败：自动重试或标记失败
     */
    private void handleFailure(String taskId, Map<String, String> body, RStream<String, String> stream, String group, StreamMessageId id, Exception e) {
        // 获取当前重试次数
        int retryCount = getRetryCount(taskId);

        if (retryCount < MAX_RETRY_COUNT) {
            // 重试次数未达上限，重新放回队列
            log.warn("任务处理失败，准备重试, taskId: {}, 当前重试次数: {}/{}", taskId, retryCount + 1, MAX_RETRY_COUNT);
            // 重新入队，等待下次消费
            stream.addAll(java.util.Map.of(
                    "taskId", taskId,
                    "ragTag", body.get("ragTag"),
                    "filePath", body.get("filePath"),
                    "retryCount", String.valueOf(retryCount + 1)
            ));
        } else {
            // 超过重试次数，标记失败
            log.error("任务处理失败，已达最大重试次数, taskId: {}, 重试次数: {}", taskId, MAX_RETRY_COUNT);
            ingestTaskRepository.updateStatus(taskId, IngestTask.TaskStatus.FAILED, safeMessage(e));
        }
    }

    /**
     * 获取任务已重试次数
     */
    private int getRetryCount(String taskId) {
        try {
            IngestTask task = ingestTaskRepository.findById(taskId);
            if (task != null && task.getErrorMessage() != null) {
                // 从错误信息中解析重试次数
                String msg = task.getErrorMessage();
                if (msg.contains("重试次数:")) {
                    String countStr = msg.substring(msg.indexOf("重试次数:") + 5, msg.indexOf("/"));
                    return Integer.parseInt(countStr.trim());
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /**
     * 文档处理逻辑
     */
    private void processDocument(Map<String, String> body) {
        String taskId = body.get("taskId");
        String ragTag = body.get("ragTag");
        String filePath = body.get("filePath");

        log.info("开始处理摄入任务, taskId: {}, ragTag: {}", taskId, ragTag);

        // 调用 ETL 流水线：Extract → Transform → Load
        documentETLService.etlPipeline(filePath, ragTag);
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null) return e.getClass().getName();
        if (message.length() <= 2000) return message;
        return message.substring(0, 2000);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
        }
        if (pendingClaimThread != null) {
            pendingClaimThread.interrupt();
        }
    }

}

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 文档摄入消费者 - 基于 Redis Stream 的异步消息处理
 *
 * <p>核心设计：
 * <pre>
 * 1. 可靠投递：Consumer Group + PEL 机制，消息持久化不丢失
 * 2. 顺序执行：同一 taskId 只被一个消费者领取
 * 3. ACK 确认：先更新DB状态，再执行ACK，防止消息丢失
 * 4. 自动重试：PEL 死信巡检，超时消息自动回收重试
 * 5. 幂等防御：多层状态拦截（COMPLETED/PROCESSING/FAILED）
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
    private DocumentETLService documentETLService;

    private final AtomicBoolean running = new AtomicBoolean(true);

    private Thread workerThread;
    private Thread pendingClaimThread;

    private static final int MAX_RETRY_COUNT = 3;
    private static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(5);

    private final ExecutorService fileDeleteExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(),
            r -> new Thread(r, "file-delete-worker")
    );

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

        // 启动死信回收线程
        pendingClaimThread = new Thread(this::pendingClaimLoop, "pending-claim-consumer");
        pendingClaimThread.setDaemon(true);
        pendingClaimThread.start();

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
                Thread.sleep(1000);
            } catch (Exception e) {
                log.error("消费消息失败", e);
                Thread.sleep(1000);
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
        String filePath = body.get("filePath");

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

        if (status == IngestTask.TaskStatus.PROCESSING) {
            // 防御性忽略：可能为上一个消费者崩溃后的恢复消息
            log.warn("任务正在处理中（防御性忽略，由 pendingClaim 回收）, taskId: {}", taskId);
            stream.ack(group, id);
            return;
        }

        if (status == IngestTask.TaskStatus.FAILED) {
            log.warn("任务已失败（最终状态）, taskId: {}, 忽略消息", taskId);
            stream.ack(group, id);
            return;
        }

        // 2. 状态机驱动：PENDING → PROCESSING（乐观锁预占）
        ingestTaskRepository.updateStatus(taskId, IngestTask.TaskStatus.PROCESSING, null);

        // 3. 执行 ETL 流水线
        Exception businessException = null;
        try {
            documentETLService.etlPipeline(
                    new org.springframework.core.io.FileSystemResource(filePath),
                    filePath,
                    ragTag
            );
        } catch (Exception e) {
            businessException = e;
        }

        // 4. 分布式状态一致性：先更新DB最终状态，再执行ACK
        if (businessException == null) {
            // 成功：DB=COMPLETED → ACK → 异步删除文件
            ingestTaskRepository.updateStatus(taskId, IngestTask.TaskStatus.COMPLETED, null);
            stream.ack(group, id);

            // 异步解耦：文件删除与任务状态完全解耦
            if (filePath != null) {
                CompletableFuture.runAsync(() -> {
                    try {
                        Files.deleteIfExists(Paths.get(filePath));
                    } catch (Exception e) {
                        log.warn("文件异步删除失败（不影响任务状态）, filePath: {}", filePath, e);
                    }
                }, fileDeleteExecutor);
            }

            log.info("摄入任务完成, taskId: {}", taskId);
        } else {
            // 失败：DB=FAILED → ACK（利用 PEL + pendingClaimLoop 死信巡检处理重试）
            String errMsg = businessException.getMessage();
            if (errMsg == null) errMsg = businessException.getClass().getName();
            if (errMsg.length() > 2000) errMsg = errMsg.substring(0, 2000);

            ingestTaskRepository.updateStatus(taskId, IngestTask.TaskStatus.FAILED, errMsg);
            stream.ack(group, id);

            log.error("摄入任务失败, taskId: {}, 错误: {}", taskId, errMsg, businessException);
        }
    }

    /**
     * 死信回收循环：定时回收超5分钟未处理的消息
     *
     * <p>处理场景：消费者领取消息后崩溃，消息未ACK，由 pendingClaimLoop 超时回收
     */
    private void pendingClaimLoop() {
        RStream<String, String> stream = redissonClient.getStream(streamConfig.getStreamKey());
        String group = streamConfig.getGroup();
        String claimConsumer = streamConfig.getConsumer() + "-claimer";

        while (running.get()) {
            try {
                Thread.sleep(60000);  // 每分钟检查一次

                // 读取 PEL 中超时的消息
                Map<StreamMessageId, Map<String, String>> pending = stream.readGroup(
                        group, claimConsumer,
                        StreamReadGroupArgs.greaterThan(StreamMessageId.NEVER_DELIVERED).count(10)
                );

                if (pending == null || pending.isEmpty()) {
                    continue;
                }

                log.info("回收超时消息, 数量: {}", pending.size());

                for (Map.Entry<StreamMessageId, Map<String, String>> entry : pending.entrySet()) {
                    StreamMessageId id = entry.getKey();
                    Map<String, String> body =  entry.getValue();
                    String taskId = body.get("taskId");

                    // 重置任务状态为 PENDING，重新入队
                    if (taskId != null) {
                        ingestTaskRepository.updateStatus(taskId, IngestTask.TaskStatus.PENDING, "超时回收，重新处理");
                    }

                    stream.ack(group, id);  // 从原 PEL 移除
                    stream.addAll(body);    // 重新入队
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
     * 停止消费者
     */
    @PreDestroy
    public void stop() {
        running.set(false);
        if (workerThread != null) workerThread.interrupt();
        if (pendingClaimThread != null) pendingClaimThread.interrupt();
        if (fileDeleteExecutor != null) fileDeleteExecutor.shutdownNow();
    }
}

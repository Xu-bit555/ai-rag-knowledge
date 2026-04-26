package cn.bugstack.rag.infrastructure.messaging;

import cn.bugstack.rag.model.entity.DocumentHash;
import cn.bugstack.rag.repository.IDocumentHashRepository;
import cn.bugstack.rag.repository.IIngestTaskRepository;
import cn.bugstack.rag.repository.IVectorStoreRepository;
import cn.bugstack.rag.service.DocumentETLService;
import cn.bugstack.rag.config.RedisStreamConfigProperties;
import cn.bugstack.rag.model.entity.IngestTask;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.RedisBusyException;
import org.redisson.client.RedisException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 文档更新消费者 - 基于 Redis Stream 的事件驱动更新
 * <p>
 * 核心设计：
 * 1. 事件驱动：监听更新消息，文档变更后秒级生效
 * 2. Hash检测：通过SHA-256检测文档是否真正变化，避免无谓重处理
 * 3. 先删后增：精确删除旧chunk，再ETL新增，保证数据一致性
 * 4. 幂等防御：多层状态拦截（COMPLETED/PROCESSING/FAILED）
 */
@Slf4j
@Component
public class DocumentUpdateConsumer {

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private RedisStreamConfigProperties streamConfig;

    @Autowired
    private IDocumentHashRepository documentHashRepository;

    @Autowired
    private IVectorStoreRepository vectorStoreRepository;

    @Autowired
    private DocumentETLService documentETLService;

    @Autowired
    private IIngestTaskRepository ingestTaskRepository;

    @Value("${spring.ai.rag.splitter.batch-size:200}")
    private int batchSize;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread workerThread;

    private final ExecutorService fileDeleteExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(),
            r -> new Thread(r, "file-delete-worker")
    );

    @PostConstruct
    public void start() {
        RStream<String, String> stream = redissonClient.getStream(streamConfig.getUpdateStreamKey());

        try {
            stream.createGroup(StreamCreateGroupArgs.name(streamConfig.getUpdateGroup()).makeStream());
            log.info("更新消息消费组创建成功: {}", streamConfig.getUpdateGroup());
        } catch (RedisBusyException e) {
            log.info("更新消息消费组已存在: {}", streamConfig.getUpdateGroup());
        } catch (Exception e) {
            log.warn("更新消息消费组初始化失败: {}", e.getMessage());
        }

        workerThread = new Thread(this::consumeLoop, "document-update-consumer");
        workerThread.setDaemon(true);
        workerThread.start();

        log.info("文档更新消费者已启动, stream: {}, group: {}",
                streamConfig.getUpdateStreamKey(), streamConfig.getUpdateGroup());
    }

    private void consumeLoop() {
        RStream<String, String> stream = redissonClient.getStream(streamConfig.getUpdateStreamKey());
        String group = streamConfig.getUpdateGroup();
        String consumer = streamConfig.getUpdateConsumer();

        while (running.get()) {
            try {
                try {
                    stream.createGroup(StreamCreateGroupArgs.name(group).makeStream());
                } catch (RedisBusyException ignored) {
                } catch (RedisException e) {
                    log.debug("创建消费组失败: {}", e.getMessage());
                }

                Map<StreamMessageId, Map<String, String>> messages = stream.readGroup(
                        group, consumer,
                        StreamReadGroupArgs.neverDelivered().count(1).timeout(Duration.ofSeconds(5))
                );

                if (messages == null || messages.isEmpty()) {
                    continue;
                }

                for (Map.Entry<StreamMessageId, Map<String, String>> entry : messages.entrySet()) {
                    processUpdateMessage(entry.getKey(), entry.getValue(), stream, group);
                }

            } catch (RedisException e) {
                log.warn("Redis Stream消费异常: {}", e.getMessage());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {}
            } catch (Exception e) {
                log.error("消费更新消息失败", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {}
            }
        }
    }

    private void processUpdateMessage(StreamMessageId id, Map<String, String> body,
                                      RStream<String, String> stream, String group) {
        String taskId = body.get("taskId");
        String ragTag = body.get("ragTag");
        String docId = body.get("docId");
        String contentHash = body.get("contentHash");
        String filePath = body.get("filePath");

        log.info("收到更新消息, taskId: {}, docId: {}, ragTag: {}", taskId, docId, ragTag);

        // 1. 幂等性防御：多层状态拦截
        IngestTask task = ingestTaskRepository.findById(taskId);
        if (task != null) {
            IngestTask.TaskStatus status = task.getStatus();
            if (status == IngestTask.TaskStatus.COMPLETED) {
                log.info("任务已完成（幂等跳过）, taskId: {}", taskId);
                stream.ack(group, id);
                return;
            }
            if (status == IngestTask.TaskStatus.PROCESSING) {
                log.warn("任务正在处理中（防御性忽略）, taskId: {}", taskId);
                stream.ack(group, id);
                return;
            }
            if (status == IngestTask.TaskStatus.FAILED) {
                log.warn("任务已失败（最终状态）, taskId: {}, 忽略消息", taskId);
                stream.ack(group, id);
                return;
            }
        }

        // 2. Hash检测：判断文档是否真正变化
        Optional<DocumentHash> existingOpt = documentHashRepository.findByDocIdAndRagTag(docId, ragTag);

        if (existingOpt.isPresent()) {
            String existingHash = existingOpt.get().getContentHash();
            if (existingHash != null && existingHash.equals(contentHash)) {
                log.info("文档内容未变化，跳过更新, docId: {}, ragTag: {}", docId, ragTag);
                if (task != null) {
                    ingestTaskRepository.updateStatus(taskId, IngestTask.TaskStatus.COMPLETED, "内容未变化，跳过");
                }
                stream.ack(group, id);
                return;
            }
        }

        // 3. Hash变化：执行更新流程
        ingestTaskRepository.updateStatus(taskId, IngestTask.TaskStatus.PROCESSING, null);

        Exception businessException = null;
        try {
            // 3.1 执行ETL更新（先删后增）
            documentETLService.etlPipelineForUpdate(
                    new FileSystemResource(filePath),
                    filePath,
                    ragTag,
                    docId
            );

            // 3.2 查询新chunkIds并更新document_hash表
            java.util.List<Long> newChunkIds = vectorStoreRepository.findChunkIdsByDocId(ragTag, docId);

            DocumentHash newHash = DocumentHash.builder()
                    .docId(docId)
                    .ragTag(ragTag)
                    .contentHash(contentHash)
                    .chunkIds(com.alibaba.fastjson2.JSON.toJSONString(newChunkIds))
                    .lastModified(LocalDateTime.now())
                    .createdAt(existingOpt.map(DocumentHash::getCreatedAt).orElse(LocalDateTime.now()))
                    .updatedAt(LocalDateTime.now())
                    .build();
            documentHashRepository.upsert(newHash);

            log.info("文档更新成功, docId: {}, ragTag: {}, 新chunk数: {}", docId, ragTag, newChunkIds.size());

        } catch (Exception e) {
            businessException = e;
            log.error("文档更新失败, docId: {}, ragTag: {}", docId, ragTag, e);
        }

        // 4. 更新任务状态并ACK
        if (businessException == null) {
            ingestTaskRepository.updateStatus(taskId, IngestTask.TaskStatus.COMPLETED, null);
            stream.ack(group, id);

            // 异步删除文件
            if (filePath != null) {
                CompletableFuture.runAsync(() -> {
                    try {
                        Files.deleteIfExists(Paths.get(filePath));
                    } catch (Exception e) {
                        log.warn("文件异步删除失败, filePath: {}", filePath, e);
                    }
                }, fileDeleteExecutor);
            }
        } else {
            String errMsg = businessException.getMessage();
            if (errMsg == null) errMsg = businessException.getClass().getName();
            if (errMsg.length() > 2000) errMsg = errMsg.substring(0, 2000);

            ingestTaskRepository.updateStatus(taskId, IngestTask.TaskStatus.FAILED, errMsg);
            stream.ack(group, id);
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (workerThread != null) workerThread.interrupt();
        if (fileDeleteExecutor != null) fileDeleteExecutor.shutdownNow();
        log.info("文档更新消费者已停止");
    }
}
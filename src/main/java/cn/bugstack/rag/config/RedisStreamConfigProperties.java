package cn.bugstack.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Redis Stream配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "spring.ai.rag.stream")
public class RedisStreamConfigProperties {

    /**
     * Stream键
     */
    private String streamKey = "rag:ingest:stream";

    /**
     * 消费组
     */
    private String group = "rag-ingest-group";

    /**
     * 消费者名称
     */
    private String consumer = "rag-ingest-consumer";

    /**
     * 更新消息Stream键（文档变更事件）
     */
    private String updateStreamKey = "rag:update:stream";

    /**
     * 更新消息消费组
     */
    private String updateGroup = "rag-update-group";

    /**
     * 更新消息消费者名称
     */
    private String updateConsumer = "rag-update-consumer";

    /**
     * P0-9: 失败任务的 DLQ Stream key (Dead Letter Queue)
     */
    private String dlqStreamKey = "rag:ingest:dlq";

    /**
     * P0-9: 失败任务文件在 Redis 中的保留时长 (默认 7 天, 之前 24h 太短)
     */
    private int fileTtlHours = 168;

    // ─────────────────────────────────────────────────────────────
    // Phase 3: 向量镜像专用 stream 配置 (test_case 双写拆异步)
    //   adopt → VectorMirrorDispatcher → rag:vector:mirror → VectorMirrorConsumer → spring_ai_vectors
    //   失败 3 次 → rag:vector:dlq → 人工 retry
    // ─────────────────────────────────────────────────────────────

    /** 向量镜像任务 stream key */
    private String vectorMirrorStreamKey = "rag:vector:mirror";

    /** 向量镜像 DLQ stream key (consumer 重试耗尽后入此, 供手动 retry) */
    private String vectorDlqStreamKey = "rag:vector:dlq";

    /** 向量镜像 consumer group */
    private String vectorMirrorGroup = "rag-vector-mirror-group";

    /** 向量镜像 consumer 名称 */
    private String vectorMirrorConsumer = "rag-vector-mirror-consumer";

    /** consumer 内最大重试次数 (失败后入 DLQ) */
    private int vectorMaxRetry = 3;

    /** 重试退避基数 (ms): 第 N 次失败等 N * baseBackoff */
    private long vectorRetryBackoffMs = 1000L;

    /** consumer 内嵌入 API 并发限流 (Semaphore permits) */
    private int vectorDispatchConcurrency = 4;

}

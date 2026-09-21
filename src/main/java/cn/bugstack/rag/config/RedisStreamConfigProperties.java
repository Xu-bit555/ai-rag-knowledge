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

}

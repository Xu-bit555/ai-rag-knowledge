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

}

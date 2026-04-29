package cn.bugstack.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MiniMax API配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "spring.ai.minimax")
public class MiniMaxConfigProperties {

    /**
     * API基础URL
     */
    private String baseUrl;

    /**
     * API密钥
     */
    private String apiKey;

    /**
     * 聊天完成接口路径
     */
    private String completionsPath = "/v1/chat/completions";

    /**
     * 嵌入接口路径
     */
    private String embeddingsPath = "/v1/embeddings";

    /**
     * 使用的模型
     */
    private String model = "MiniMax-M2.7";

}

package cn.bugstack.rag.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingClient;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.OpenAiEmbeddingClient;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import javax.sql.DataSource;

/**
 * 向量存储配置
 */
@Slf4j
@Configuration
public class VectorStoreConfig {

    /**
     * Embedding 模型配置
     */
    @Bean
    @ConfigurationProperties(prefix = "spring.ai.embedding")
    public EmbeddingProperties embeddingProperties() {
        return new EmbeddingProperties();
    }

    /**
     * Embedding 模型（根据配置动态选择）
     * 支持：bge / minimax / nomic
     */
    @Bean
    public EmbeddingClient embeddingModel(EmbeddingProperties properties) {
        String provider = properties.getProvider().toLowerCase();
        log.info("初始化 Embedding 模型, provider: {}", provider);

        switch (provider) {
            case "bge":
                return createOpenAiEmbeddingClient(
                        properties.getBge().getBaseUrl(),
                        properties.getBge().getApiKey(),
                        properties.getBge().getModel()
                );
            case "minimax":
                return createOpenAiEmbeddingClient(
                        properties.getMinimax().getBaseUrl(),
                        properties.getMinimax().getApiKey(),
                        properties.getMinimax().getModel()
                );
            case "nomic":
            default:
                // nomic 通过 OpenAI 兼容接口调用
                log.warn("nomic provider 需要自行部署兼容服务，或使用其他 provider");
                return createOpenAiEmbeddingClient(
                        properties.getMinimax().getBaseUrl(),
                        properties.getMinimax().getApiKey(),
                        "nomic-embed-text"
                );
        }
    }

    /**
     * 创建 OpenAI 兼容的 Embedding 模型
     */
    private EmbeddingClient createOpenAiEmbeddingClient(String baseUrl, String apiKey, String model) {
        log.info("创建 Embedding 模型, baseUrl: {}, model: {}", baseUrl, model);
        OpenAiApi openAiApi = new OpenAiApi(baseUrl, apiKey);
        OpenAiEmbeddingOptions options = new OpenAiEmbeddingOptions();
        options.setModel(model);
        return new OpenAiEmbeddingClient(openAiApi, null, options, null);
    }

    /**
     * PgVector 向量存储
     */
    @Bean
    @Lazy
    public VectorStore vectorStore(DataSource dataSource, EmbeddingClient embeddingModel,
                                   EmbeddingProperties properties) {
        int dimensions = properties.getDimensions();
        log.info("初始化 PgVectorStore, dimensions: {}", dimensions);
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        return new PgVectorStore(jdbcTemplate, embeddingModel, dimensions);
    }

    /**
     * Embedding 配置属性
     */
    @Data
    public static class EmbeddingProperties {
        // 模型提供者：bge / minimax / nomic
        private String provider = "bge";
        // 向量维度
        private int dimensions = 1024;

        private BgeProperties bge = new BgeProperties();
        private MinimaxProperties minimax = new MinimaxProperties();

        @Data
        public static class BgeProperties {
            private String baseUrl = "http://your-bge-service.com";
            private String apiKey = "";
            private String model = "bge-large-zh";
        }

        @Data
        public static class MinimaxProperties {
            private String baseUrl = "https://api.minimax.chat";
            private String apiKey = "";
            private String model = "embo-01";
        }
    }

}

package cn.bugstack.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingClient;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 向量存储配置
 */
@Slf4j
@Configuration
public class VectorStoreConfig {

    /**
     * Embedding 模型（SiliconFlow 兼容版本）
     */
    @Bean
    public EmbeddingClient embeddingModel() {
        log.info("初始化 Embedding 模型 (SiliconFlow兼容版), URL: https://api.siliconflow.cn/v1, 模型: BAAI/bge-m3");
        return new SiliconFlowEmbeddingModel();
    }

    /**
     * PgVector 向量存储
     */
    @Bean
    @Lazy
    public VectorStore vectorStore(DataSource dataSource, EmbeddingClient embeddingModel,
                                   @Value("${spring.ai.embedding.dimensions:1024}") int dimensions) {
        log.info("初始化 PgVectorStore, dimensions: {}", dimensions);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        return new PgVectorStore(jdbcTemplate, embeddingModel, dimensions);
    }

}

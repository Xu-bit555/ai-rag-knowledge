package cn.bugstack.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 向量存储配置
 *
 * Spring AI 1.1.x:
 * - EmbeddingClient → EmbeddingModel (renamed interface)
 * - PgVectorStore 通过 builder(JdbcTemplate, EmbeddingModel) 构造
 */
@Slf4j
@Configuration
public class VectorStoreConfig {

    /**
     * 向量表名 — 必须与 VectorStoreRepositoryImpl 中原生 SQL 使用的表名保持一致
     */
    private static final String VECTOR_TABLE_NAME = "spring_ai_vectors";

    /**
     * Embedding 模型: 由 SiliconFlowEmbeddingModel @Component 自动注册为 EmbeddingModel bean
     * 此处不再显式 @Bean 包装,避免与 @Component 冲突
     *
     * 若 Spring AI 自动检测到 OpenAI 配置,可通过 application.yml 排除:
     *   spring.ai.openai.chat.enabled=false
     */

    /**
     * PgVector 向量存储
     *
     * 注意 tableName 必须显式指定:
     *   - PgVectorStore builder 默认表名是 vector_store
     *   - 但 VectorStoreRepositoryImpl 里的原生 SQL 查的是 spring_ai_vectors
     *   - 这里是手工 builder 构造, 不读 spring.ai.vectorstore.pgvector.* 自动配置,
     *     所以在 application.yml 里配 table-name 是无效的, 必须写死在这里
     */
    @Bean
    @Lazy
    public VectorStore vectorStore(DataSource dataSource, EmbeddingModel embeddingModel,
                                   @Value("${spring.ai.embedding.dimensions:1024}") int dimensions) {
        log.info("初始化 PgVectorStore, dimensions: {}, tableName: {}", dimensions, VECTOR_TABLE_NAME);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName(VECTOR_TABLE_NAME)
                .dimensions(dimensions)
                .initializeSchema(true)
                .build();
    }

}
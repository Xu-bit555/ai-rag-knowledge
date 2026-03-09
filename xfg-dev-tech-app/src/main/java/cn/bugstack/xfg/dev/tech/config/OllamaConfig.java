package cn.bugstack.xfg.dev.tech.config;

import org.springframework.ai.ollama.OllamaChatClient;
import org.springframework.ai.ollama.OllamaEmbeddingClient;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class OllamaConfig {

    @Bean
    public OllamaApi ollamaApi(@Value("${spring.ai.ollama.base-url}") String baseUrl) {
        return new OllamaApi(baseUrl);
    }

    @Bean
    public OllamaChatClient ollamaChatClient(OllamaApi ollamaApi) {
        return new OllamaChatClient(ollamaApi);
    }

    // 文本分割器：将长文本分割成更小的片段Token，便于处理和存储
    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return new TokenTextSplitter();
    }

    // 实例化基于内存的简单向量数据库，配置 nomic-embed-text 嵌入模型将文本转换为向量。
    //模型部署在你配置文件 baseUrl 指向的 Ollama 服务端运行环境中。
    //代码通过 OllamaEmbeddingClient 底层的 OllamaApi，将文本内容封装成 HTTP 请求发送给 Ollama 服务，Ollama 服务调用 nomic-embed-text 模型将文本计算为多维浮点数组（向量）后，作为 HTTP 响应返回给程序。
    @Bean
    public SimpleVectorStore simpleVectorStore(OllamaApi ollamaApi){
        OllamaEmbeddingClient embeddingClient = new OllamaEmbeddingClient(ollamaApi);
        embeddingClient.withDefaultOptions(OllamaOptions.create().withModel("nomic-embed-text"));
        return new SimpleVectorStore(embeddingClient);
    }

    // 实例化基于 PostgreSQL 的持久化向量数据库，使用 jdbcTemplate 执行 SQL 并复用嵌入模型配置。
    @Bean
    public PgVectorStore pgVectorStore(OllamaApi ollamaApi, JdbcTemplate jdbcTemplate) {
        OllamaEmbeddingClient embeddingClient = new OllamaEmbeddingClient(ollamaApi);
        embeddingClient.withDefaultOptions(OllamaOptions.create().withModel("nomic-embed-text"));
        return new PgVectorStore(jdbcTemplate, embeddingClient);
    }

}

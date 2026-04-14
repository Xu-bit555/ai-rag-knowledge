package cn.bugstack.rag.config;

import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量存储配置
 */
@Configuration
public class VectorStoreConfig {

    private final TextSplitterConfigProperties splitterConfig;

    public VectorStoreConfig(TextSplitterConfigProperties splitterConfig) {
        this.splitterConfig = splitterConfig;
    }

    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return new TokenTextSplitter(
                splitterConfig.getMaxTokens(),
                splitterConfig.getMinTokens(),
                splitterConfig.getMinChunkLengthToEmbed(),
                splitterConfig.getMergeChunkLength(),
                splitterConfig.isKeepSeparator()
        );
    }

}

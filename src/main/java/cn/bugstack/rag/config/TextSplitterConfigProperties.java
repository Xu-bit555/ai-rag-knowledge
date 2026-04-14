package cn.bugstack.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 文本切分配置属性
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "spring.ai.rag.splitter")
public class TextSplitterConfigProperties {

    /**
     * 最大token数
     */
    private int maxTokens = 500;

    /**
     * 最小token数
     */
    private int minTokens = 50;

    /**
     * 最小嵌入长度
     */
    private int minChunkLengthToEmbed = 5;

    /**
     * 合并块长度
     */
    private int mergeChunkLength = 200;

    /**
     * 保留分隔符
     */
    private boolean keepSeparator = true;
}

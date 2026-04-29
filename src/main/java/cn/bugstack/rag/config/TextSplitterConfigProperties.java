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
     * 每个 chunk 的最大 token 数
     */
    private int maxTokens = 1000;

    /**
     * 每个 chunk 的最小 token 数，过小则合并到上一块
     */
    private int minTokens = 50;

    /**
     * 小于此字符长度的 chunk 不进行 embedding
     */
    private int minChunkLengthToEmbed = 5;

    /**
     * 相邻短 chunk 合并阈值（字符数）
     */
    private int mergeChunkLength = 200;

    /**
     * 是否保留分隔符
     */
    private boolean keepSeparator = true;

    /**
     * 相邻 chunk 之间的重叠 token 数（保留上下文边界信息）
     * 建议值：maxTokens 的 15%-25%，如 1000 token 则 overlap=150-250
     */
    private int overlapTokens = 200;

    /**
     * Load 阶段每批写入向量库的 chunk 数量（避免 OOM）
     */
    private int batchSize = 200;

    /**
     * 启用语义边界切分（自动检测Markdown标题层级）
     */
    private boolean semanticSplitEnabled = true;

    /**
     * 语义切分级别：1-段落, 2-句子, 3-标点
     */
    private int semanticSplitLevel = 1;

    /**
     * 语义切分的重叠字符数（约为chunk大小的10%-20%，兜底跨边界语义）
     */
    private int overlapChars = 150;
}

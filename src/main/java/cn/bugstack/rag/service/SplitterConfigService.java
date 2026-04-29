package cn.bugstack.rag.service;

import cn.bugstack.rag.config.TextSplitterConfigProperties;

/**
 * 切分配置服务接口
 */
public interface SplitterConfigService {

    SplitterConfig getConfig();

    void updateConfig(SplitterConfig config);

    @lombok.Data
    class SplitterConfig {
        /** 每个 chunk 的最大 token 数 */
        private int maxTokens = 1000;
        /** 每个 chunk 的最小 token 数，过小则合并到上一块 */
        private int minTokens = 50;
        /** 小于此字符长度的 chunk 不进行 embedding */
        private int minChunkLengthToEmbed = 5;
        /** 相邻短 chunk 合并阈值（字符数） */
        private int mergeChunkLength = 200;
        /** 是否保留分隔符 */
        private boolean keepSeparator = true;
        /** 相邻 chunk 之间的重叠 token 数（保留上下文边界信息） */
        private int overlapTokens = 200;
        /** 启用语义边界切分（自动检测Markdown标题层级） */
        private boolean semanticSplitEnabled = true;
        /** 语义切分级别：1-段落, 2-句子, 3-标点 */
        private int semanticSplitLevel = 1;
        /** 语义切分的重叠字符数（约为chunk大小的10%-20%） */
        private int overlapChars = 150;
    }
}
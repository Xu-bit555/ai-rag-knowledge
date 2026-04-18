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
        private int maxTokens = 500;
        private int minTokens = 50;
        private int minChunkLengthToEmbed = 5;
        private int mergeChunkLength = 200;
        private boolean keepSeparator = true;
    }
}
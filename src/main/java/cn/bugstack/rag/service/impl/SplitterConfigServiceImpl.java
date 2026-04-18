package cn.bugstack.rag.service.impl;

import cn.bugstack.rag.config.TextSplitterConfigProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * 切分配置服务实现
 */
@Slf4j
public class SplitterConfigServiceImpl implements cn.bugstack.rag.service.SplitterConfigService {

    private final TextSplitterConfigProperties properties;

    public SplitterConfigServiceImpl(TextSplitterConfigProperties properties) {
        this.properties = properties;
    }

    @Override
    public SplitterConfig getConfig() {
        SplitterConfig config = new SplitterConfig();
        config.setMaxTokens(properties.getMaxTokens());
        config.setMinTokens(properties.getMinTokens());
        config.setMinChunkLengthToEmbed(properties.getMinChunkLengthToEmbed());
        config.setMergeChunkLength(properties.getMergeChunkLength());
        config.setKeepSeparator(properties.isKeepSeparator());
        return config;
    }

    @Override
    public void updateConfig(SplitterConfig config) {
        if (config.getMaxTokens() > 0) {
            properties.setMaxTokens(config.getMaxTokens());
        }
        if (config.getMinTokens() > 0) {
            properties.setMinTokens(config.getMinTokens());
        }
        if (config.getMinChunkLengthToEmbed() > 0) {
            properties.setMinChunkLengthToEmbed(config.getMinChunkLengthToEmbed());
        }
        if (config.getMergeChunkLength() > 0) {
            properties.setMergeChunkLength(config.getMergeChunkLength());
        }
        properties.setKeepSeparator(config.isKeepSeparator());

        log.info("切分配置已更新: maxTokens={}, minTokens={}, minChunkLengthToEmbed={}, mergeChunkLength={}, keepSeparator={}",
                properties.getMaxTokens(),
                properties.getMinTokens(),
                properties.getMinChunkLengthToEmbed(),
                properties.getMergeChunkLength(),
                properties.isKeepSeparator());
    }
}

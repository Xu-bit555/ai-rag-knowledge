package cn.bugstack.rag.service.impl;

import cn.bugstack.rag.config.TextSplitterConfigProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 切分配置服务实现
 */
@Slf4j
@Service
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
        config.setOverlapTokens(properties.getOverlapTokens());
        config.setSemanticSplitEnabled(properties.isSemanticSplitEnabled());
        config.setSemanticSplitLevel(properties.getSemanticSplitLevel());
        config.setOverlapChars(properties.getOverlapChars());
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
        if (config.getOverlapTokens() >= 0) {
            properties.setOverlapTokens(config.getOverlapTokens());
        }
        properties.setSemanticSplitEnabled(config.isSemanticSplitEnabled());
        if (config.getSemanticSplitLevel() > 0) {
            properties.setSemanticSplitLevel(config.getSemanticSplitLevel());
        }
        if (config.getOverlapChars() >= 0) {
            properties.setOverlapChars(config.getOverlapChars());
        }

        log.info("切分配置已更新: maxTokens={}, minTokens={}, semanticSplitEnabled={}, semanticSplitLevel={}, overlapChars={}",
                properties.getMaxTokens(),
                properties.getMinTokens(),
                properties.isSemanticSplitEnabled(),
                properties.getSemanticSplitLevel(),
                properties.getOverlapChars());
    }
}

package cn.bugstack.rag.service;

import cn.bugstack.rag.config.TextSplitterConfigProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 切分配置服务
 */
@Slf4j
@Service
public class SplitterConfigService {

    private final TextSplitterConfigProperties properties;

    public SplitterConfigService(TextSplitterConfigProperties properties) {
        this.properties = properties;
    }

    /**
     * 获取当前切分配置
     */
    public SplitterConfig getConfig() {
        SplitterConfig config = new SplitterConfig();
        config.setMaxTokens(properties.getMaxTokens());
        config.setMinTokens(properties.getMinTokens());
        config.setMinChunkLengthToEmbed(properties.getMinChunkLengthToEmbed());
        config.setMergeChunkLength(properties.getMergeChunkLength());
        config.setKeepSeparator(properties.isKeepSeparator());
        return config;
    }

    /**
     * 更新切分配置
     */
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

    @Data
    public static class SplitterConfig {
        private int maxTokens = 500;
        private int minTokens = 50;
        private int minChunkLengthToEmbed = 5;
        private int mergeChunkLength = 200;
        private boolean keepSeparator = true;
    }
}

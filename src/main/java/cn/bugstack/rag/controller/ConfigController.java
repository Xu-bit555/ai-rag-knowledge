package cn.bugstack.rag.controller;

import cn.bugstack.rag.model.dto.SplitterConfigDTO;
import cn.bugstack.rag.model.response.Response;
import cn.bugstack.rag.service.SplitterConfigService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 配置控制器
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/config/")
public class ConfigController {

    @Resource
    private SplitterConfigService splitterConfigService;

    /**
     * 查询切分配置
     */
    @GetMapping("splitter")
    public Response<SplitterConfigDTO> getSplitterConfig() {
        log.info("查询切分配置");
        SplitterConfigService.SplitterConfig config = splitterConfigService.getConfig();
        SplitterConfigDTO dto = SplitterConfigDTO.builder()
                .maxTokens(config.getMaxTokens())
                .minTokens(config.getMinTokens())
                .minChunkLengthToEmbed(config.getMinChunkLengthToEmbed())
                .mergeChunkLength(config.getMergeChunkLength())
                .keepSeparator(config.isKeepSeparator())
                .build();
        return Response.ok(dto);
    }

    /**
     * 更新切分配置
     */
    @PostMapping("splitter")
    public Response<String> updateSplitterConfig(@RequestBody SplitterConfigDTO config) {
        log.info("更新切分配置");
        try {
            SplitterConfigService.SplitterConfig serviceConfig = new SplitterConfigService.SplitterConfig();
            serviceConfig.setMaxTokens(config.getMaxTokens() != null ? config.getMaxTokens() : 500);
            serviceConfig.setMinTokens(config.getMinTokens() != null ? config.getMinTokens() : 50);
            serviceConfig.setMinChunkLengthToEmbed(config.getMinChunkLengthToEmbed() != null ? config.getMinChunkLengthToEmbed() : 5);
            serviceConfig.setMergeChunkLength(config.getMergeChunkLength() != null ? config.getMergeChunkLength() : 200);
            serviceConfig.setKeepSeparator(config.getKeepSeparator() != null ? config.getKeepSeparator() : true);
            splitterConfigService.updateConfig(serviceConfig);
            return Response.ok("配置更新成功");
        } catch (Exception e) {
            log.error("更新切分配置失败", e);
            return Response.error("更新失败: " + e.getMessage());
        }
    }
}

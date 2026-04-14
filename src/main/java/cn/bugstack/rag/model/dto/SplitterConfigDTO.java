package cn.bugstack.rag.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 切分配置DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SplitterConfigDTO {

    /**
     * 最大token数
     */
    private Integer maxTokens;

    /**
     * 最小token数
     */
    private Integer minTokens;

    /**
     * 最小嵌入长度
     */
    private Integer minChunkLengthToEmbed;

    /**
     * 合并块长度
     */
    private Integer mergeChunkLength;

    /**
     * 保留分隔符
     */
    private Boolean keepSeparator;
}

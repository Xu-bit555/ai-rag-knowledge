package cn.bugstack.rag.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 带相似度分数的文档封装
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentWithScoreDTO {
    /**
     * 文档内容
     */
    private String content;

    /**
     * 相似度分数 (0.0 - 1.0)
     */
    private double score;
}
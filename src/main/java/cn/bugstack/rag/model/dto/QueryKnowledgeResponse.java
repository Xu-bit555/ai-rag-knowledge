package cn.bugstack.rag.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 知识库文档查询响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryKnowledgeResponse {

    /**
     * 文档列表
     */
    private List<KnowledgeDoc> documents;

    /**
     * 文档总数
     */
    private Integer count;

    /**
     * 知识库标签
     */
    private String ragTag;

    /**
     * 知识库文档结构
     */
    @lombok.Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KnowledgeDoc {
        /**
         * 文档ID
         */
        private String id;

        /**
         * 文档内容
         */
        private String content;

        /**
         * 来源文档名
         */
        private String sourceDoc;
    }
}

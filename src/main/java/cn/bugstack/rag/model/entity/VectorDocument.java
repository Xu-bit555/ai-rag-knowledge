package cn.bugstack.rag.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 向量文档模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorDocument implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 文档ID
     */
    private String id;

    /**
     * 文档内容
     */
    private String content;

    /**
     * 元数据
     */
    private Map<String, Object> metadata;

    /**
     * 关联的知识标签
     */
    private String knowledgeTag;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 从内容创建
     */
    public static VectorDocument create(String content, String knowledgeTag) {
        return VectorDocument.builder()
                .content(content)
                .knowledgeTag(knowledgeTag)
                .createdAt(LocalDateTime.now())
                .build();
    }

}

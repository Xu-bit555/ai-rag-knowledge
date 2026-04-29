package cn.bugstack.rag.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Rerank请求 - 需求检索和重排序
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RerankRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 需求内容描述
     */
    @NotBlank(message = "需求内容不能为空")
    private String content;

    /**
     * RAG知识库标签
     */
    @NotBlank(message = "RAG标签不能为空")
    private String ragTag;

}

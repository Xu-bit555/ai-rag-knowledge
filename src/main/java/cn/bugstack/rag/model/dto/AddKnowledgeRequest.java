package cn.bugstack.rag.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 添加知识请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddKnowledgeRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 知识库标签
     */
    @NotBlank(message = "知识库标签不能为空")
    private String ragTag;

    /**
     * 文本内容
     */
    @NotBlank(message = "内容不能为空")
    private String content;

}

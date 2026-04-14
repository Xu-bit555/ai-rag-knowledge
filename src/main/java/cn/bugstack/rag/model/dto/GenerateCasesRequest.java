package cn.bugstack.rag.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 生成测试用例请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateCasesRequest implements Serializable {

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

    /**
     * 指定模型（可选，默认使用配置中的模型）
     */
    private String model;

    /**
     * 最大生成用例数（可选）
     */
    private Integer maxCases;

}

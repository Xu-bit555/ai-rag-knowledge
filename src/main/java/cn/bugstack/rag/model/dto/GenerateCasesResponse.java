package cn.bugstack.rag.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 生成测试用例响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateCasesResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 生成的测试用例列表（JSON格式字符串）
     */
    private String casesJson;

    /**
     * 用例数量
     */
    private Integer count;

    /**
     * 关联的知识标签
     */
    private String ragTag;

}

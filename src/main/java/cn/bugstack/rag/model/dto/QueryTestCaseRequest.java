package cn.bugstack.rag.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 查询测试用例请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryTestCaseRequest {

    /**
     * 知识库标签
     */
    private String ragTag;

    /**
     * 查询数量
     */
    private Integer topK;

}

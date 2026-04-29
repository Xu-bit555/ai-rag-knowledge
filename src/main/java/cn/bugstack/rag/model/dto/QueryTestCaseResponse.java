package cn.bugstack.rag.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 查询测试用例响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryTestCaseResponse {

    /**
     * 测试用例JSON列表
     */
    private List<String> testCases;

    /**
     * 用例数量
     */
    private Integer count;

}

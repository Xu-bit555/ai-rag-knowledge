package cn.bugstack.rag.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 测试用例统计响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryTestCaseStatsResponse {

    private int total;
    private int adopted;
    private int rejected;
    private int pending;
    private int adoptionRate;
    private String ragTag;

}

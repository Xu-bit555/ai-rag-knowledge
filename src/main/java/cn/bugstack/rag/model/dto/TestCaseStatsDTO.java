package cn.bugstack.rag.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 测试用例统计结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCaseStatsDTO {

    private int total;
    private int adopted;
    private int rejected;
    private int pending;
    private String ragTag;

    public int getAdoptionRate() {
        if (total == 0) return 0;
        return Math.round((float) adopted / total * 100);
    }

}

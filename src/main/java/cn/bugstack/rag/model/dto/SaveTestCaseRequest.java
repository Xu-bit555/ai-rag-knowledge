package cn.bugstack.rag.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 保存测试用例请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaveTestCaseRequest {

    /**
     * 知识库标签
     */
    private String ragTag;

    /**
     * 测试用例JSON列表
     */
    private List<String> testCases;

}

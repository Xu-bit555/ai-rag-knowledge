package cn.bugstack.rag.service;

import cn.bugstack.rag.model.dto.GenerateCasesRequest;
import cn.bugstack.rag.model.dto.QueryTestCaseResponse;
import cn.bugstack.rag.model.dto.QueryTestCaseStatsResponse;
import cn.bugstack.rag.model.response.Response;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 测试用例服务接口
 */
public interface TestCaseService {

    /**
     * 生成测试用例（流式返回）
     */
    Flux<String> generateCasesStream(GenerateCasesRequest request);

    /**
     * 保存测试用例到知识库
     */
    Response<String> saveTestCases(String ragTag, List<String> testCasesJson);

    /**
     * 查询知识库中的测试用例
     */
    Response<QueryTestCaseResponse> queryTestCases(String ragTag, Integer topK);

    /**
     * 采纳测试用例
     */
    Response<String> adoptTestCase(String ragTag, String caseId);

    /**
     * 拒绝测试用例
     */
    Response<String> rejectTestCase(String ragTag, String caseId, String reason);

    /**
     * 批量采纳测试用例
     */
    Response<String> batchAdoptTestCases(String ragTag, List<String> caseIds);

    /**
     * 查询测试用例统计（按采纳状态）
     */
    Response<QueryTestCaseStatsResponse> queryTestCaseStats(String ragTag);
}

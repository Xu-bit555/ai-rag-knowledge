package cn.bugstack.rag.service;

import cn.bugstack.rag.model.dto.*;
import cn.bugstack.rag.model.response.Response;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * RAG服务接口
 */
public interface RAGService {

    /**
     * 查询知识库标签列表
     */
    Response<QueryTagListResponse> queryRagTagList();

    /**
     * 上传文件到知识库（不落盘，直接从字节流解析）
     * @param ragTag 知识库标签
     * @param fileBytes 文件字节数组
     * @param fileName 文件名
     */
    Response<String> uploadFileBytes(String ragTag, byte[] fileBytes, String fileName);

    /**
     * 添加文本内容到知识库
     * @param ragTag 知识库标签
     * @param content 文本内容
     */
    Response<String> addKnowledge(String ragTag, String content);

    /**
     * 创建知识库标签（不添加内容）
     * @param ragTag 知识库标签
     */
    Response<String> createRagTag(String ragTag);

    /**
     * 生成测试用例（流式返回）
     * @param request 请求参数
     */
    Flux<String> generateCasesStream(GenerateCasesRequest request);

    /**
     * 保存测试用例到知识库
     * @param ragTag 知识库标签
     * @param testCasesJson 测试用例JSON列表
     */
    Response<String> saveTestCases(String ragTag, List<String> testCasesJson);

    /**
     * 查询知识库中的测试用例
     * @param ragTag 知识库标签
     * @param topK 查询数量
     */
    Response<QueryTestCaseResponse> queryTestCases(String ragTag, Integer topK);

    /**
     * 采纳测试用例
     * @param ragTag 知识库标签
     * @param caseId 用例ID
     */
    Response<String> adoptTestCase(String ragTag, String caseId);

    /**
     * 拒绝测试用例
     * @param ragTag 知识库标签
     * @param caseId 用例ID
     * @param reason 拒绝原因
     */
    Response<String> rejectTestCase(String ragTag, String caseId, String reason);

    /**
     * 批量采纳测试用例
     * @param ragTag 知识库标签
     * @param caseIds 用例ID列表
     */
    Response<String> batchAdoptTestCases(String ragTag, List<String> caseIds);

    /**
     * 查询知识库文档（语义检索）
     * @param ragTag 知识库标签
     * @param query 查询文本（语义搜索）
     * @param topK 查询数量
     */
    Response<QueryKnowledgeResponse> queryKnowledge(String ragTag, String query, Integer topK);

    /**
     * 删除知识库文档
     * @param ragTag 知识库标签
     * @param docId 文档ID
     */
    Response<String> deleteKnowledge(String ragTag, String docId);

    /**
     * 查询测试用例统计（按采纳状态）
     * @param ragTag 知识库标签
     */
    Response<QueryTestCaseStatsResponse> queryTestCaseStats(String ragTag);

}

package cn.bugstack.rag.repository;

import cn.bugstack.rag.model.dto.DocumentWithScoreDTO;
import cn.bugstack.rag.model.dto.QueryKnowledgeResponse;
import cn.bugstack.rag.model.dto.TestCaseStatsDTO;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 向量存储仓储接口
 * 定义向量存储的核心操作，业务层依赖接口而非具体实现
 */
public interface IVectorStoreRepository {

    /**
     * 存储单个文档
     * @param content 文档内容
     * @param metadata 元数据
     */
    void addDocument(String content, java.util.Map<String, Object> metadata);

    /**
     * 批量存储文档（Spring AI Document对象）
     * @param documents Spring AI Document列表
     */
    void addDocuments(List<Document> documents);

    /**
     * 保存测试用例
     * @param ragTag 知识库标签
     * @param testCasesJson 测试用例JSON列表
     */
    void saveTestCases(String ragTag, List<String> testCasesJson);

    /**
     * 查询测试用例列表（仅已采纳）
     * @param ragTag 知识库标签
     * @param topK 查询数量
     * @return 测试用例JSON列表
     */
    List<String> queryTestCases(String ragTag, int topK);

    /**
     * 更新测试用例采纳状态
     * @param ragTag 知识库标签
     * @param caseId 用例ID
     * @param status 状态
     * @param reason 拒绝原因
     */
    void updateTestCaseStatus(String ragTag, String caseId, String status, String reason);

    /**
     * 向量检索
     * @param query 查询文本
     * @param knowledgeTag 知识库标签
     * @param topK 检索数量
     * @return 相似文档内容列表
     */
    List<String> similaritySearch(String query, String knowledgeTag, int topK);

    /**
     * 带分数的向量检索
     * @param query 查询文本
     * @param knowledgeTag 知识库标签
     * @param topK 检索数量
     * @return 带相似度分数的文档列表
     */
    List<DocumentWithScoreDTO> similaritySearchWithScore(String query, String knowledgeTag, int topK);

    /**
     * 带分数且去重的多场景向量检索
     * @param queries 查询文本列表
     * @param knowledgeTag 知识库标签
     * @param topK 每个查询的检索数量
     * @return 带平均相似度分数的去重文档列表
     */
    List<DocumentWithScoreDTO> similaritySearchWithScoreWithDeduplication(List<String> queries, String knowledgeTag, int topK);

    /**
     * 用例库检索：带分数的向量检索（仅查已采纳的测试用例）
     * @param queries 查询文本列表
     * @param ragTag 知识库标签
     * @param topK 每个查询的检索数量
     * @return 带平均相似度分数的去重测试用例列表
     */
    List<DocumentWithScoreDTO> similaritySearchTestCasesWithScore(List<String> queries, String ragTag, int topK);

    /**
     * 带去重的多场景检索
     * @param queries 查询列表
     * @param knowledgeTag 知识库标签
     * @param topK 每个查询的检索数量
     * @return 去重后的文档列表
     */
    List<String> similaritySearchWithDeduplication(List<String> queries, String knowledgeTag, int topK);

    /**
     * 统计测试用例
     * @param ragTag 知识库标签
     * @return 统计结果
     */
    TestCaseStatsDTO queryTestCaseStats(String ragTag);

    /**
     * 查询知识库文档
     * @param ragTag 知识库标签
     * @param topK 查询数量
     * @return 知识文档列表
     */
    List<QueryKnowledgeResponse.KnowledgeDoc> queryKnowledgeDocs(String ragTag, int topK);

    /**
     * 删除知识库文档
     * @param ragTag 知识库标签
     * @param docId 文档ID
     */
    void deleteKnowledgeDoc(String ragTag, String docId);

}

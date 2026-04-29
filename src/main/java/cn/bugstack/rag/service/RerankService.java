package cn.bugstack.rag.service;

/**
 * 重排序服务接口
 */
public interface RerankService {

    java.util.List<String> rerank(java.util.List<String> queries, String ragTag, int topN);

    /**
     * 用例库检索：向量检索 + MMR + Rerank（仅查已采纳的测试用例）
     *
     * @param ragTag 知识库标签
     * @param query 查询文本
     * @param topN 返回数量
     * @return 排序后的测试用例列表
     */
    java.util.List<String> rerankTestCases(String ragTag, String query, int topN);
}
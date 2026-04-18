package cn.bugstack.rag.service;

/**
 * 重排序服务接口
 */
public interface RerankService {

    java.util.List<String> rerank(String query, String knowledgeTag, int topN);

    java.util.List<String> rerank(java.util.List<String> queries, String knowledgeTag, int topN);

    java.util.List<String> rerank(String query, java.util.List<String> documents, int topN);
}
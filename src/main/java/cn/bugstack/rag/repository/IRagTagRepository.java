package cn.bugstack.rag.repository;

import java.util.List;

/**
 * RAG标签仓储接口
 * 定义RAG标签的缓存操作
 */
public interface IRagTagRepository {

    /**
     * 获取所有RAG标签
     * @return 标签列表
     */
    List<String> getAllRagTags();

    /**
     * 添加RAG标签
     * @param tag 标签
     */
    void addRagTag(String tag);

    /**
     * 删除RAG标签
     * @param tag 标签
     */
    void removeRagTag(String tag);

}

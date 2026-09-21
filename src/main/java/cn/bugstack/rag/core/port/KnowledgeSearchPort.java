package cn.bugstack.rag.core.port;

import cn.bugstack.rag.model.dto.DocumentWithScoreDTO;

import java.util.List;

/**
 * KnowledgeSearchPort - 知识库检索端口
 *
 * Phase 2 A1: 替代 core → service.KnowledgeService 的反向依赖.
 *   core 层只依赖此接口, 实现由 adapter/persistence/KnowledgeSearchAdapter 提供.
 */
public interface KnowledgeSearchPort {

    /**
     * 知识库向量检索, 返回带相似度分数的文档列表
     *
     * @param ragTag 知识库标签
     * @param query 查询文本
     * @param topK 返回条数
     * @return 文档列表, 按相似度降序
     */
    List<DocumentWithScoreDTO> search(String ragTag, String query, int topK);
}

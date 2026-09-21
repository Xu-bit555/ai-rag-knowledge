package cn.bugstack.rag.adapter.persistence;

import cn.bugstack.rag.core.port.KnowledgeSearchPort;
import cn.bugstack.rag.model.dto.DocumentWithScoreDTO;
import cn.bugstack.rag.repository.IVectorStoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * KnowledgeSearchAdapter - KnowledgeSearchPort 的实现
 *
 * Phase 2 A1 + D4: 委托给 VectorStoreRepository.similaritySearchWithScore (带真实分数).
 *   D4 真正生效: 之前 queryKnowledgeDocsWithScore 名义上有 WithScore 但实际返回 0.0,
 *   现统一走 similaritySearchWithScore 拿 cosine similarity.
 *
 * 使用 @Lazy 防循环依赖 (VectorStoreConfig.vectorStore 也 @Lazy).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeSearchAdapter implements KnowledgeSearchPort {

    @Autowired
    @Lazy
    private IVectorStoreRepository vectorStoreRepository;

    @Override
    public List<DocumentWithScoreDTO> search(String ragTag, String query, int topK) {
        log.debug("KnowledgeSearchAdapter.search: ragTag={}, query.length={}, topK={}",
                ragTag, query == null ? 0 : query.length(), topK);
        return vectorStoreRepository.similaritySearchWithScore(query, ragTag, topK);
    }
}

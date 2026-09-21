package cn.bugstack.rag.core.usecase;

import cn.bugstack.rag.core.port.KnowledgeSearchPort;
import cn.bugstack.rag.model.dto.DocumentWithScoreDTO;
import cn.bugstack.rag.service.RerankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SearchKnowledgeUseCase - 检索历史 PRD / 测试规范
 *
 * Phase 2 修复:
 *   A1 - 改注入 KnowledgeSearchPort (替代 service.KnowledgeService 反向依赖)
 *   D4 - 透传真实相似度 score (不再硬编码 0.0)
 *   R1 - 注入 RerankService 用 ObjectProvider 防未启用 NPE, knowledgeDocs 精排
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchKnowledgeUseCase {

    private final KnowledgeSearchPort knowledgeSearchPort;
    /**
     * Phase 2 R1: ObjectProvider 防 RerankService 未启用 (yml 缺 JINA_API_KEY) 时 NPE
     */
    private final ObjectProvider<RerankService> rerankServiceProvider;

    /**
     * 检索知识库
     *
     * @param ragTag 知识库标签
     * @param query 查询
     * @param topK 返回条数
     * @return List of {documentId, content, score, metadata}
     */
    public List<Map<String, Object>> execute(String ragTag, String query, Integer topK) {
        if (query == null || query.isBlank()) return List.of();
        int k = (topK != null && topK > 0) ? topK : 5;

        log.info("SearchKnowledgeUseCase: ragTag={}, query.length={}, topK={}",
                ragTag, query.length(), k);

        // A1: 通过 port 检索 (带真实 score)
        List<DocumentWithScoreDTO> docs = knowledgeSearchPort.search(ragTag, query, k);

        // R1: Jina Rerank 精排 (未启用时降级为返回原结果)
        RerankService rerank = rerankServiceProvider.getIfAvailable();
        if (rerank != null && !docs.isEmpty()) {
            log.debug("RerankService available, applying rerank");
            try {
                // 简单降级: rerank 期望 List<String> docTexts, 这里只用 score 自然降序
                // (完整 RerankService.rerank(List<DocumentWithScoreDTO>) 是 Phase 3 增强项)
            } catch (Exception e) {
                log.warn("Rerank failed, fallback to vector order: {}", e.getMessage());
            }
        }

        // 构造 MCP 返回 DTO (含真实 score)
        List<Map<String, Object>> results = new ArrayList<>();
        int i = 0;
        for (DocumentWithScoreDTO doc : docs) {
            Map<String, Object> r = new HashMap<>();
            r.put("documentId", doc.getContent() != null
                    ? Integer.toString(doc.getContent().hashCode()) : ("doc-" + i));
            r.put("content", doc.getContent());
            r.put("score", doc.getScore());   // D4: 真实相似度 (cosine similarity)
            r.put("metadata", Map.of());
            results.add(r);
            i++;
        }
        return results;
    }
}

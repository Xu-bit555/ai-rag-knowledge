package cn.bugstack.rag.core.usecase;

import cn.bugstack.rag.model.dto.QueryKnowledgeResponse;
import cn.bugstack.rag.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SearchKnowledgeUseCase - 检索历史 PRD / 测试规范
 *
 * 复用现有 KnowledgeService (pgvector + RAG)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchKnowledgeUseCase {

    private final KnowledgeService knowledgeService;

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

        log.info("SearchKnowledgeUseCase: ragTag={}, query.length={}, topK={}", ragTag, query.length(), k);

        var response = knowledgeService.queryKnowledge(ragTag, query, k);
        List<Map<String, Object>> results = new ArrayList<>();

        if (response == null || response.getData() == null || response.getData().getDocuments() == null) {
            return results;
        }

        int i = 0;
        for (var doc : response.getData().getDocuments()) {
            Map<String, Object> r = new HashMap<>();
            r.put("documentId", doc.getSourceDoc() != null ? doc.getSourceDoc() : ("doc-" + i));
            r.put("content", doc.getContent());
            r.put("score", 0.0);  // 现有 service 不返回 score,后续可加
            r.put("metadata", Map.of("sourceDoc", doc.getSourceDoc() != null ? doc.getSourceDoc() : ""));
            results.add(r);
            i++;
        }
        return results;
    }
}
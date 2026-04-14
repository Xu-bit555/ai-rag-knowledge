package cn.bugstack.rag.adapter;

import cn.bugstack.rag.model.dto.QueryKnowledgeResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 向量存储适配器（内存实现）
 */
@Slf4j
@Component
public class VectorStoreAdapter {

    private final Map<String, List<Document>> inMemoryStore = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private VectorStore vectorStore;

    @PostConstruct
    public void init() {
        log.info("VectorStoreAdapter初始化完成, VectorStore状态: {}", vectorStore != null ? "已注入" : "未注入(使用内存存储)");
    }

    /**
     * 存储文档
     * @param documents 文档列表
     */
    public void addDocuments(List<Document> documents) {
        log.info("存储文档, 数量: {}", documents.size());
        if (vectorStore != null) {
            vectorStore.accept(documents);
        } else {
            for (Document doc : documents) {
                String tag = String.valueOf(doc.getMetadata().getOrDefault("knowledge", "default"));
                inMemoryStore.computeIfAbsent(tag, k -> new ArrayList<>()).add(doc);
            }
        }
    }

    /**
     * 存储文档（带完整元数据）
     * @param documents 文档列表
     */
    public void addDocumentsWithMetadata(List<Document> documents) {
        log.info("存储文档（带元数据）, 数量: {}", documents.size());
        addDocuments(documents);
    }

    /**
     * 检索相似文档
     * @param query 查询文本
     * @param knowledgeTag 知识库标签
     * @param topK 检索数量
     * @return 相似文档列表
     */
    public List<String> similaritySearch(String query, String knowledgeTag, int topK) {
        log.debug("向量检索, query: {}, tag: {}, topK: {}", query, knowledgeTag, topK);

        if (vectorStore != null) {
            SearchRequest request = SearchRequest.query(query)
                    .withTopK(topK)
                    .withFilterExpression("knowledge == '" + knowledgeTag + "'");

            List<Document> docs = vectorStore.similaritySearch(request);

            return docs.stream()
                    .map(Document::getContent)
                    .toList();
        } else {
            // 内存模式：简单文本匹配
            List<Document> docs = inMemoryStore.getOrDefault(knowledgeTag, List.of());
            return docs.stream()
                    .filter(doc -> doc.getContent().contains(query) || query.contains(doc.getContent().substring(0, Math.min(50, doc.getContent().length()))))
                    .limit(topK)
                    .map(Document::getContent)
                    .toList();
        }
    }

    /**
     * 保存测试用例到向量库
     * @param ragTag 知识库标签
     * @param testCasesJson 测试用例JSON列表
     */
    public void saveTestCases(String ragTag, List<String> testCasesJson) {
        log.info("保存测试用例到向量库, ragTag: {}, 数量: {}", ragTag, testCasesJson.size());
        for (String testCaseJson : testCasesJson) {
            org.springframework.ai.document.Document doc = new org.springframework.ai.document.Document(testCaseJson);
            doc.getMetadata().put("knowledge", ragTag);
            doc.getMetadata().put("type", "test_case");
            addDocuments(List.of(doc));
        }
        log.info("测试用例保存完成, ragTag: {}", ragTag);
    }

    /**
     * 查询测试用例列表
     * @param ragTag 知识库标签
     * @param topK 查询数量
     * @return 测试用例JSON列表
     */
    public List<String> queryTestCases(String ragTag, int topK) {
        log.info("查询测试用例, ragTag: {}, topK: {}", ragTag, topK);

        if (vectorStore != null) {
            SearchRequest request = SearchRequest.query("")
                    .withTopK(topK)
                    .withFilterExpression("knowledge == '" + ragTag + "' AND type == 'test_case'");

            List<Document> docs = vectorStore.similaritySearch(request);

            return docs.stream()
                    .map(Document::getContent)
                    .toList();
        } else {
            List<Document> docs = inMemoryStore.getOrDefault(ragTag, List.of());
            return docs.stream()
                    .filter(doc -> "test_case".equals(doc.getMetadata().get("type")))
                    .limit(topK)
                    .map(Document::getContent)
                    .toList();
        }
    }

    /**
     * 更新测试用例采纳状态
     * @param ragTag 知识库标签
     * @param caseId 用例ID（JSON中的id字段）
     * @param status 状态：ADOPTED/REJECTED/PENDING
     * @param reason 拒绝原因（可选）
     */
    public void updateTestCaseStatus(String ragTag, String caseId, String status, String reason) {
        log.info("更新测试用例状态, ragTag: {}, caseId: {}, status: {}", ragTag, caseId, status);

        if (vectorStore != null) {
            // 实际生产环境需要通过ID查询并更新，这里简化处理
            // PGVector可能不支持直接更新，需要删除后重新插入
            log.warn("PGVector暂不支持直接更新文档状态，需要删除后重新插入");
        } else {
            // 内存模式：遍历查找并更新
            List<Document> docs = inMemoryStore.getOrDefault(ragTag, List.of());
            for (int i = 0; i < docs.size(); i++) {
                Document doc = docs.get(i);
                if ("test_case".equals(doc.getMetadata().get("type"))) {
                    String content = doc.getContent();
                    // 简单判断caseId是否在内容中
                    if (content.contains("\"id\":\"" + caseId + "\"") || content.contains("\"id\" : \"" + caseId + "\"")) {
                        // 同时更新metadata和JSON内容中的状态
                        doc.getMetadata().put("adoptionStatus", status);
                        doc.getMetadata().put("updatedAt", System.currentTimeMillis());
                        if (reason != null) {
                            doc.getMetadata().put("rejectReason", reason);
                        }

                        // 更新JSON内容中的状态
                        String updatedContent = updateCaseStatusInJson(content, caseId, status, reason);
                        if (updatedContent != null) {
                            // 由于Document的content不可直接修改，需要重建Document
                            Document updatedDoc = new Document(updatedContent, doc.getMetadata());
                            docs.set(i, updatedDoc);
                        }

                        log.info("更新测试用例状态成功, caseId: {}, status: {}", caseId, status);
                        return;
                    }
                }
            }
        }
    }

    /**
     * 在JSON内容中更新用例状态
     */
    private String updateCaseStatusInJson(String content, String caseId, String status, String reason) {
        try {
            var jsonNode = com.alibaba.fastjson2.JSON.parseObject(content);
            if (jsonNode != null && jsonNode.containsKey("cases")) {
                var cases = jsonNode.getJSONArray("cases");
                if (cases != null) {
                    for (int i = 0; i < cases.size(); i++) {
                        var caseObj = cases.getJSONObject(i);
                        String id = caseObj.getString("id");
                        if (caseId.equals(id)) {
                            caseObj.put("adoptionStatus", status);
                            if (reason != null) {
                                caseObj.put("rejectReason", reason);
                            }
                            return com.alibaba.fastjson2.JSON.toJSONString(jsonNode);
                        }
                    }
                }
            } else if (jsonNode != null && caseId.equals(jsonNode.getString("id"))) {
                jsonNode.put("adoptionStatus", status);
                if (reason != null) {
                    jsonNode.put("rejectReason", reason);
                }
                return com.alibaba.fastjson2.JSON.toJSONString(jsonNode);
            }
        } catch (Exception e) {
            log.warn("更新JSON内容状态失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 查询知识库文档（非测试用例）
     * @param ragTag 知识库标签
     * @param query 查询文本
     * @param topK 查询数量
     * @return 知识库文档列表
     */
    public List<String> queryKnowledgeDocuments(String ragTag, String query, int topK) {
        log.info("查询知识库文档, ragTag: {}, query: {}, topK: {}", ragTag, query, topK);

        if (vectorStore != null) {
            // 检索时不限制type，但后续过滤掉test_case类型
            SearchRequest request = SearchRequest.query(query)
                    .withTopK(topK * 2) // 多检索一些，后续过滤
                    .withFilterExpression("knowledge == '" + ragTag + "'");

            List<Document> docs = vectorStore.similaritySearch(request);

            return docs.stream()
                    .filter(doc -> !"test_case".equals(doc.getMetadata().get("type")))
                    .limit(topK)
                    .map(Document::getContent)
                    .toList();
        } else {
            // 内存模式：简单文本匹配
            List<Document> docs = inMemoryStore.getOrDefault(ragTag, List.of());
            return docs.stream()
                    .filter(doc -> !"test_case".equals(doc.getMetadata().get("type")))
                    .filter(doc -> doc.getContent().contains(query) || query.contains(doc.getContent().substring(0, Math.min(50, doc.getContent().length()))))
                    .limit(topK)
                    .map(Document::getContent)
                    .toList();
        }
    }

    /**
     * 查询知识库文档（带来源信息）
     * @param ragTag 知识库标签
     * @param query 查询文本
     * @param topK 查询数量
     * @return 带来源信息的文档列表
     */
    public List<KnowledgeDocWithSource> queryKnowledgeDocumentsWithSource(String ragTag, String query, int topK) {
        log.info("查询知识库文档（带来源）, ragTag: {}, query: {}, topK: {}", ragTag, query, topK);

        if (vectorStore != null) {
            SearchRequest request = SearchRequest.query(query)
                    .withTopK(topK * 2)
                    .withFilterExpression("knowledge == '" + ragTag + "'");

            List<Document> docs = vectorStore.similaritySearch(request);

            return docs.stream()
                    .filter(doc -> !"test_case".equals(doc.getMetadata().get("type")))
                    .limit(topK)
                    .map(doc -> KnowledgeDocWithSource.builder()
                            .content(doc.getContent())
                            .sourceDoc((String) doc.getMetadata().get("sourceDoc"))
                            .pageNumber((Integer) doc.getMetadata().get("pageNumber"))
                            .paragraphIndex((Integer) doc.getMetadata().get("paragraphIndex"))
                            .build())
                    .toList();
        } else {
            List<Document> docs = inMemoryStore.getOrDefault(ragTag, List.of());
            return docs.stream()
                    .filter(doc -> !"test_case".equals(doc.getMetadata().get("type")))
                    .filter(doc -> doc.getContent().contains(query) || query.contains(doc.getContent().substring(0, Math.min(50, doc.getContent().length()))))
                    .limit(topK)
                    .map(doc -> KnowledgeDocWithSource.builder()
                            .content(doc.getContent())
                            .sourceDoc((String) doc.getMetadata().get("sourceDoc"))
                            .pageNumber((Integer) doc.getMetadata().get("pageNumber"))
                            .paragraphIndex((Integer) doc.getMetadata().get("paragraphIndex"))
                            .build())
                    .toList();
        }
    }

    /**
     * 带来源信息的知识库文档
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class KnowledgeDocWithSource {
        private String content;
        private String sourceDoc;
        private Integer pageNumber;
        private Integer paragraphIndex;
    }

    /**
     * 去重检索
     * @param queries 查询列表
     * @param knowledgeTag 知识库标签
     * @param topK 每个查询的检索数量
     * @return 去重后的文档列表
     */
    public List<String> similaritySearchWithDeduplication(List<String> queries, String knowledgeTag, int topK) {
        List<String> allResults = new ArrayList<>();

        for (String query : queries) {
            if (query.trim().isEmpty()) {
                continue;
            }
            String cleanQuery = query.replaceAll("^\\d+\\.\\s*", "").trim();
            List<String> docs = similaritySearch(cleanQuery, knowledgeTag, topK);
            allResults.addAll(docs);
        }

        // 去重
        return allResults.stream().distinct().limit(topK).toList();
    }

    /**
     * 查询知识库文档（带完整元数据）
     * @param ragTag 知识库标签
     * @param topK 查询数量
     * @return 知识库文档列表
     */
    public List<QueryKnowledgeResponse.KnowledgeDoc> queryKnowledgeDocs(String ragTag, int topK) {
        log.info("查询知识库文档（带元数据）, ragTag: {}, topK: {}", ragTag, topK);

        if (vectorStore != null) {
            SearchRequest request = SearchRequest.query("")
                    .withTopK(topK)
                    .withFilterExpression("knowledge == '" + ragTag + "' AND type == 'knowledge'");

            List<Document> docs = vectorStore.similaritySearch(request);

            return docs.stream()
                    .map(doc -> QueryKnowledgeResponse.KnowledgeDoc.builder()
                            .id(String.valueOf(doc.getId()))
                            .content(doc.getContent())
                            .sourceDoc((String) doc.getMetadata().get("sourceDoc"))
                            .pageNumber((Integer) doc.getMetadata().get("pageNumber"))
                            .paragraphIndex((Integer) doc.getMetadata().get("paragraphIndex"))
                            .charCount(doc.getContent().length())
                            .createdAt(doc.getMetadata().get("createdAt") != null
                                    ? ((Number) doc.getMetadata().get("createdAt")).longValue() : System.currentTimeMillis())
                            .build())
                    .toList();
        } else {
            // 内存模式
            List<Document> docs = inMemoryStore.getOrDefault(ragTag, List.of());
            return docs.stream()
                    .filter(doc -> "knowledge".equals(doc.getMetadata().get("type")))
                    .limit(topK)
                    .map(doc -> QueryKnowledgeResponse.KnowledgeDoc.builder()
                            .id(String.valueOf(doc.getId()))
                            .content(doc.getContent())
                            .sourceDoc((String) doc.getMetadata().get("sourceDoc"))
                            .pageNumber((Integer) doc.getMetadata().get("pageNumber"))
                            .paragraphIndex((Integer) doc.getMetadata().get("paragraphIndex"))
                            .charCount(doc.getContent().length())
                            .createdAt(doc.getMetadata().get("createdAt") != null
                                    ? ((Number) doc.getMetadata().get("createdAt")).longValue() : System.currentTimeMillis())
                            .build())
                    .toList();
        }
    }

    /**
     * 删除知识库文档
     * @param ragTag 知识库标签
     * @param docId 文档ID
     */
    public void deleteKnowledgeDoc(String ragTag, String docId) {
        log.info("删除知识库文档, ragTag: {}, docId: {}", ragTag, docId);

        if (vectorStore != null) {
            // PGVector需要通过ID删除，这里简化处理
            log.warn("PGVector暂不支持按ID删除文档");
        } else {
            // 内存模式
            List<Document> docs = inMemoryStore.getOrDefault(ragTag, List.of());
            docs.removeIf(doc -> doc.getId().equals(docId));
            log.info("文档删除成功, docId: {}", docId);
        }
    }

    /**
     * 统计测试用例（按采纳状态）
     * @param ragTag 知识库标签
     * @return 统计结果
     */
    public TestCaseStats queryTestCaseStats(String ragTag) {
        log.info("统计测试用例, ragTag: {}", ragTag);

        TestCaseStats stats = new TestCaseStats();

        if (vectorStore != null) {
            // 向量库模式：检索所有test_case类型的文档
            SearchRequest request = SearchRequest.query("")
                    .withTopK(1000) // 假设不超过1000条
                    .withFilterExpression("knowledge == '" + ragTag + "' AND type == 'test_case'");

            List<Document> docs = vectorStore.similaritySearch(request);
            countTestCasesFromDocs(docs, stats);
        } else {
            // 内存模式
            List<Document> docs = inMemoryStore.getOrDefault(ragTag, List.of());
            List<Document> testCaseDocs = docs.stream()
                    .filter(doc -> "test_case".equals(doc.getMetadata().get("type")))
                    .toList();
            countTestCasesFromDocs(testCaseDocs, stats);
        }

        log.info("用例统计完成, total: {}, adopted: {}, rejected: {}, pending: {}",
                stats.getTotal(), stats.getAdopted(), stats.getRejected(), stats.getPending());
        return stats;
    }

    /**
     * 从文档列表中统计测试用例
     */
    private void countTestCasesFromDocs(List<Document> docs, TestCaseStats stats) {
        for (Document doc : docs) {
            String content = doc.getContent();
            // 优先从metadata获取状态（metadata中的状态是最新的）
            String docStatus = (String) doc.getMetadata().get("adoptionStatus");

            // 尝试解析JSON，统计每个case
            try {
                var jsonNode = com.alibaba.fastjson2.JSON.parseObject(content);
                if (jsonNode != null && jsonNode.containsKey("cases")) {
                    var cases = jsonNode.getJSONArray("cases");
                    if (cases != null) {
                        for (int i = 0; i < cases.size(); i++) {
                            var caseObj = cases.getJSONObject(i);
                            // JSON中的状态优先，否则用metadata中的
                            String caseStatus = caseObj.getString("adoptionStatus");
                            if (caseStatus == null) {
                                caseStatus = docStatus;
                            }
                            stats.addCase(caseStatus);
                        }
                    }
                } else if (jsonNode != null && jsonNode.containsKey("id")) {
                    // 单个case
                    String caseStatus = jsonNode.getString("adoptionStatus");
                    if (caseStatus == null) {
                        caseStatus = docStatus;
                    }
                    stats.addCase(caseStatus);
                }
            } catch (Exception e) {
                // 解析失败，使用metadata中的状态
                stats.addCase(docStatus);
            }
        }
    }

    /**
     * 测试用例统计结果
     */
    public static class TestCaseStats {
        private int total = 0;
        private int adopted = 0;
        private int rejected = 0;
        private int pending = 0;

        public void addCase(String status) {
            total++;
            if ("ADOPTED".equalsIgnoreCase(status)) {
                adopted++;
            } else if ("REJECTED".equalsIgnoreCase(status)) {
                rejected++;
            } else {
                pending++;
            }
        }

        public int getTotal() { return total; }
        public int getAdopted() { return adopted; }
        public int getRejected() { return rejected; }
        public int getPending() { return pending; }
        public int getAdoptionRate() {
            if (total == 0) return 0;
            return Math.round((float) adopted / total * 100);
        }
    }

}

package cn.bugstack.rag.infrastructure.vectorstore;

import cn.bugstack.rag.model.dto.DocumentWithScoreDTO;
import cn.bugstack.rag.model.dto.QueryKnowledgeResponse;
import cn.bugstack.rag.model.dto.TestCaseStatsDTO;
import cn.bugstack.rag.repository.IVectorStoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingClient;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

/**
 * 向量存储仓储实现（PGVector）
 */
@Slf4j
@Repository
public class VectorStoreRepositoryImpl implements IVectorStoreRepository {

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Override
    public void addDocument(String content, Map<String, Object> metadata) {
        Document doc = new Document(content, metadata);
        vectorStore.accept(List.of(doc));
    }

    @Override
    public void addDocuments(List<Document> documents) {
        log.info("存储文档到向量库, 数量: {}", documents.size());
        vectorStore.accept(documents);
    }

    @Override
    public void saveTestCases(String ragTag, List<String> testCasesJson) {
        log.info("保存测试用例到向量库, ragTag: {}, 数量: {}", ragTag, testCasesJson.size());
        for (String testCaseJson : testCasesJson) {
            Document doc = new Document(testCaseJson);
            doc.getMetadata().put("knowledge", ragTag);
            doc.getMetadata().put("type", "test_case");
            doc.getMetadata().put("adoptionStatus", "PENDING");
            vectorStore.accept(List.of(doc));
        }
        log.info("测试用例保存完成, ragTag: {}", ragTag);
    }

    /**
     * 只查询 采纳状态 的用例
     * @param ragTag 知识库标签
     * @param topK 查询数量
     * @return
     */
    @Override
    public List<String> queryTestCases(String ragTag, int topK) {
        log.info("查询测试用例(仅已采纳), ragTag: {}, topK: {}", ragTag, topK);

        SearchRequest request = SearchRequest.query("")
                .withTopK(topK)
                .withFilterExpression("knowledge == '" + ragTag + "' AND type == 'test_case' AND adoptionStatus == 'ADOPTED'");

        List<Document> docs = vectorStore.similaritySearch(request);
        return docs.stream().map(Document::getContent).toList();
    }

    @Override
    public void updateTestCaseStatus(String ragTag, String caseId, String status, String reason) {
        log.info("更新测试用例状态, ragTag: {}, caseId: {}, status: {}", ragTag, caseId, status);

        String caseIdPattern = "%\"id\":\"" + caseId + "\"%";

        String sql;
        if (reason != null && !reason.isEmpty()) {
            sql = "UPDATE spring_ai_vectors " +
                  "SET metadata = jsonb_set(jsonb_set(metadata, '{adoptionStatus}', to_jsonb(?)), '{rejectReason}', to_jsonb(?)) " +
                  "WHERE metadata->>'knowledge' = ? AND metadata->>'type' = 'test_case' AND content LIKE ?";
            jdbcTemplate.update(sql, status, reason, ragTag, caseIdPattern);
        } else {
            sql = "UPDATE spring_ai_vectors " +
                  "SET metadata = jsonb_set(metadata, '{adoptionStatus}', to_jsonb(?)) " +
                  "WHERE metadata->>'knowledge' = ? AND metadata->>'type' = 'test_case' AND content LIKE ?";
            jdbcTemplate.update(sql, status, ragTag, caseIdPattern);
        }
    }

    @Override
    public List<String> similaritySearch(String query, String knowledgeTag, int topK) {
        SearchRequest request = SearchRequest.query(query)
                .withTopK(topK)
                .withFilterExpression("knowledge == '" + knowledgeTag + "'");
        List<Document> docs = vectorStore.similaritySearch(request);
        return docs.stream().map(Document::getContent).toList();
    }

    /**
     *
     * @param query 查询文本
     * @param knowledgeTag 知识库标签
     * @param topK 检索数量
     * @return
     */
    @Override
    public List<DocumentWithScoreDTO> similaritySearchWithScore(String query, String knowledgeTag, int topK) {
        log.info("带分数的向量检索, query: {}, knowledgeTag: {}, topK: {}", query, knowledgeTag, topK);

        SearchRequest request = SearchRequest.query(query)
                .withTopK(topK)
                .withFilterExpression("knowledge == '" + knowledgeTag + "'");
        List<Document> docs = vectorStore.similaritySearch(request);

        // 计算query的embedding
        double[] queryVector = embeddingClient.embed(List.of(query)).get(0).stream()
                .mapToDouble(Double::doubleValue).toArray();

        // 批量计算文档的embedding
        List<String> docContents = docs.stream().map(Document::getContent).toList();
        List<List<Double>> allDocEmbeddings = embeddingClient.embed(docContents);

        // 计算每个文档的相似度分数
        List<DocumentWithScoreDTO> results = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            double[] docVector = allDocEmbeddings.get(i).stream().mapToDouble(Double::doubleValue).toArray();
            double similarity = cosineSimilarity(queryVector, docVector);
            results.add(new DocumentWithScoreDTO(docs.get(i).getContent(), similarity));
        }

        // 按分数降序排序
        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        log.info("带分数检索完成, 返回: {} 条", results.size());
        return results;
    }

    /**
     * 计算余弦相似度
     */
    private double cosineSimilarity(double[] vec1, double[] vec2) {
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        for (int i = 0; i < vec1.length; i++) {
            dotProduct += vec1[i] * vec2[i];
            norm1 += vec1[i] * vec1[i];
            norm2 += vec2[i] * vec2[i];
        }
        if (norm1 == 0 || norm2 == 0) return 0.0;
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    @Override
    public List<String> similaritySearchWithDeduplication(List<String> queries, String knowledgeTag, int topK) {
        List<String> allResults = new ArrayList<>();

        for (String query : queries) {
            if (query.trim().isEmpty()) continue;
            String cleanQuery = query.replaceAll("^\\d+\\.\\s*", "").trim();
            SearchRequest request = SearchRequest.query(cleanQuery)
                    .withTopK(topK)
                    .withFilterExpression("knowledge == '" + knowledgeTag + "' AND type == 'knowledge'");
            List<Document> docs = vectorStore.similaritySearch(request);
            allResults.addAll(docs.stream().map(Document::getContent).toList());
        }

        return allResults.stream().distinct().limit(topK).toList();
    }

    @Override
    public List<DocumentWithScoreDTO> similaritySearchWithScoreWithDeduplication(List<String> queries, String knowledgeTag, int topK) {
        log.info("带分数去重检索, queries数量: {}, knowledgeTag: {}, topK: {}", queries.size(), knowledgeTag, topK);

        // 1. 清洗 queries 并批量 embedding
        List<String> cleanQueries = new ArrayList<>();
        for (String query : queries) {
            if (query.trim().isEmpty()) continue;
            cleanQueries.add(query.replaceAll("^\\d+\\.\\s*", "").trim());
        }

        if (cleanQueries.isEmpty()) {
            return List.of();
        }

        // 批量 embedding 所有 queries（1次 API 调用）
        List<double[]> queryEmbeddings = embeddingClient.embed(cleanQueries).stream()
                .map(list -> list.stream().mapToDouble(Double::doubleValue).toArray())
                .toList();

        // 2. 每个 query 分别检索
        Map<String, List<Double>> docScoresMap = new LinkedHashMap<>();
        List<String> uniqueDocContents = new ArrayList<>();

        for (int qi = 0; qi < cleanQueries.size(); qi++) {
            String cleanQuery = cleanQueries.get(qi);
            double[] queryEmbedding = queryEmbeddings.get(qi);

            SearchRequest request = SearchRequest.query(cleanQuery)
                    .withTopK(topK)
                    .withFilterExpression("knowledge == '" + knowledgeTag + "'");
            List<Document> docs = vectorStore.similaritySearch(request);

            for (Document doc : docs) {
                String content = doc.getContent();
                if (!docScoresMap.containsKey(content)) {
                    docScoresMap.put(content, new ArrayList<>());
                    uniqueDocContents.add(content);
                }
                // 计算该 query 与文档的余弦相似度
                double[] docVector = embeddingClient.embed(List.of(content)).get(0).stream()
                        .mapToDouble(Double::doubleValue).toArray();
                double similarity = cosineSimilarity(queryEmbedding, docVector);
                docScoresMap.get(content).add(similarity);
            }
        }

        if (docScoresMap.isEmpty()) {
            return List.of();
        }

        // 3. 每个文档取多 query 的平均分数
        List<DocumentWithScoreDTO> results = new ArrayList<>();
        for (Map.Entry<String, List<Double>> entry : docScoresMap.entrySet()) {
            double avgScore = entry.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            results.add(new DocumentWithScoreDTO(entry.getKey(), avgScore));
        }

        // 按分数降序排序
        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        log.info("带分数去重检索完成, 去重后文档数: {}", results.size());
        return results;
    }

    @Override
    public List<DocumentWithScoreDTO> similaritySearchTestCasesWithScore(List<String> queries, String ragTag, int topK) {
        log.info("用例库带分数检索, queries数量: {}, ragTag: {}, topK: {}", queries.size(), ragTag, topK);

        // 1. 清洗 queries 并批量 embedding
        List<String> cleanQueries = new ArrayList<>();
        for (String query : queries) {
            if (query.trim().isEmpty()) continue;
            cleanQueries.add(query.replaceAll("^\\d+\\.\\s*", "").trim());
        }

        if (cleanQueries.isEmpty()) {
            return List.of();
        }

        // 批量 embedding 所有 queries（1次 API 调用）
        List<double[]> queryEmbeddings = embeddingClient.embed(cleanQueries).stream()
                .map(list -> list.stream().mapToDouble(Double::doubleValue).toArray())
                .toList();

        // 2. 每个 query 分别检索（过滤：test_case + ADOPTED）
        Map<String, List<Double>> docScoresMap = new LinkedHashMap<>();

        for (int qi = 0; qi < cleanQueries.size(); qi++) {
            String cleanQuery = cleanQueries.get(qi);
            double[] queryEmbedding = queryEmbeddings.get(qi);

            SearchRequest request = SearchRequest.query(cleanQuery)
                    .withTopK(topK)
                    .withFilterExpression("knowledge == '" + ragTag + "' AND type == 'test_case' AND adoptionStatus == 'ADOPTED'");
            List<Document> docs = vectorStore.similaritySearch(request);

            for (Document doc : docs) {
                String content = doc.getContent();
                if (!docScoresMap.containsKey(content)) {
                    docScoresMap.put(content, new ArrayList<>());
                }
                // 计算该 query 与文档的余弦相似度
                double[] docVector = embeddingClient.embed(List.of(content)).get(0).stream()
                        .mapToDouble(Double::doubleValue).toArray();
                double similarity = cosineSimilarity(queryEmbedding, docVector);
                docScoresMap.get(content).add(similarity);
            }
        }

        if (docScoresMap.isEmpty()) {
            return List.of();
        }

        // 3. 每个文档取多 query 的平均分数
        List<DocumentWithScoreDTO> results = new ArrayList<>();
        for (Map.Entry<String, List<Double>> entry : docScoresMap.entrySet()) {
            double avgScore = entry.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            results.add(new DocumentWithScoreDTO(entry.getKey(), avgScore));
        }

        // 按分数降序排序
        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        log.info("用例库带分数检索完成, 去重后用例数: {}", results.size());
        return results;
    }

    @Override
    public TestCaseStatsDTO queryTestCaseStats(String ragTag) {
        log.info("统计测试用例, ragTag: {}", ragTag);

        String sql = """
            SELECT
                COUNT(*) as total,
                COUNT(CASE WHEN metadata->>'adoptionStatus' = 'ADOPTED' THEN 1 END) as adopted,
                COUNT(CASE WHEN metadata->>'adoptionStatus' = 'REJECTED' THEN 1 END) as rejected,
                COUNT(CASE WHEN metadata->>'adoptionStatus' IS NULL OR metadata->>'adoptionStatus' = 'PENDING' THEN 1 END) as pending
            FROM spring_ai_vectors
            WHERE metadata->>'knowledge' = ?
              AND metadata->>'type' = 'test_case'
            """;

        TestCaseStatsDTO stats = jdbcTemplate.queryForObject(sql, (rs, rowNum) ->
                TestCaseStatsDTO.builder()
                        .total(rs.getInt("total"))
                        .adopted(rs.getInt("adopted"))
                        .rejected(rs.getInt("rejected"))
                        .pending(rs.getInt("pending"))
                        .ragTag(ragTag)
                        .build(), ragTag);

        log.info("用例统计完成, total: {}, adopted: {}, rejected: {}, pending: {}, adoptionRate: {}",
                stats.getTotal(), stats.getAdopted(), stats.getRejected(), stats.getPending(), stats.getAdoptionRate());

        return stats;
    }

    @Override
    public List<QueryKnowledgeResponse.KnowledgeDoc> queryKnowledgeDocs(String ragTag, int topK) {
        log.info("查询知识库文档, ragTag: {}, topK: {}", ragTag, topK);

        String sql = """
            SELECT content, metadata
            FROM spring_ai_vectors
            WHERE metadata->>'knowledge' = ?
              AND metadata->>'type' = 'knowledge'
            LIMIT ?
            """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String content = rs.getString("content");
            var metadata = rs.getObject("metadata", java.util.Map.class);
            return QueryKnowledgeResponse.KnowledgeDoc.builder()
                    .content(content)
                    .sourceDoc(metadata != null ? (String) metadata.get("sourceDoc") : null)
                    .build();
        }, ragTag, topK);
    }

    @Override
    public List<QueryKnowledgeResponse.KnowledgeDoc> queryKnowledgeDocsWithScore(String ragTag, String query, int topK) {
        log.info("语义检索知识库文档, ragTag: {}, query: {}, topK: {}", ragTag, query, topK);

        SearchRequest request = SearchRequest.query(query)
                .withTopK(topK)
                .withFilterExpression("knowledge == '" + ragTag + "' AND type == 'knowledge'");
        List<Document> docs = vectorStore.similaritySearch(request);

        return docs.stream().map(doc ->
                QueryKnowledgeResponse.KnowledgeDoc.builder()
                        .content(doc.getContent())
                        .sourceDoc((String) doc.getMetadata().get("sourceDoc"))
                        .build()
        ).toList();
    }


    @Override
    public void deleteByDocId(String ragTag, String docId) {
        log.info("根据docId删除所有相关chunk, ragTag: {}, docId: {}", ragTag, docId);

        String sql = """
            DELETE FROM spring_ai_vectors
            WHERE metadata->>'knowledge' = ?
              AND metadata->>'docId' = ?
            """;

        int deleted = jdbcTemplate.update(sql, ragTag, docId);
        log.info("根据docId删除chunk完成, ragTag: {}, docId: {}, 删除数量: {}", ragTag, docId, deleted);
    }

    @Override
    public List<Long> findChunkIdsByDocId(String ragTag, String docId) {
        log.debug("查询docId对应的chunkIds, ragTag: {}, docId: {}", ragTag, docId);

        String sql = """
            SELECT id FROM spring_ai_vectors
            WHERE metadata->>'knowledge' = ?
              AND metadata->>'docId' = ?
            """;

        return jdbcTemplate.queryForList(sql, Long.class, ragTag, docId);
    }

}

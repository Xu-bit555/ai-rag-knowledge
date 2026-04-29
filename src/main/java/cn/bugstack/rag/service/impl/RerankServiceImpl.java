package cn.bugstack.rag.service.impl;

import cn.bugstack.rag.model.dto.DocumentWithScoreDTO;
import cn.bugstack.rag.repository.IVectorStoreRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 重排序服务实现
 */
@Slf4j
@Service
public class RerankServiceImpl implements cn.bugstack.rag.service.RerankService {

    private static final String JINA_RERANK_URL = "https://api.jina.ai/v1/rerank";

    // MMR 多样性系数
    private static final double MMR_LAMBDA = 0.5;

    @Value("${spring.ai.jina.api-key}")
    private String jinaApiKey;

    @Value("${spring.ai.jina.model:jina-reranker-v1-base-en}")
    private String jinaModel;

    @Resource
    private RestTemplate restTemplate;

    @Resource
    private IVectorStoreRepository vectorStoreRepository;

    /**
     * 重排序（多场景版本：向量检索 → MMR → Rerank）
     * 使用多个query分别检索并去重，每个文档取平均相似度分数
     *
     * @param queries      查询文本列表（多场景场景）
     * @param ragTag 知识库标签
     * @param topN         返回的最相关文档数量
     * @return 按相关性排序且有多样性的文档列表
     */
    public List<String> rerank(List<String> queries, String ragTag, int topN) {
        if (queries == null || queries.isEmpty()) {
            log.warn("查询列表为空，跳过rerank");
            return List.of();
        }

        try {
            log.info("Rerank请求(多场景模式), queries数量: {}, ragTag: {}, topN: {}",
                    queries.size(), ragTag, topN);

            // 阶段0：带分数的去重向量检索
            int candidateSize = topN * 3;
            List<DocumentWithScoreDTO> scoredDocuments =
                    vectorStoreRepository.similaritySearchWithScoreWithDeduplication(queries, ragTag, candidateSize);

            if (scoredDocuments.isEmpty()) {
                log.warn("向量检索结果为空");
                return List.of();
            }

            // 合并所有queries作为rerank的query
            String combinedQuery = String.join(" ", queries);

            // 阶段1：MMR 多样性选择（使用真实分数）
            List<String> mmrCandidates = applyMMRWithScores(combinedQuery, scoredDocuments, topN);

            // 阶段2：Rerank 精排
            List<RerankResult> rerankedDocs = jinaRerankWithScore(combinedQuery, mmrCandidates);

            if (rerankedDocs.isEmpty()) {
                return mmrCandidates.size() <= topN ? mmrCandidates : mmrCandidates.subList(0, topN);
            }

            // 取 Rerank 分数最高的 topN
            List<String> finalResults = rerankedDocs.stream()
                    .limit(topN)
                    .map(r -> r.text)
                    .toList();

            log.info("Rerank完成(多场景模式), 原始文档数: {}, MMR候选: {}, 最终返回: {}",
                    scoredDocuments.size(), mmrCandidates.size(), finalResults.size());

            return finalResults;

        } catch (Exception e) {
            log.error("Rerank失败: {}", e.getMessage(), e);
            return List.of();
        }
    }


    /**
     * 用例库检索重排序：向量检索 → MMR → Rerank（仅查已采纳的测试用例）
     *
     * @param ragTag 知识库标签
     * @param query 查询文本
     * @param topN 返回的最相关用例数量
     * @return 按相关性排序且有多样性的测试用例列表
     */
    @Override
    public List<String> rerankTestCases(String ragTag, String query, int topN) {
        try {
            log.info("用例库Rerank请求, ragTag: {}, query长度: {}, topN: {}",
                    ragTag, query != null ? query.length() : 0, topN);

            // 阶段0：从query提取多场景（简单按句号/换行拆分）
            List<String> scenarios = extractScenarios(query);
            if (scenarios.isEmpty()) {
                scenarios = List.of(query);
            }

            // 阶段1：向量检索获取候选用例（带真实相似度分数，过滤test_case+ADOPTED）
            int candidateSize = topN * 3;
            List<DocumentWithScoreDTO> scoredDocuments =
                    vectorStoreRepository.similaritySearchTestCasesWithScore(scenarios, ragTag, candidateSize);

            if (scoredDocuments.isEmpty()) {
                log.warn("用例库检索结果为空");
                return List.of();
            }

            // 阶段2：MMR 多样性选择（使用真实相似度分数）
            List<String> mmrCandidates = applyMMRWithScores(query, scoredDocuments, topN);

            // 阶段3：Jina Rerank 精排
            List<RerankResult> rerankedDocs = jinaRerankWithScore(query, mmrCandidates);

            if (rerankedDocs.isEmpty()) {
                return mmrCandidates.size() <= topN ? mmrCandidates : mmrCandidates.subList(0, topN);
            }

            // 取 Rerank 分数最高的 topN
            List<String> finalResults = rerankedDocs.stream()
                    .limit(topN)
                    .map(r -> r.text)
                    .toList();

            log.info("用例库Rerank完成, 原始用例数: {}, MMR候选: {}, 最终返回: {}",
                    scoredDocuments.size(), mmrCandidates.size(), finalResults.size());

            return finalResults;

        } catch (Exception e) {
            log.error("用例库Rerank失败: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 从查询文本中提取多场景（简单拆分）
     */
    private List<String> extractScenarios(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        // 按句号、换行、分号拆分
        String[] parts = query.split("[。\n;]");
        return Arrays.stream(parts)
                .map(String::trim)
                .filter(s -> !s.isBlank() && s.length() > 5)
                .toList();
    }

    /**
     * 使用真实相似度分数的 MMR 多样性选择
     *
     * @param query        查询文本
     * @param documents    带分数的候选文档列表
     * @param selectCount 需要选择的数量
     * @return 多样性选择后的文档列表
     */
    private List<String> applyMMRWithScores(String query, List<DocumentWithScoreDTO> documents, int selectCount) {
        if (documents.size() <= selectCount) {
            return documents.stream().map(DocumentWithScoreDTO::getContent).toList();
        }

        // 初始化：使用向量检索返回的真实相似度分数
        List<MMRDocument> candidates = new ArrayList<>();
        for (DocumentWithScoreDTO doc : documents) {
            candidates.add(new MMRDocument(doc.getContent(), doc.getScore()));
        }

        // 被选集和候选集
        List<MMRDocument> selected = new ArrayList<>();
        List<MMRDocument> remaining = new ArrayList<>(candidates);

        // 选择第一个：选择相似度分数最高的
        if (!remaining.isEmpty()) {
            int bestIdx = 0;
            double bestScore = remaining.get(0).score;
            for (int i = 1; i < remaining.size(); i++) {
                if (remaining.get(i).score > bestScore) {
                    bestScore = remaining.get(i).score;
                    bestIdx = i;
                }
            }
            selected.add(remaining.remove(bestIdx));
        }

        // 迭代选择 MMR 最高的
        while (selected.size() < selectCount && !remaining.isEmpty()) {
            double bestMMR = Double.NEGATIVE_INFINITY;
            MMRDocument bestCandidate = null;
            int bestIndex = -1;

            for (int i = 0; i < remaining.size(); i++) {
                MMRDocument candidate = remaining.get(i);

                // 计算与已选择文档的最大相似度（用于衡量多样性）
                double maxSimilarity = selected.stream()
                        .mapToDouble(s -> calculateSimilarity(candidate.text, s.text))
                        .max()
                        .orElse(0.0);

                // MMR 公式：λ × relevance_score - (1-λ) × max_similarity
                double mmr = MMR_LAMBDA * candidate.score
                           - (1 - MMR_LAMBDA) * maxSimilarity;

                if (mmr > bestMMR) {
                    bestMMR = mmr;
                    bestCandidate = candidate;
                    bestIndex = i;
                }
            }

            if (bestCandidate != null && bestIndex >= 0) {
                selected.add(bestCandidate);
                remaining.remove(bestIndex);
            } else {
                break;
            }
        }

        return selected.stream().map(d -> d.text).toList();
    }

    /**
     * Jina Rerank 获取相关性分数
     */
    private List<RerankResult> jinaRerankWithScore(String query, List<String> documents) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", jinaModel);
            requestBody.put("query", query);
            requestBody.put("documents", documents);
            requestBody.put("top_n", Math.min(documents.size(), 20));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + jinaApiKey);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    JINA_RERANK_URL, HttpMethod.POST, entity, Map.class);

            Map<String, Object> responseBody = response.getBody();
            if (responseBody == null || !responseBody.containsKey("results")) {
                log.warn("Jina Rerank 响应格式异常");
                return Collections.emptyList();
            }

            List<Map<String, Object>> results = (List<Map<String, Object>>) responseBody.get("results");

            List<RerankResult> scoredDocs = new ArrayList<>();
            for (Map<String, Object> result : results) {
                Map<String, Object> document = (Map<String, Object>) result.get("document");
                String docText = (String) document.get("text");
                Double score = (Double) result.get("relevance_score");

                if (docText != null && score != null) {
                    scoredDocs.add(new RerankResult(docText, score));
                }
            }

            return scoredDocs;

        } catch (Exception e) {
            log.error("Jina Rerank 调用失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 文本相似度计算（基于 JSON 业务字段的 Jaccard 相似度）
     */
    private double calculateSimilarity(String text1, String text2) {
        if (text1 == null || text2 == null) return 0.0;
        if (text1.equals(text2)) return 1.0;

        try {
            // 解析 JSON，提取业务字段
            var json1 = com.alibaba.fastjson2.JSON.parseObject(text1);
            var json2 = com.alibaba.fastjson2.JSON.parseObject(text2);

            // 提取关键字段进行相似度计算
            String title1 = json1 != null ? json1.getString("title") : "";
            String title2 = json2 != null ? json2.getString("title") : "";

            String expected1 = json1 != null ? json1.getString("expectedResult") : "";
            String expected2 = json2 != null ? json2.getString("expectedResult") : "";

            // steps 是数组，转为字符串
            String steps1 = "";
            String steps2 = "";
            if (json1 != null && json1.containsKey("steps")) {
                steps1 = json1.getJSONArray("steps").toJSONString();
            }
            if (json2 != null && json2.containsKey("steps")) {
                steps2 = json2.getJSONArray("steps").toJSONString();
            }

            // 拼接业务内容
            text1 = title1 + " " + steps1 + " " + expected1;
            text2 = title2 + " " + steps2 + " " + expected2;

        } catch (Exception e) {
            // JSON 解析失败，使用原始文本
        }

        // Jaccard 相似度计算
        Set<Character> chars1 = new HashSet<>();
        Set<Character> chars2 = new HashSet<>();

        for (char c : text1.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                chars1.add(Character.toLowerCase(c));
            }
        }
        for (char c : text2.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                chars2.add(Character.toLowerCase(c));
            }
        }

        if (chars1.isEmpty() && chars2.isEmpty()) return 0.0;

        Set<Character> intersection = new HashSet<>(chars1);
        intersection.retainAll(chars2);

        Set<Character> union = new HashSet<>(chars1);
        union.addAll(chars2);

        return (double) intersection.size() / union.size();
    }

    /**
     * MMR 文档封装
     */
    private static class MMRDocument {
        String text;
        double score;

        MMRDocument(String text, double score) {
            this.text = text;
            this.score = score;
        }
    }

    /**
     * Rerank 结果封装
     */
    private static class RerankResult {
        String text;
        double score;

        RerankResult(String text, double score) {
            this.text = text;
            this.score = score;
        }
    }

}

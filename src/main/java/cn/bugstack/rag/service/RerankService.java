package cn.bugstack.rag.service;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 重排序服务（增强版）
 * 负责对检索结果进行多阶段Rerank，使用Jina Rerank Model筛选最相关的文档
 * 优化策略：相关性 + 多样性 + 覆盖度
 */
@Slf4j
@Service
public class RerankService {

    private static final String JINA_RERANK_URL = "https://api.jina.ai/v1/rerank";

    // MMR (Maximal Marginal Relevance) 多样性系数
    private static final double MMR_LAMBDA = 0.5;

    // 相似度阈值，超过则认为重复
    private static final double DUPLICATE_SIMILARITY_THRESHOLD = 0.85;

    @Value("${spring.ai.jina.api-key}")
    private String jinaApiKey;

    @Value("${spring.ai.jina.model:jina-reranker-v1-base-en}")
    private String jinaModel;

    @Resource
    private RestTemplate restTemplate;

    /**
     * 增强版重排序
     * 策略：1. Jina精排  2. MMR多样性增强  3. 去重处理
     *
     * @param query      查询文本（需求描述）
     * @param documents  待排序的候选文档列表
     * @param topN       返回的最相关文档数量
     * @return 按相关性排序且有多样性的文档列表
     */
    public List<String> rerank(String query, List<String> documents, int topN) {
        if (documents == null || documents.isEmpty()) {
            log.warn("候选文档为空，跳过rerank");
            return List.of();
        }

        try {
            log.info("增强Rerank请求, query长度: {}, 文档数: {}, topN: {}",
                    query != null ? query.length() : 0, documents.size(), topN);

            // 阶段1：Jina Rerank获取相关性分数
            List<RerankResult> scoredDocs = jinaRerankWithScore(query, documents);

            if (scoredDocs.isEmpty()) {
                return documents.size() <= topN ? documents : documents.subList(0, topN);
            }

            // 阶段2：MMR多样性增强
            List<RerankResult> mmrResults = applyMMR(query, scoredDocs, topN);

            // 阶段3：后处理 - 去除重复
            List<String> finalResults = postProcess(mmrResults, topN);

            log.info("增强Rerank完成, 原始文档数: {}, 最终返回: {}",
                    documents.size(), finalResults.size());

            return finalResults;

        } catch (Exception e) {
            log.error("增强Rerank失败: {}", e.getMessage(), e);
            // 降级：使用基础Jina Rerank
            return jinaRerank(query, documents, topN);
        }
    }

    /**
     * Jina Rerank（带分数返回）
     */
    private List<RerankResult> jinaRerankWithScore(String query, List<String> documents) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", jinaModel);
            requestBody.put("query", query);
            requestBody.put("documents", documents);
            requestBody.put("top_n", Math.min(documents.size(), 20)); // Jina限制最多20

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + jinaApiKey);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    JINA_RERANK_URL, HttpMethod.POST, entity, Map.class);

            Map<String, Object> responseBody = response.getBody();
            if (responseBody == null || !responseBody.containsKey("results")) {
                log.warn("Jina Rerank响应格式异常");
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
            log.error("Jina Rerank调用失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 基础Jina Rerank（降级用）
     */
    private List<String> jinaRerank(String query, List<String> documents, int topN) {
        List<RerankResult> results = jinaRerankWithScore(query, documents);
        return results.stream()
                .map(r -> r.text)
                .limit(topN)
                .collect(Collectors.toList());
    }

    /**
     * MMR (Maximal Marginal Relevance) 多样性算法
     * 公式：MMR = λ * Score(doc) - (1-λ) * max(Similarity(doc, selected))
     * 核心思想：在保证相关性的同时，增加结果的多样性
     */
    private List<RerankResult> applyMMR(String query, List<RerankResult> scoredDocs, int topN) {
        if (scoredDocs.size() <= topN) {
            return scoredDocs;
        }

        List<RerankResult> selected = new ArrayList<>();
        List<RerankResult> remaining = new ArrayList<>(scoredDocs);

        // 选择得分最高的作为第一个
        if (!remaining.isEmpty()) {
            selected.add(remaining.remove(0));
        }

        while (selected.size() < topN && !remaining.isEmpty()) {
            double bestMMR = Double.NEGATIVE_INFINITY;
            RerankResult bestCandidate = null;
            int bestIndex = -1;

            for (int i = 0; i < remaining.size(); i++) {
                RerankResult candidate = remaining.get(i);

                // 计算与已选择文档的最大相似度
                double maxSimilarity = selected.stream()
                        .mapToDouble(s -> calculateSimilarity(candidate.text, s.text))
                        .max()
                        .orElse(0.0);

                // MMR公式
                double mmr = MMR_LAMBDA * candidate.normalizedScore
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

        return selected;
    }

    /**
     * 简单文本相似度计算（使用Jaccard系数）
     */
    private double calculateSimilarity(String text1, String text2) {
        if (text1 == null || text2 == null) return 0.0;
        if (text1.equals(text2)) return 1.0;

        Set<String> words1 = new HashSet<>(Arrays.asList(text1.split("[\\s\\p{Punct}]+")));
        Set<String> words2 = new HashSet<>(Arrays.asList(text2.split("[\\s\\punct}]+")));

        words1.removeIf(w -> w.length() < 2); // 过滤短词
        words2.removeIf(w -> w.length() < 2);

        if (words1.isEmpty() && words2.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(words1);
        intersection.retainAll(words2);

        Set<String> union = new HashSet<>(words1);
        union.addAll(words2);

        return (double) intersection.size() / union.size();
    }

    /**
     * 后处理：去重
     */
    private List<String> postProcess(List<RerankResult> results, int topN) {
        List<String> finalResults = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (RerankResult result : results) {
            if (finalResults.size() >= topN) break;

            // 检查是否与已选结果重复
            boolean isDuplicate = finalResults.stream()
                    .anyMatch(existing -> calculateSimilarity(result.text, existing) > DUPLICATE_SIMILARITY_THRESHOLD);

            if (!isDuplicate) {
                finalResults.add(result.text);
                seen.add(result.text.hashCode() + "");
            }
        }

        return finalResults;
    }

    /**
     * Rerank结果封装
     */
    private static class RerankResult {
        String text;
        double score;
        double normalizedScore; // 归一化后的分数

        RerankResult(String text, double score) {
            this.text = text;
            this.score = score;
            this.normalizedScore = score; // Jina返回的已经是0-1之间的分数
        }
    }

    /**
     * 格式化候选用例列表（用于其他场景的兼容方法）
     */
    public String formatCandidateCases(List<String> cases) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cases.size(); i++) {
            sb.append("[").append(i + 1).append("] ").append(cases.get(i));
            if (i < cases.size() - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

}

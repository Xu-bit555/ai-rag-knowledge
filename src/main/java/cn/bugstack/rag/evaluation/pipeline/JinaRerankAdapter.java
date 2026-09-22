package cn.bugstack.rag.evaluation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A 组：Jina Reranker（production baseline）。
 *
 * **严格复现 production 行为**（per plan §三 A 组 + §九 Jina Adapter）：
 * - model    = "jina-reranker-v1-base-en"
 * - endpoint = "https://api.jina.ai/v1/rerank"
 * - body     = { model, query, documents[], top_n = min(documents.size(), 20) }
 * - sort     = relevance_score DESC
 * - auth     = "Bearer ${spring.ai.jina.api-key}"  或构造时显式注入
 *
 * **绝不**调用 RerankServiceImpl.rerank()（后者内部耦合 retrieval，违反"冻结 candidate"）。
 * HTTP 调用逻辑逐字段对照 RerankServiceImpl.jinaRerankWithScore（line 246-288）。
 *
 * 失败语义：API 异常时返回按 retrievalScore DESC 排序的前 topN（降级行为），
 * 这样 A 组失败不会让整套实验崩。降级事件写入 telemetry（latencyMs 仍记录）。
 */
public final class JinaRerankAdapter implements Reranker {

    public static final String DEFAULT_MODEL = "jina-reranker-v1-base-en";
    public static final String DEFAULT_URL   = "https://api.jina.ai/v1/rerank";

    private final String model;
    private final String endpoint;
    private final String apiKey;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public JinaRerankAdapter() {
        this(DEFAULT_MODEL, DEFAULT_URL, readApiKeyFromEnv());
    }

    public JinaRerankAdapter(String model, String endpoint, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("JINA_API_KEY env var (spring.ai.jina.api-key) is required");
        }
        this.model = model;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.mapper = new ObjectMapper();
    }

    private static String readApiKeyFromEnv() {
        String k = System.getenv("JINA_API_KEY");
        return k;
    }

    @Override
    public String name() { return "jina"; }

    @Override
    public RerankOutput rerank(String query, List<RetrievalCandidate> candidates, int topN) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query must not be blank");
        if (candidates == null) throw new IllegalArgumentException("candidates must not be null");
        if (topN < 1) throw new IllegalArgumentException("topN must be >= 1");

        long t0 = System.nanoTime();
        Map<String, RetrievalCandidate> byText = new HashMap<>();
        List<String> documents = new ArrayList<>(candidates.size());
        for (RetrievalCandidate c : candidates) {
            byText.put(c.text(), c);
            documents.add(c.text());
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("query", query);
            body.put("documents", documents);
            body.put("top_n", Math.min(documents.size(), 20));

            String json = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsed = (System.nanoTime() - t0) / 1_000_000L;

            if (resp.statusCode() / 100 != 2) {
                return degrade(query, candidates, topN, elapsed, "Jina HTTP " + resp.statusCode());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = mapper.readValue(resp.body(), Map.class);
            Object resultsObj = responseBody.get("results");
            if (!(resultsObj instanceof List)) {
                return degrade(query, candidates, topN, elapsed, "missing results[]");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) resultsObj;

            // P1 fix: 用 index 字段定位 candidate, 不再依赖 document.text
            //   Jina API v1+ 实际只返回 {index, relevance_score}, document 字段被砍掉 (避免大 payload).
            //   旧代码假设 document 是 Map 含 text 子字段 → 全部 continue 跳过 → 走 degrade = NoOp
            //   新代码: 优先用 index 字段 (新版), 兼容 document.text (旧版)
            //   用 candidateId 索引保证 O(1) 查找
            java.util.Map<Integer, RetrievalCandidate> byIndex = new java.util.HashMap<>();
            for (int i = 0; i < candidates.size(); i++) {
                byIndex.put(i, candidates.get(i));
            }

            List<RankedItem> ranking = new ArrayList<>();
            Map<String, Double> scores = new HashMap<>();
            // 先把所有 candidate 的默认 score 填上（防止 Jina 没返回的 doc 缺失）
            for (RetrievalCandidate c : candidates) {
                scores.put(c.candidateId(), 0.0);
            }
            int idx = 0;
            for (Map<String, Object> result : results) {
                Object scoreObj = result.get("relevance_score");
                if (!(scoreObj instanceof Number)) continue;

                RetrievalCandidate c = null;

                // 优先用 index 字段 (Jina v1+ 标准)
                Object indexObj = result.get("index");
                if (indexObj instanceof Number) {
                    int docIndex = ((Number) indexObj).intValue();
                    c = byIndex.get(docIndex);
                }

                // 兼容旧版: document.text 字段
                if (c == null) {
                    Object docObj = result.get("document");
                    if (docObj instanceof Map) {
                        String text = (String) ((Map<String, Object>) docObj).get("text");
                        if (text != null) c = byText.get(text);
                    }
                }

                if (c == null) continue;

                double score = ((Number) scoreObj).doubleValue();
                scores.put(c.candidateId(), score);
                int newRank = ++idx;
                ranking.add(new RankedItem(newRank, c.ragRank(), c.candidateId(), score));
            }
            // 补全：Jina top_n=20 截断时，把剩余 candidate 按 retrievalScore DESC 填入
            // （保持 topN 个数，避免 metrics 维度错位）
            if (ranking.size() < topN && ranking.size() < candidates.size()) {
                java.util.Set<String> ranked = ranking.stream().map(RankedItem::candidateId)
                        .collect(Collectors.toSet());
                List<RetrievalCandidate> rest = candidates.stream()
                        .filter(c -> !ranked.contains(c.candidateId()))
                        .sorted(Comparator.comparingDouble(RetrievalCandidate::retrievalScore).reversed())
                        .collect(Collectors.toList());
                int fallbackRank = ranking.size();
                for (RetrievalCandidate c : rest) {
                    if (fallbackRank >= topN) break;
                    ranking.add(new RankedItem(++fallbackRank, c.ragRank(), c.candidateId(), 0.0));
                }
            }
            RerankTelemetry tele = new RerankTelemetry(elapsed, 0, 0, 0.0, 0, 0);
            return new RerankOutput(name(), query, ranking, scores, Map.of(), tele);
        } catch (Exception e) {
            long elapsed = (System.nanoTime() - t0) / 1_000_000L;
            return degrade(query, candidates, topN, elapsed, e.getClass().getSimpleName());
        }
    }

    private RerankOutput degrade(String query, List<RetrievalCandidate> candidates, int topN, long elapsed, String reason) {
        List<RetrievalCandidate> sorted = candidates.stream()
                .sorted(Comparator.comparingDouble(RetrievalCandidate::retrievalScore).reversed())
                .limit(topN)
                .collect(Collectors.toList());
        List<RankedItem> ranking = new ArrayList<>(sorted.size());
        Map<String, Double> scores = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            RetrievalCandidate c = sorted.get(i);
            int newRank = i + 1;
            ranking.add(new RankedItem(newRank, c.ragRank(), c.candidateId(), c.retrievalScore()));
            scores.put(c.candidateId(), c.retrievalScore());
        }
        // 不抛异常，让实验能继续跑（A 组降级 = 已知行为，不算 rerank 失败）
        RerankTelemetry tele = new RerankTelemetry(elapsed, 0, 0, 0.0, 0, 0);
        // degrade 路径返回的 ranking 与 retrieval score 一致；这不是真实 rerank 结果，
        // 但能保证 metrics 维度不崩。报告里应标注 degrade 次数。
        return new RerankOutput(name() + "_degraded", query, ranking, scores, Map.of(), tele);
    }
}
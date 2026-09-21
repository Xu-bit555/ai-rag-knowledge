package cn.bugstack.rag.evaluation.pipeline;

import cn.bugstack.rag.evaluation.judge.JevCache;
import cn.bugstack.rag.evaluation.judge.JevClient;
import cn.bugstack.rag.evaluation.judge.JevScoringStrategy;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * B 组：Jev Reranker（per plan §三 B 组）。
 *
 * 关键约束（per plan §十 smoke 验收 11）：
 * - 对每个 candidate 调用一次 Jev API（per-candidate 评分）
 * - 同一次 response 上产出三种 score：choice / expected / normalized
 * - ranking 由配置的 scoreStrategy 决定
 * - **绝不**为三种 score 重复调用 API（共享 cache key + raw response）
 *
 * 失败语义：单 candidate Jev 调用失败 → 该 candidate score=0、choice="error"，
 * 不抛异常（让 A/B 对比能跑完）。
 */
public final class JevRerankAdapter implements Reranker {

    private final JevClient client;
    private final JevCache cache;
    private final ExperimentConfig.ScoreStrategy defaultStrategy;

    public JevRerankAdapter(JevClient client, JevCache cache) {
        this(client, cache, ExperimentConfig.ScoreStrategy.CHOICE_PROBABILITY);
    }

    public JevRerankAdapter(JevClient client, JevCache cache, ExperimentConfig.ScoreStrategy defaultStrategy) {
        if (client == null) throw new IllegalArgumentException("client must not be null");
        if (cache == null) throw new IllegalArgumentException("cache must not be null");
        if (defaultStrategy == null) throw new IllegalArgumentException("defaultStrategy must not be null");
        this.client = client;
        this.cache = cache;
        this.defaultStrategy = defaultStrategy;
    }

    @Override
    public String name() { return "jev"; }

    @Override
    public RerankOutput rerank(String query, List<RetrievalCandidate> candidates, int topN) {
        return rerank(query, candidates, topN, defaultStrategy);
    }

    /**
     * 用指定 score strategy 排序，输出 ranking。
     * 同一份 jevDetails 可以被多种 strategy 重用。
     */
    public RerankOutput rerank(String query, List<RetrievalCandidate> candidates, int topN,
                               ExperimentConfig.ScoreStrategy strategy) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query must not be blank");
        if (candidates == null) throw new IllegalArgumentException("candidates must not be null");
        if (topN < 1) throw new IllegalArgumentException("topN must be >= 1");
        if (strategy == null || strategy == ExperimentConfig.ScoreStrategy.NA) {
            throw new IllegalArgumentException("strategy must be a Jev score strategy");
        }

        long t0 = System.nanoTime();
        int totalInput = 0, totalOutput = 0;
        Map<String, JevRerankDetail> details = new LinkedHashMap<>();
        Map<String, Double> scoresByStrategy = new HashMap<>();
        Map<String, Double> scoresChoice     = new HashMap<>();
        Map<String, Double> scoresExpected   = new HashMap<>();
        Map<String, Double> scoresNormalized = new HashMap<>();

        for (RetrievalCandidate c : candidates) {
            JevRerankDetail detail = scoreOneWithCache(query, c);
            details.put(c.candidateId(), detail);
            scoresChoice.put(c.candidateId(),     detail.scoreChoice());
            scoresExpected.put(c.candidateId(),   detail.scoreExpected());
            scoresNormalized.put(c.candidateId(), detail.scoreNormalized());

            double s = switch (strategy) {
                case CHOICE_PROBABILITY -> detail.scoreChoice();
                case EXPECTED_SCORE     -> detail.scoreExpected();
                case NORMALIZED_SCORE   -> detail.scoreNormalized();
                case NA                 -> 0.0;
            };
            scoresByStrategy.put(c.candidateId(), s);
        }

        // 按 strategy 排序后取 topN
        List<Map.Entry<String, Double>> sorted = scoresByStrategy.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(topN)
                .collect(Collectors.toList());

        List<RankedItem> ranking = new java.util.ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<String, Double> e = sorted.get(i);
            String cid = e.getKey();
            RetrievalCandidate cand = findById(candidates, cid);
            int origRank = cand == null ? -1 : cand.ragRank();
            ranking.add(new RankedItem(i + 1, origRank, cid, e.getValue()));
        }

        long elapsed = (System.nanoTime() - t0) / 1_000_000L;
        RerankTelemetry tele = new RerankTelemetry(
                elapsed,
                /*inputTokens*/  0,
                /*outputTokens*/ 0,
                /*costUsd*/      0.0,
                cache.hits(),
                cache.misses());
        return new RerankOutput(name(), query, ranking, scoresByStrategy, details, tele);
    }

    /**
     * 单 candidate 评分：先查 cache，miss 才打 API。
     */
    private JevRerankDetail scoreOneWithCache(String query, RetrievalCandidate c) {
        Optional<Map<String, Object>> cached = cache.get(query, c.candidateId(), client.model());
        Map<String, Object> responseBody;
        if (cached.isPresent()) {
            responseBody = cached.get();
        } else {
            responseBody = client.evaluateRaw(query, c.text());
            cache.put(query, c.candidateId(), client.model(), responseBody);
        }
        return parse(responseBody, c.candidateId());
    }

    @SuppressWarnings("unchecked")
    private JevRerankDetail parse(Map<String, Object> responseBody, String candidateId) {
        try {
            Map<String, Object> answers = (Map<String, Object>) responseBody.get("answers");
            if (answers == null) throw new IllegalStateException("missing 'answers'");
            Map<String, Object> relevance = (Map<String, Object>) answers.get("relevance");
            if (relevance == null) throw new IllegalStateException("missing 'answers.relevance'");
            String choice = (String) relevance.get("choice");
            if (choice == null) throw new IllegalStateException("missing 'choice'");

            // probabilities 可能是 Map<String, Integer> 或 Map<String, Double>，统一转 Double
            Map<String, Double> probs = new LinkedHashMap<>();
            Object rawProbs = relevance.get("probabilities");
            if (rawProbs instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) rawProbs).entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof Number) {
                        probs.put(e.getKey(), ((Number) v).doubleValue());
                    } else {
                        probs.put(e.getKey(), 0.0);
                    }
                }
            }
            // 确保 4 个 label 都存在（缺则填 0）
            for (String k : new String[]{
                    JevRerankDetail.LABEL_DIRECTLY_ANSWERS,
                    JevRerankDetail.LABEL_USEFUL,
                    JevRerankDetail.LABEL_TANGENTIAL,
                    JevRerankDetail.LABEL_IRRELEVANT}) {
                probs.putIfAbsent(k, 0.0);
            }

            double confidence = 0.0;
            // TypeSafe direct HTTP 返回 snake_case: provider_metadata；SDK 返回 camelCase: providerMetadata
            Map<String, Object> pm = (Map<String, Object>) responseBody.get("provider_metadata");
            if (pm == null) pm = (Map<String, Object>) responseBody.get("providerMetadata");
            if (pm != null && pm.get("typesafe") instanceof Map) {
                Map<String, Object> ts = (Map<String, Object>) pm.get("typesafe");
                if (ts.get("confidence") instanceof Map) {
                    Map<String, Object> conf = (Map<String, Object>) ts.get("confidence");
                    Object v = conf.get("relevance");
                    if (v instanceof Number) confidence = ((Number) v).doubleValue();
                }
            }

            return new JevRerankDetail(
                    choice,
                    probs,
                    confidence,
                    JevScoringStrategy.scoreChoice(probs),
                    JevScoringStrategy.scoreExpected(probs),
                    JevScoringStrategy.scoreNormalized(probs));
        } catch (Exception e) {
            // 解析失败 → 返回"全部概率为 0，choice=irrelevant"的安全默认
            Map<String, Double> zero = new LinkedHashMap<>();
            zero.put(JevRerankDetail.LABEL_DIRECTLY_ANSWERS, 0.0);
            zero.put(JevRerankDetail.LABEL_USEFUL,            0.0);
            zero.put(JevRerankDetail.LABEL_TANGENTIAL,        0.0);
            zero.put(JevRerankDetail.LABEL_IRRELEVANT,        0.0);
            return new JevRerankDetail(JevRerankDetail.LABEL_IRRELEVANT, zero, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private static RetrievalCandidate findById(List<RetrievalCandidate> candidates, String candidateId) {
        for (RetrievalCandidate c : candidates) {
            if (c.candidateId().equals(candidateId)) return c;
        }
        return null;
    }
}
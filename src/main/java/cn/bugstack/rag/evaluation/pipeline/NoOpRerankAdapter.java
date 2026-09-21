package cn.bugstack.rag.evaluation.pipeline;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * C 组：No Rerank。
 *
 * 严格按 plan §三 C 组定义：**保持 retrieval 原始 rank（ragRank）顺序**，
 * 不按 retrieval_score DESC 重排，不调任何外部 API。
 *
 * ranking 中 newRank = 排序后位置（1-based），originalRank = 候选的 ragRank。
 * 对于 C 组，newRank == originalRank 应当成立。
 */
public final class NoOpRerankAdapter implements Reranker {

    @Override
    public String name() { return "none"; }

    @Override
    public RerankOutput rerank(String query, List<RetrievalCandidate> candidates, int topN) {
        if (candidates == null) throw new IllegalArgumentException("candidates must not be null");
        if (topN < 1) throw new IllegalArgumentException("topN must be >= 1");

        List<RetrievalCandidate> sorted = candidates.stream()
                .sorted(Comparator.comparingInt(RetrievalCandidate::ragRank))
                .limit(topN)
                .collect(Collectors.toList());

        List<RankedItem> ranking = new java.util.ArrayList<>(sorted.size());
        Map<String, Double> scores = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            RetrievalCandidate c = sorted.get(i);
            int newRank = i + 1;
            // C 组：score 用 retrieval_score 仅作为可观测值，不参与排序
            ranking.add(new RankedItem(newRank, c.ragRank(), c.candidateId(), c.retrievalScore()));
            scores.put(c.candidateId(), c.retrievalScore());
        }
        return new RerankOutput(name(), query, ranking, scores, Map.of(), RerankTelemetry.empty());
    }
}
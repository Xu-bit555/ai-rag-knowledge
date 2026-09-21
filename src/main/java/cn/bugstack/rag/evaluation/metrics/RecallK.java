package cn.bugstack.rag.evaluation.metrics;

import java.util.List;
import java.util.Map;

/**
 * Recall@K = (ranking top-K 内 relevant 数) / (ground truth 中所有 relevant 数)
 *
 * 若 ground truth 中 relevant 总数为 0 → 返回 0.0（无法定义 recall）。
 */
public final class RecallK {

    private RecallK() {}

    public static double compute(List<String> rankingCandidateIds,
                                 Map<String, Integer> groundTruth,
                                 int k) {
        int totalRelevant = MetricsUtils.totalRelevant(groundTruth);
        if (totalRelevant == 0) return 0.0;
        int kk = Math.min(k, rankingCandidateIds.size());
        int hit = 0;
        for (int i = 0; i < kk; i++) {
            Integer g = groundTruth.get(rankingCandidateIds.get(i));
            if (g != null && g >= MetricsUtils.MIN_RELEVANT_GRADE) hit++;
        }
        return (double) hit / totalRelevant;
    }
}
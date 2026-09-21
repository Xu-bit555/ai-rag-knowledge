package cn.bugstack.rag.evaluation.metrics;

import java.util.List;
import java.util.Map;

/**
 * Precision@K = (ranking top-K 内 relevant 数) / K
 */
public final class PrecisionK {

    private PrecisionK() {}

    public static double compute(List<String> rankingCandidateIds,
                                 Map<String, Integer> groundTruth,
                                 int k) {
        if (k <= 0) return 0.0;
        int kk = Math.min(k, rankingCandidateIds.size());
        int hit = 0;
        for (int i = 0; i < kk; i++) {
            Integer g = groundTruth.get(rankingCandidateIds.get(i));
            if (g != null && g >= MetricsUtils.MIN_RELEVANT_GRADE) hit++;
        }
        return (double) hit / kk;
    }
}
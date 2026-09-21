package cn.bugstack.rag.evaluation.metrics;

import java.util.List;
import java.util.Map;

/**
 * MRR@K = 1 / rank_of_first_relevant_in_top_K
 * 若 top-K 内无 relevant → 0.0
 */
public final class MRRK {

    private MRRK() {}

    public static double compute(List<String> rankingCandidateIds,
                                 Map<String, Integer> groundTruth,
                                 int k) {
        int kk = Math.min(k, rankingCandidateIds.size());
        for (int i = 0; i < kk; i++) {
            Integer g = groundTruth.get(rankingCandidateIds.get(i));
            if (g != null && g >= MetricsUtils.MIN_RELEVANT_GRADE) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }
}
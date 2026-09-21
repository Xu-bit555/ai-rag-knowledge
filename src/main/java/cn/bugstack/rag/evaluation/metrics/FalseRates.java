package cn.bugstack.rag.evaluation.metrics;

import java.util.List;
import java.util.Map;

/**
 * False Positive Rate@K = (ranking top-K 中 ground_truth relevance=0 的 candidate 数) / K
 * False Negative Rate@K = (ground_truth relevance>=3 但不在 ranking top-K 的 candidate 数) / (ground_truth relevance>=3 总数)
 *
 * 注意 FP 用 binary (0 vs >=1)，FN 用 4-level 中 "directly_answers" 维度（per plan §二十二）。
 */
public final class FalseRates {

    private FalseRates() {}

    public static double falsePositiveRateK(List<String> rankingCandidateIds,
                                            Map<String, Integer> groundTruth,
                                            int k) {
        if (k <= 0) return 0.0;
        int kk = Math.min(k, rankingCandidateIds.size());
        int fp = 0;
        for (int i = 0; i < kk; i++) {
            Integer g = groundTruth.get(rankingCandidateIds.get(i));
            if (g != null && g == 0) fp++;
        }
        return (double) fp / kk;
    }

    public static int falsePositiveCountK(List<String> rankingCandidateIds,
                                         Map<String, Integer> groundTruth,
                                         int k) {
        int kk = Math.min(k, rankingCandidateIds.size());
        int fp = 0;
        for (int i = 0; i < kk; i++) {
            Integer g = groundTruth.get(rankingCandidateIds.get(i));
            if (g != null && g == 0) fp++;
        }
        return fp;
    }

    public static int falseNegativeCountK(List<String> rankingCandidateIds,
                                          Map<String, Integer> groundTruth,
                                          int k) {
        java.util.Set<String> topK = new java.util.HashSet<>(rankingCandidateIds.subList(0, Math.min(k, rankingCandidateIds.size())));
        int fn = 0;
        for (Map.Entry<String, Integer> e : groundTruth.entrySet()) {
            if (e.getValue() != null && e.getValue() >= 3 && !topK.contains(e.getKey())) {
                fn++;
            }
        }
        return fn;
    }

    public static double falseNegativeRateK(List<String> rankingCandidateIds,
                                            Map<String, Integer> groundTruth,
                                            int k) {
        int totalCritical = 0;
        for (Integer g : groundTruth.values()) {
            if (g != null && g >= 3) totalCritical++;
        }
        if (totalCritical == 0) return 0.0;
        return (double) falseNegativeCountK(rankingCandidateIds, groundTruth, k) / totalCritical;
    }
}
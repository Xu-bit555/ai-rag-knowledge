package cn.bugstack.rag.evaluation.metrics;

import java.util.List;
import java.util.Map;

/**
 * nDCG@K = DCG@K / IDCG@K
 *
 * gain(relevance) = relevance grade（0/1/2/3）
 * discount(i) = log2(i + 2)   (i is 0-based)
 */
public final class NDCGK {

    private NDCGK() {}

    public static double compute(List<String> rankingCandidateIds,
                                 Map<String, Integer> groundTruth,
                                 int k) {
        int kk = Math.min(k, rankingCandidateIds.size());
        double dcg = 0.0;
        for (int i = 0; i < kk; i++) {
            Integer g = groundTruth.get(rankingCandidateIds.get(i));
            int grade = g == null ? 0 : g;
            dcg += grade / log2(i + 2);
        }

        // IDCG：理想排序下，把所有 relevant candidate 按 grade DESC 排
        int[] idealGrades = computeIdealGrades(groundTruth, k);
        double idcg = 0.0;
        for (int i = 0; i < idealGrades.length; i++) {
            idcg += idealGrades[i] / log2(i + 2);
        }
        return idcg > 0 ? dcg / idcg : 0.0;
    }

    private static int[] computeIdealGrades(Map<String, Integer> groundTruth, int k) {
        // 取 top-k 个 grade，按降序
        List<Integer> sorted = new java.util.ArrayList<>();
        for (Integer g : groundTruth.values()) {
            if (g != null && g > 0) sorted.add(g);
        }
        sorted.sort((a, b) -> Integer.compare(b, a));
        int n = Math.min(k, sorted.size());
        int[] result = new int[n];
        for (int i = 0; i < n; i++) result[i] = sorted.get(i);
        return result;
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
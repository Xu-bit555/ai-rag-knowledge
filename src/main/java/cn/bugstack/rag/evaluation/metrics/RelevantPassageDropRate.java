package cn.bugstack.rag.evaluation.metrics;

import java.util.List;
import java.util.Map;

/**
 * Relevant Passage Drop Rate（per plan §十五）：
 * Retriever 已经成功召回的 Relevant Passage（relevance >= MIN_RELEVANT_GRADE
 * 且出现在 Frozen Top-K），在 Reranker 输出 Top-N 后被错误排除在外的比例。
 *
 * drop_rate = 1 - |frozen_top_K ∩ ranking_top_N ∩ relevant| / |frozen_top_K ∩ relevant|
 *
 * 若 frozen_top_K ∩ relevant 为空 → 0.0（没东西可掉）。
 */
public final class RelevantPassageDropRate {

    private RelevantPassageDropRate() {}

    public static double compute(List<String> frozenTopKCandidateIds,
                                 List<String> rankingCandidateIds,
                                 Map<String, Integer> groundTruth) {
        java.util.Set<String> frozen = new java.util.HashSet<>(frozenTopKCandidateIds);
        java.util.Set<String> topN = new java.util.HashSet<>(rankingCandidateIds);

        int availableInFrozen = 0;
        int survivedInTopN = 0;
        for (Map.Entry<String, Integer> e : groundTruth.entrySet()) {
            if (e.getValue() != null && e.getValue() >= MetricsUtils.MIN_RELEVANT_GRADE) {
                if (frozen.contains(e.getKey())) {
                    availableInFrozen++;
                    if (topN.contains(e.getKey())) survivedInTopN++;
                }
            }
        }
        if (availableInFrozen == 0) return 0.0;
        return 1.0 - (double) survivedInTopN / availableInFrozen;
    }
}
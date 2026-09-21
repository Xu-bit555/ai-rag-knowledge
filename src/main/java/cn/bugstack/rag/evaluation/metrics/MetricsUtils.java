package cn.bugstack.rag.evaluation.metrics;

import java.util.List;
import java.util.Map;

/**
 * Metrics 公共工具：把 ranking + ground truth 统一成"位置 → relevance grade"序列。
 *
 * Ground truth 约定（per plan §四）：
 *   relevance ∈ {0=irrelevant, 1=tangential, 2=useful, 3=directly_answers}
 *
 * 大部分指标按"ground truth 中 relevance >= MIN_RELEVANT_GRADE 的 candidate 视为 relevant"。
 * MIN_RELEVANT_GRADE = 2（"useful"及以上）。
 */
public final class MetricsUtils {

    public static final int MIN_RELEVANT_GRADE = 2;

    private MetricsUtils() {}

    /**
     * 把 ranking 转成每个位置的 relevance grade（0 表示未标注）。
     * ranking[i] = candidateId → groundTruth.getOrDefault(candidateId, 0)
     */
    public static int[] rankingToGrades(List<String> rankingCandidateIds, Map<String, Integer> groundTruth) {
        int[] grades = new int[rankingCandidateIds.size()];
        for (int i = 0; i < rankingCandidateIds.size(); i++) {
            String cid = rankingCandidateIds.get(i);
            Integer g = groundTruth.get(cid);
            grades[i] = g == null ? 0 : g;
        }
        return grades;
    }

    /**
     * 判断 retrieval_failure：ground_truth 里 relevance >= MIN_RELEVANT_GRADE 的
     * candidate 是否都不在 topK（Frozen Top-30）。
     */
    public static boolean isRetrievalFailure(Map<String, Integer> groundTruth, List<String> frozenTopKCandidateIds) {
        for (Map.Entry<String, Integer> e : groundTruth.entrySet()) {
            if (e.getValue() >= MIN_RELEVANT_GRADE) {
                if (frozenTopKCandidateIds.contains(e.getKey())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * ground truth 中 relevance >= MIN_RELEVANT_GRADE 的 candidate 集合大小。
     */
    public static int totalRelevant(Map<String, Integer> groundTruth) {
        int n = 0;
        for (Integer g : groundTruth.values()) {
            if (g != null && g >= MIN_RELEVANT_GRADE) n++;
        }
        return n;
    }
}
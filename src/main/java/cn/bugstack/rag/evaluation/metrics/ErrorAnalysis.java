package cn.bugstack.rag.evaluation.metrics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Error Analysis（per plan §六 / §二十六 11）。
 *
 * 5 类 case（每对 group pair）：
 * - retrieval_failure       = Top-30 里完全没有 ground truth relevance >= 2
 * - both_success            = A 和 B 都把 relevance >= 2 放进 topN
 * - both_failure            = A 和 B 都没把 relevance >= 2 放进 topN
 * - A_success_B_failure     = A 成功、B 失败
 * - A_failure_B_success     = A 失败、B 成功
 * - reranking_failure_only  = Top-30 里有 relevance >= 2，但 A/B 都没放进 topN（即 both_failure 但排除 retrieval_failure）
 */
public final class ErrorAnalysis {

    public static final String CAT_RETRIEVAL_FAILURE        = "retrieval_failure";
    public static final String CAT_BOTH_SUCCESS             = "both_success";
    public static final String CAT_BOTH_FAILURE             = "both_failure";
    public static final String CAT_A_SUCCESS_B_FAILURE      = "A_success_B_failure";
    public static final String CAT_A_FAILURE_B_SUCCESS      = "A_failure_B_success";
    public static final String CAT_RERANKING_FAILURE_ONLY   = "reranking_failure_only";

    private ErrorAnalysis() {}

    /**
     * 对 query 集合分类。
     *
     * @param groundTruths  Map<queryId, Map<candidateId, relevanceGrade>>
     * @param rankingsA     Map<queryId, List<candidateId>>
     * @param rankingsB     Map<queryId, List<candidateId>>
     * @param frozenTopK    Map<queryId, List<candidateId>>  Frozen Top-K candidate list（用于判 retrieval_failure）
     * @return Map<category, count>
     */
    public static Map<String, Integer> classify(Map<String, Map<String, Integer>> groundTruths,
                                                Map<String, List<String>> rankingsA,
                                                Map<String, List<String>> rankingsB,
                                                Map<String, List<String>> frozenTopK) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put(CAT_RETRIEVAL_FAILURE,       0);
        counts.put(CAT_BOTH_SUCCESS,            0);
        counts.put(CAT_BOTH_FAILURE,            0);
        counts.put(CAT_A_SUCCESS_B_FAILURE,     0);
        counts.put(CAT_A_FAILURE_B_SUCCESS,     0);
        counts.put(CAT_RERANKING_FAILURE_ONLY,  0);

        for (String qid : groundTruths.keySet()) {
            Map<String, Integer> gt = groundTruths.get(qid);
            List<String> frozen = frozenTopK.getOrDefault(qid, List.of());
            if (MetricsUtils.isRetrievalFailure(gt, frozen)) {
                counts.merge(CAT_RETRIEVAL_FAILURE, 1, Integer::sum);
                continue;
            }
            List<String> aRanking = rankingsA.getOrDefault(qid, List.of());
            List<String> bRanking = rankingsB.getOrDefault(qid, List.of());
            boolean aOk = hasRelevant(gt, aRanking);
            boolean bOk = hasRelevant(gt, bRanking);
            if (aOk && bOk)        counts.merge(CAT_BOTH_SUCCESS,        1, Integer::sum);
            else if (!aOk && !bOk) {
                counts.merge(CAT_BOTH_FAILURE, 1, Integer::sum);
                counts.merge(CAT_RERANKING_FAILURE_ONLY, 1, Integer::sum);
            }
            else if (aOk && !bOk)  counts.merge(CAT_A_SUCCESS_B_FAILURE, 1, Integer::sum);
            else if (!aOk && bOk)  counts.merge(CAT_A_FAILURE_B_SUCCESS, 1, Integer::sum);
        }
        return counts;
    }

    private static boolean hasRelevant(Map<String, Integer> groundTruth, List<String> ranking) {
        Set<String> set = new java.util.HashSet<>(ranking);
        for (Map.Entry<String, Integer> e : groundTruth.entrySet()) {
            if (e.getValue() != null && e.getValue() >= MetricsUtils.MIN_RELEVANT_GRADE && set.contains(e.getKey())) {
                return true;
            }
        }
        return false;
    }
}
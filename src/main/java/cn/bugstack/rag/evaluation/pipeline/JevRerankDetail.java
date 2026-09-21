package cn.bugstack.rag.evaluation.pipeline;

import java.util.Map;

/**
 * Jev 单条 candidate 的完整评分明细。
 *
 * 一次 Jev API response 上产出三种 score（per plan §三 B 组）：
 * - scoreChoice     = P(directly_answers)
 * - scoreExpected   = 3·P(DA) + 2·P(U) + 1·P(T) + 0·P(I)   ∈ [0, 3]
 * - scoreNormalized = scoreExpected / 3                   ∈ [0, 1]
 *
 * probability distribution 全量保留，方便后续重排序或换权重。
 */
public record JevRerankDetail(
        String choice,
        Map<String, Double> probabilities,
        double confidence,
        double scoreChoice,
        double scoreExpected,
        double scoreNormalized
) {
    public static final String PROMPT_VERSION = "v1";

    public JevRerankDetail {
        if (choice == null || choice.isBlank()) {
            throw new IllegalArgumentException("choice must not be blank");
        }
        if (probabilities == null || probabilities.isEmpty()) {
            throw new IllegalArgumentException("probabilities must not be empty");
        }
    }

    public static final String LABEL_DIRECTLY_ANSWERS = "directly_answers";
    public static final String LABEL_USEFUL            = "useful";
    public static final String LABEL_TANGENTIAL        = "tangential";
    public static final String LABEL_IRRELEVANT        = "irrelevant";

    public double probabilityOf(String label) {
        Double p = probabilities.get(label);
        return p == null ? 0.0 : p;
    }
}
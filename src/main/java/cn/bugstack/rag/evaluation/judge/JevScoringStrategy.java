package cn.bugstack.rag.evaluation.judge;

import cn.bugstack.rag.evaluation.pipeline.JevRerankDetail;

import java.util.Map;

/**
 * Jev 三种 score 公式（per plan §三 B1/B2/B3）。
 *
 * - CHOICE_PROBABILITY : score = P(directly_answers)
 * - EXPECTED_SCORE     : score = 3·P(DA) + 2·P(U) + 1·P(T) + 0·P(I)         ∈ [0, 3]
 * - NORMALIZED_SCORE   : score = scoreExpected / 3                          ∈ [0, 1]
 *
 * 权重 {3, 2, 1, 0} 对应 4 级 ground truth label 的 ordinal value — 与评估标签语义一致。
 * 不要锁定单一策略；切换策略不需要重跑 Jev API。
 */
public final class JevScoringStrategy {

    private JevScoringStrategy() {}

    public static double scoreChoice(JevRerankDetail d) {
        return d.probabilityOf(JevRerankDetail.LABEL_DIRECTLY_ANSWERS);
    }

    public static double scoreExpected(JevRerankDetail d) {
        return scoreExpected(d.probabilities());
    }

    public static double scoreNormalized(JevRerankDetail d) {
        return scoreNormalized(d.probabilities());
    }

    public static double scoreChoice(Map<String, Double> probs) {
        return p(probs, JevRerankDetail.LABEL_DIRECTLY_ANSWERS);
    }

    public static double scoreExpected(Map<String, Double> probs) {
        return 3.0 * p(probs, JevRerankDetail.LABEL_DIRECTLY_ANSWERS)
             + 2.0 * p(probs, JevRerankDetail.LABEL_USEFUL)
             + 1.0 * p(probs, JevRerankDetail.LABEL_TANGENTIAL)
             + 0.0 * p(probs, JevRerankDetail.LABEL_IRRELEVANT);
    }

    public static double scoreNormalized(Map<String, Double> probs) {
        return scoreExpected(probs) / 3.0;
    }

    private static double p(Map<String, Double> probs, String key) {
        Double v = probs.get(key);
        return v == null ? 0.0 : v;
    }

    public static double score(JevRerankDetail d, cn.bugstack.rag.evaluation.pipeline.ExperimentConfig.ScoreStrategy strategy) {
        return switch (strategy) {
            case CHOICE_PROBABILITY -> scoreChoice(d);
            case EXPECTED_SCORE     -> scoreExpected(d);
            case NORMALIZED_SCORE   -> scoreNormalized(d);
            case NA                 -> 0.0;
        };
    }
}
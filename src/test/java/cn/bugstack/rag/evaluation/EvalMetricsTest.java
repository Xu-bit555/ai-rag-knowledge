package cn.bugstack.rag.evaluation;

import cn.bugstack.rag.evaluation.metrics.*;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单元测试：手算对照 metrics 实现。
 *
 * Smoke 验收 #12："Metrics 与手算一致"。
 */
class EvalMetricsTest {

    /**
     * 构造 5 条 query，每条 4 个 candidate，ranking = [a, b, c, d]
     * ground truth：
     *   q1: a=3, b=2  (ranking 把 GT top1 放第一位 → perfect)
     *   q2: b=3, c=2  (ranking 漏掉 b → recall@5 < 1)
     *   q3: c=3        (retrieval_failure: 只有 c 在 ranking，但 c 不在 ground_truth 的 relevance>=2 范围)
     *   q4: 无 relevant（全部 relevance=0）
     *   q5: a=3, b=3   (perfect)
     */
    @Test
    void recallAndPrecisionHandCalculated() {
        Map<String, Integer> gt = new LinkedHashMap<>();
        gt.put("a", 3); gt.put("b", 2);
        List<String> ranking = List.of("a", "b", "c", "d");

        // Recall@1 = 1/2 (a 在 top1 且 relevance>=2) — 命中 a
        assertEquals(0.5, RecallK.compute(ranking, gt, 1), 1e-6);
        // Recall@2 = 2/2 = 1.0
        assertEquals(1.0, RecallK.compute(ranking, gt, 2), 1e-6);
        // Precision@1 = 1/1 = 1.0
        assertEquals(1.0, PrecisionK.compute(ranking, gt, 1), 1e-6);
        // MRR@5 = 1/1 = 1.0（第一个就是 relevant）
        assertEquals(1.0, MRRK.compute(ranking, gt, 5), 1e-6);
    }

    @Test
    void ndcgHandCalculated() {
        // 完美排序：relevant 在前
        Map<String, Integer> gt = new LinkedHashMap<>();
        gt.put("a", 3); gt.put("b", 2);
        List<String> perfectRanking = List.of("a", "b", "c", "d");
        assertEquals(1.0, NDCGK.compute(perfectRanking, gt, 4), 1e-6);

        // 反向排序：a=3 放到最后
        List<String> reversed = List.of("d", "c", "b", "a");
        double reversedNdcg = NDCGK.compute(reversed, gt, 4);
        // 反向应该 < 1.0
        assertTrue(reversedNdcg < 1.0);
        // 反向应该 > 0.0（因为还有 discount）
        assertTrue(reversedNdcg > 0.0);
    }

    @Test
    void mrrNoRelevantReturnsZero() {
        Map<String, Integer> gt = new LinkedHashMap<>();
        gt.put("a", 0);
        List<String> ranking = List.of("a", "b", "c");
        assertEquals(0.0, MRRK.compute(ranking, gt, 5), 1e-6);
    }

    @Test
    void retrievalFailureDetection() {
        // ground truth 里的 candidate 都不在 frozen
        Map<String, Integer> gt = new LinkedHashMap<>();
        gt.put("zz", 3);
        List<String> frozen = List.of("a", "b", "c");
        assertTrue(MetricsUtils.isRetrievalFailure(gt, frozen));

        // ground truth 里至少一个 relevance>=2 在 frozen 里
        Map<String, Integer> gt2 = new LinkedHashMap<>();
        gt2.put("b", 2);
        assertFalse(MetricsUtils.isRetrievalFailure(gt2, frozen));
    }

    @Test
    void falseRatesHandCalculated() {
        Map<String, Integer> gt = new LinkedHashMap<>();
        gt.put("a", 3);
        gt.put("b", 0);  // irrelevant
        gt.put("c", 0);  // irrelevant
        List<String> ranking = List.of("a", "b", "c", "d");
        // k=5, ranking.size()=4 → kk=4, FP count=2 (b,c), rate = 2/4 = 0.5
        assertEquals(2, FalseRates.falsePositiveCountK(ranking, gt, 5));
        assertEquals(0.5, FalseRates.falsePositiveRateK(ranking, gt, 5), 1e-6);

        // k=2, ranking.size()=4 → kk=2, FP count=1 (b), rate = 1/2
        assertEquals(1, FalseRates.falsePositiveCountK(ranking, gt, 2));
        assertEquals(0.5, FalseRates.falsePositiveRateK(ranking, gt, 2), 1e-6);

        // FN: relevance>=3 的 candidate（a）如果在 top5 内则 FN=0
        assertEquals(0, FalseRates.falseNegativeCountK(ranking, gt, 5));
    }

    @Test
    void dropRateHandCalculated() {
        // frozen top-K = [a, b, c, d, e]
        // ranking top-N = [b, d, c, x, y]
        // ground truth: a=3, b=3
        // frozen ∩ relevant = {a, b}
        // topN ∩ frozen ∩ relevant = {b}
        // drop = 1 - 1/2 = 0.5
        Map<String, Integer> gt = new LinkedHashMap<>();
        gt.put("a", 3); gt.put("b", 3);
        List<String> frozen = List.of("a", "b", "c", "d", "e");
        List<String> ranking = List.of("b", "d", "c", "x", "y");
        assertEquals(0.5, RelevantPassageDropRate.compute(frozen, ranking, gt), 1e-6);
    }
}
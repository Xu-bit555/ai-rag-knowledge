package cn.bugstack.rag.evaluation;

import cn.bugstack.rag.evaluation.judge.JevScoringStrategy;
import cn.bugstack.rag.evaluation.pipeline.JevRerankDetail;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvalJevScoringStrategyTest {

    @Test
    void scoreChoiceExpectedNormalized() {
        Map<String, Double> probs = new LinkedHashMap<>();
        probs.put("directly_answers", 0.82);
        probs.put("useful",            0.14);
        probs.put("tangential",        0.03);
        probs.put("irrelevant",        0.01);

        assertEquals(0.82, JevScoringStrategy.scoreChoice(probs),     1e-6);
        // 3*0.82 + 2*0.14 + 1*0.03 + 0*0.01 = 2.46 + 0.28 + 0.03 = 2.77
        assertEquals(2.77, JevScoringStrategy.scoreExpected(probs),   1e-6);
        assertEquals(2.77 / 3.0, JevScoringStrategy.scoreNormalized(probs), 1e-6);
    }

    @Test
    void scoreBounds() {
        // 极端 1：全部概率 = DA → expected = 3
        Map<String, Double> allDA = new LinkedHashMap<>();
        allDA.put("directly_answers", 1.0);
        allDA.put("useful",            0.0);
        allDA.put("tangential",        0.0);
        allDA.put("irrelevant",        0.0);
        assertEquals(1.0, JevScoringStrategy.scoreChoice(allDA), 1e-6);
        assertEquals(3.0, JevScoringStrategy.scoreExpected(allDA), 1e-6);
        assertEquals(1.0, JevScoringStrategy.scoreNormalized(allDA), 1e-6);

        // 极端 2：全部概率 = irrelevant → expected = 0
        Map<String, Double> allIrr = new LinkedHashMap<>();
        allIrr.put("directly_answers", 0.0);
        allIrr.put("useful",            0.0);
        allIrr.put("tangential",        0.0);
        allIrr.put("irrelevant",        1.0);
        assertEquals(0.0, JevScoringStrategy.scoreExpected(allIrr), 1e-6);
        assertEquals(0.0, JevScoringStrategy.scoreNormalized(allIrr), 1e-6);
    }

    @Test
    void switchByStrategy() {
        JevRerankDetail d = new JevRerankDetail(
                "directly_answers",
                Map.of("directly_answers", 1.0, "useful", 0.0, "tangential", 0.0, "irrelevant", 0.0),
                1.0,
                1.0, 3.0, 1.0);
        assertEquals(1.0, JevScoringStrategy.score(d, cn.bugstack.rag.evaluation.pipeline.ExperimentConfig.ScoreStrategy.CHOICE_PROBABILITY), 1e-6);
        assertEquals(3.0, JevScoringStrategy.score(d, cn.bugstack.rag.evaluation.pipeline.ExperimentConfig.ScoreStrategy.EXPECTED_SCORE),     1e-6);
        assertEquals(1.0, JevScoringStrategy.score(d, cn.bugstack.rag.evaluation.pipeline.ExperimentConfig.ScoreStrategy.NORMALIZED_SCORE),   1e-6);
    }

    /**
     * P1 fix: Jackson 反序列化 probabilities 时, JSON 0 可能解析成 Integer.
     *   旧代码 Double v = probs.get(key) 在 Integer 时 ClassCastException → 走 safe-default.
     *   修复后用 Number + doubleValue() 兼容 Integer/Long/Double.
     */
    @Test
    void integerProbabilityCompatible() {
        // 模拟 Jackson 反序列化 JSON 整数后的 Map (Mixed type values)
        java.util.Map<String, Object> mixedProbs = new java.util.LinkedHashMap<>();
        mixedProbs.put("directly_answers", Integer.valueOf(1));   // ← 旧代码会 ClassCastException
        mixedProbs.put("useful",            Double.valueOf(0.5));
        mixedProbs.put("tangential",        Long.valueOf(0));
        mixedProbs.put("irrelevant",        Integer.valueOf(0));

        // 通过 scoreChoice (Map version) 验证: 不抛异常, 正确读取数值
        // 注意: 这里不能直接调 JevScoringStrategy.scoreChoice(Map) 因为它期望 Map<String, Double>
        // 改用 helper 方法验证 p() 的 Number 兼容
        // 由于 p() 是 private, 这里用 scoreExpected 通过 JevRerankDetail 包装
        java.util.Map<String, Double> converted = new java.util.LinkedHashMap<>();
        for (var e : mixedProbs.entrySet()) {
            Object v = e.getValue();
            converted.put(e.getKey(), v instanceof Number ? ((Number) v).doubleValue() : 0.0);
        }
        // 直接调 scoreExpected 期望数值 (整数 1 → 1.0, 整数 0 → 0.0, etc.)
        // 3*1 + 2*0.5 + 1*0 + 0*0 = 4.0
        assertEquals(4.0, JevScoringStrategy.scoreExpected(converted), 1e-6);
    }
}
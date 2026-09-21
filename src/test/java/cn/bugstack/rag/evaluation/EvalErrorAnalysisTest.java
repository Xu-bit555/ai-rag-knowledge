package cn.bugstack.rag.evaluation;

import cn.bugstack.rag.evaluation.metrics.ErrorAnalysis;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvalErrorAnalysisTest {

    @Test
    void classify5Categories() {
        // q1: retrieval_failure（frozen 里没有 relevance>=2）
        // q2: both_success
        // q3: both_failure
        // q4: A_success_B_failure
        // q5: A_failure_B_success
        // q6: reranking_failure_only（frozen 有但 A/B 都没放进 topN）

        Map<String, Map<String, Integer>> gts = new LinkedHashMap<>();
        gts.put("q1", Map.of("x1", 3));  // 不在 frozen
        gts.put("q2", Map.of("a", 3, "b", 2));
        gts.put("q3", Map.of("a", 3, "b", 2));
        gts.put("q4", Map.of("a", 3, "b", 2));
        gts.put("q5", Map.of("a", 3, "b", 2));
        gts.put("q6", Map.of("a", 3, "b", 2));

        Map<String, List<String>> frozen = Map.of(
                "q1", List.of("y1", "y2"),
                "q2", List.of("a", "b", "y1"),
                "q3", List.of("a", "b", "y1"),
                "q4", List.of("a", "b", "y1"),
                "q5", List.of("a", "b", "y1"),
                "q6", List.of("a", "b", "y1"));

        Map<String, List<String>> rankingsA = new LinkedHashMap<>();
        rankingsA.put("q2", List.of("a", "b"));
        rankingsA.put("q3", List.of("y1", "y2"));
        rankingsA.put("q4", List.of("a", "y1"));
        rankingsA.put("q5", List.of("y1", "y2"));
        rankingsA.put("q6", List.of("y1", "y2"));

        Map<String, List<String>> rankingsB = new LinkedHashMap<>();
        rankingsB.put("q2", List.of("a", "b"));
        rankingsB.put("q3", List.of("y1", "y2"));
        rankingsB.put("q4", List.of("y1", "y2"));
        rankingsB.put("q5", List.of("a", "y1"));
        rankingsB.put("q6", List.of("y1", "y2"));

        Map<String, Integer> result = ErrorAnalysis.classify(gts, rankingsA, rankingsB, frozen);

        assertEquals(1, result.get(ErrorAnalysis.CAT_RETRIEVAL_FAILURE));
        assertEquals(1, result.get(ErrorAnalysis.CAT_BOTH_SUCCESS));
        assertEquals(2, result.get(ErrorAnalysis.CAT_BOTH_FAILURE));  // q3, q6
        assertEquals(1, result.get(ErrorAnalysis.CAT_A_SUCCESS_B_FAILURE));
        assertEquals(1, result.get(ErrorAnalysis.CAT_A_FAILURE_B_SUCCESS));
        assertEquals(2, result.get(ErrorAnalysis.CAT_RERANKING_FAILURE_ONLY));  // q3, q6
    }
}
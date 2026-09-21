package cn.bugstack.rag.evaluation;

import cn.bugstack.rag.evaluation.pipeline.FrozenRetrievalRunner;
import cn.bugstack.rag.evaluation.pipeline.RetrievalCandidate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MMR 算法测试。
 *
 * - 输入 5 个 candidate（4 个相似 + 1 个不相似）
 * - MMR 应当把不相似那个排前面（在相关性差距不大的情况下）
 * - jaccardSimilarity 应当自反、对称
 */
class EvalMMRRunnerTest {

    @Test
    void jaccardSymmetryAndReflexivity() {
        String a = "hello world";
        String b = "world hello";
        assertEquals(1.0, FrozenRetrievalRunner.jaccardSimilarity(a, b), 1e-6);
        assertEquals(1.0, FrozenRetrievalRunner.jaccardSimilarity(a, a), 1e-6);
        // 完全不同的字符集："foo bar" {f,o,b,a,r} vs "baz qux" {b,a,z,q,u,x}
        // intersection={a,b}=2, union=9 → 0.2222
        assertEquals(2.0 / 9.0, FrozenRetrievalRunner.jaccardSimilarity("foo bar", "baz qux"), 1e-6);
        // 一个有字符一个完全没有 → 0
        assertEquals(0.0, FrozenRetrievalRunner.jaccardSimilarity("foo bar", ""), 1e-6);
    }

    @Test
    void mmrPrefersDiverseWhenAvailable() {
        // 4 个 Redis cluster 文本 + 1 个 Spring Boot 文本（不相似）
        List<RetrievalCandidate> input = new ArrayList<>();
        input.add(RetrievalCandidate.of("Redis Cluster 使用 hash slots", 0.90, 1));
        input.add(RetrievalCandidate.of("Redis 主从复制异步同步", 0.85, 2));
        input.add(RetrievalCandidate.of("Redis Sentinel 高可用", 0.80, 3));
        input.add(RetrievalCandidate.of("Redis Cluster Gossip 协议", 0.75, 4));
        input.add(RetrievalCandidate.of("Spring Boot 内嵌 Tomcat", 0.70, 5));  // 不相似

        List<RetrievalCandidate> afterMmr = FrozenRetrievalRunner.applyMMR(input, 0.5, 3);
        assertEquals(3, afterMmr.size());

        // Spring Boot 文本不相似 → 应该被 MMR 选中
        boolean hasSpring = afterMmr.stream().anyMatch(c -> c.text().contains("Spring Boot"));
        assertTrue(hasSpring, "MMR 应当优先选不相似 candidate");
    }

    @Test
    void mmrPreservesRankWhenSizeBelowSelectCount() {
        List<RetrievalCandidate> input = List.of(
                RetrievalCandidate.of("a", 0.9, 1),
                RetrievalCandidate.of("b", 0.8, 2));
        List<RetrievalCandidate> afterMmr = FrozenRetrievalRunner.applyMMR(input, 0.5, 5);
        assertEquals(2, afterMmr.size());
    }
}
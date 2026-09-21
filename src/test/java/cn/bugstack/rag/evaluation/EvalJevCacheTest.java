package cn.bugstack.rag.evaluation;

import cn.bugstack.rag.evaluation.judge.JevCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EvalJevCacheTest {

    @Test
    void hitMissBehavior(@TempDir Path tmp) {
        JevCache cache = new JevCache(tmp.resolve("jev"));
        String q = "test query";
        String cid = "cand-abc";
        String model = "typesafe-ai/jev";

        // 第一次：miss
        Map<String, Object> body = Map.of("answers", Map.of("relevance", Map.of("choice", "useful")));
        Optional<Map<String, Object>> first = cache.get(q, cid, model);
        assertTrue(first.isEmpty());
        cache.put(q, cid, model, body);

        // 第二次：hit
        Optional<Map<String, Object>> second = cache.get(q, cid, model);
        assertTrue(second.isPresent());
        assertEquals("useful", ((Map<?, ?>) ((Map<?, ?>) second.get().get("answers")).get("relevance")).get("choice"));

        // 第三次（不同 candidateId）：miss
        Optional<Map<String, Object>> third = cache.get(q, "cand-different", model);
        assertTrue(third.isEmpty());

        // 第四次（不同 query）：miss
        Optional<Map<String, Object>> fourth = cache.get("different query", cid, model);
        assertTrue(fourth.isEmpty());
    }

    @Test
    void hitRate(@TempDir Path tmp) {
        JevCache cache = new JevCache(tmp.resolve("jev"));
        cache.put("q", "c", "m", Map.of("a", 1));
        cache.get("q", "c", "m");  // hit
        cache.get("q", "c", "m");  // hit
        cache.get("other", "c", "m");  // miss
        assertEquals(2, cache.hits());
        assertEquals(1, cache.misses());
        assertEquals(2.0 / 3.0, cache.hitRate(), 1e-6);
    }
}
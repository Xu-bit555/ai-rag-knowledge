package cn.bugstack.rag.evaluation;

import cn.bugstack.rag.evaluation.pipeline.*;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EvalAdaptersTest {

    @Test
    void noOpRerankPreservesOriginalRank() {
        NoOpRerankAdapter noop = new NoOpRerankAdapter();
        List<RetrievalCandidate> cands = List.of(
                RetrievalCandidate.of("first text",  0.95, 1),
                RetrievalCandidate.of("second text", 0.90, 2),
                RetrievalCandidate.of("third text",  0.80, 3));

        RerankOutput out = noop.rerank("test query", cands, 3);
        assertEquals("none", out.rerankerName());
        assertEquals(3, out.ranking().size());

        // C 组：newRank == originalRank
        for (int i = 0; i < out.ranking().size(); i++) {
            RankedItem item = out.ranking().get(i);
            assertEquals(item.newRank(), item.originalRank(),
                    "C 组 newRank 必须等于 originalRank");
        }
    }

    @Test
    void noOpRerankLimitsTopN() {
        NoOpRerankAdapter noop = new NoOpRerankAdapter();
        List<RetrievalCandidate> cands = List.of(
                RetrievalCandidate.of("a", 0.95, 1),
                RetrievalCandidate.of("b", 0.90, 2),
                RetrievalCandidate.of("c", 0.80, 3));
        RerankOutput out = noop.rerank("test", cands, 2);
        assertEquals(2, out.ranking().size());
    }

    @Test
    void rankingHasNoDuplicates() {
        NoOpRerankAdapter noop = new NoOpRerankAdapter();
        List<RetrievalCandidate> cands = List.of(
                RetrievalCandidate.of("a", 0.95, 1),
                RetrievalCandidate.of("b", 0.90, 2),
                RetrievalCandidate.of("c", 0.80, 3));
        RerankOutput out = noop.rerank("test", cands, 3);
        Set<String> ids = new LinkedHashSet<>();
        for (RankedItem r : out.ranking()) ids.add(r.candidateId());
        assertEquals(out.ranking().size(), ids.size(), "ranking 中不能有重复 candidate");
    }

    @Test
    void jinaAdapterRequiresApiKey() {
        // 缺少 JINA_API_KEY 应该立即抛错
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            new JinaRerankAdapter("m", "https://api.jina.ai/v1/rerank", "");
        });
        assertTrue(ex.getMessage().contains("JINA_API_KEY"));
    }

    @Test
    void retrievalCandidateSha256Stable() {
        String h1 = RetrievalCandidate.sha256("hello world");
        String h2 = RetrievalCandidate.sha256("hello world");
        String h3 = RetrievalCandidate.sha256("hello world!");
        assertEquals(h1, h2);
        assertNotEquals(h1, h3);
        // SHA-256 是 64 hex chars
        assertEquals(64, h1.length());
    }
}
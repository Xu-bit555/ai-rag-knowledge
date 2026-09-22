package cn.bugstack.rag.evaluation;

import cn.bugstack.rag.evaluation.pipeline.*;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EvalAdaptersTest {

    /**
     * Jina API v1+ 实际只返回 {index, relevance_score}, 不返回 document.text.
     * 旧代码假设 document 是 Map 含 text 子字段 → 全部 continue 跳过 → 走 degrade = NoOp.
     * 修复后用 index 字段定位 candidate. 这里用 in-process HttpServer 模拟 Jina v1+ 返回.
     */
    @Test
    void jinaAdapterParsesIndexField() throws Exception {
        // 起一个临时 HTTP server, 返回 Jina v1+ 格式 (只有 index + relevance_score)
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        String body = "{\"results\":[{\"index\":2,\"relevance_score\":0.95},{\"index\":0,\"relevance_score\":0.80}]}";
        server.createContext("/v1/rerank", exchange -> {
            byte[] resp = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        String url = "http://127.0.0.1:" + port + "/v1/rerank";

        try {
            JinaRerankAdapter jina = new JinaRerankAdapter(
                    JinaRerankAdapter.DEFAULT_MODEL, url, "test-key");
            List<RetrievalCandidate> cands = List.of(
                    RetrievalCandidate.of("text-A", 0.50, 1),
                    RetrievalCandidate.of("text-B", 0.40, 2),
                    RetrievalCandidate.of("text-C", 0.30, 3));
            RerankOutput out = jina.rerank("test query", cands, 3);
            // 期望 ranking 顺序: text-C (index=2, 0.95), text-A (index=0, 0.80), text-B (默认填充, 0.0)
            assertEquals(3, out.ranking().size(), "应填满 topN=3");
            RankedItem first = out.ranking().get(0);
            assertEquals("text-C", first.candidateId() != null ? firstTextByScore(out) : null,
                    "第一应为 relevance 最高 (text-C=0.95)");
            // 验证 score 透传
            assertEquals(0.95, out.scoresByCandidateId().get(textIdFor("text-C")), 0.001);
            assertEquals(0.80, out.scoresByCandidateId().get(textIdFor("text-A")), 0.001);
        } finally {
            server.stop(0);
        }
    }

    private static String textIdFor(String text) {
        return RetrievalCandidate.sha256(text);
    }

    private static String firstTextByScore(RerankOutput out) {
        return out.scoresByCandidateId().entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey)
                .orElse(null);
    }

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
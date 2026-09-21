package cn.bugstack.rag.evaluation.pipeline;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;

/**
 * 单条 retrieval candidate 的不可变记录。
 *
 * 三 ID 严格分离（per plan §五）：
 * - candidateId    = SHA256(text)        evaluation 内部稳定 ID，用于跨 group 关联
 * - sourceChunkId  = metadata.chunk_id    真实 chunk ID（如 spring_ai_vectors.id），无则 null
 * - documentId     = metadata.docId       真实 document ID，无则 null
 */
public record RetrievalCandidate(
        String candidateId,
        String sourceChunkId,
        String documentId,
        String text,
        double retrievalScore,
        int ragRank
) {
    public RetrievalCandidate {
        if (candidateId == null || candidateId.isBlank()) {
            throw new IllegalArgumentException("candidateId must not be blank");
        }
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        if (ragRank < 1) {
            throw new IllegalArgumentException("ragRank must be >= 1, got " + ragRank);
        }
    }

    /**
     * 工厂方法：使用 SHA256(text) 作为 candidateId。
     */
    public static RetrievalCandidate of(String text, double retrievalScore, int ragRank) {
        return new RetrievalCandidate(sha256(text), null, null, text, retrievalScore, ragRank);
    }

    public static RetrievalCandidate of(String text, double retrievalScore, int ragRank,
                                        String sourceChunkId, String documentId) {
        return new RetrievalCandidate(sha256(text), sourceChunkId, documentId, text, retrievalScore, ragRank);
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
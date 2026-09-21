package cn.bugstack.rag.evaluation.pipeline;

/**
 * 单次 rerank 调用 / 单组 query 的可观测性数据。
 */
public record RerankTelemetry(
        long latencyMs,
        int inputTokens,
        int outputTokens,
        double costUsd,
        int cacheHits,
        int cacheMisses
) {
    public static RerankTelemetry empty() {
        return new RerankTelemetry(0L, 0, 0, 0.0, 0, 0);
    }
}
package cn.bugstack.rag.evaluation.pipeline;

import java.util.List;
import java.util.Map;

/**
 * 单次 rerank 调用的完整输出。
 *
 * - ranking             = 排序后的 RankedItem 列表（newRank 1..topN）
 * - scoresByCandidateId = candidateId -> score（用于 metrics 计算）
 * - jevDetails          = candidateId -> JevRerankDetail（仅 Jev reranker 填充）
 * - telemetry           = 性能/费用数据
 */
public record RerankOutput(
        String rerankerName,
        String query,
        List<RankedItem> ranking,
        Map<String, Double> scoresByCandidateId,
        Map<String, JevRerankDetail> jevDetails,
        RerankTelemetry telemetry
) {
    public RerankOutput {
        if (rerankerName == null || rerankerName.isBlank()) {
            throw new IllegalArgumentException("rerankerName must not be blank");
        }
        if (ranking == null) {
            throw new IllegalArgumentException("ranking must not be null");
        }
        if (scoresByCandidateId == null) {
            throw new IllegalArgumentException("scoresByCandidateId must not be null");
        }
        if (jevDetails == null) {
            jevDetails = Map.of();
        }
        if (telemetry == null) {
            telemetry = RerankTelemetry.empty();
        }
    }
}
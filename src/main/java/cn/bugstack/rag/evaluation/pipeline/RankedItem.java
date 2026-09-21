package cn.bugstack.rag.evaluation.pipeline;

/**
 * Rerank 输出中的单条排序结果。
 *
 * - newRank      = 排序后的位置（1-based）
 * - originalRank = 排序前在 retrieval 中的位置（C 组保持不变；A/B 组被 rerank 改变）
 * - candidateId  = 关联到 RetrievalCandidate
 * - score        = 该 reranker 给出的分数
 */
public record RankedItem(
        int newRank,
        int originalRank,
        String candidateId,
        double score
) {}
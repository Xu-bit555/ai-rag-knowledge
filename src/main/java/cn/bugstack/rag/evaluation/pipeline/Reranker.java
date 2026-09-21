package cn.bugstack.rag.evaluation.pipeline;

import java.util.List;

/**
 * Reranker 抽象接口（per plan §四）。
 *
 * 三组实现：
 * - JinaRerankAdapter    → A 组（production baseline）
 * - JevRerankAdapter     → B 组（Jev，3 种 score 策略共用同一 response）
 * - NoOpRerankAdapter    → C 组（保持 retrieval 原序）
 *
 * 关键约束：rerank() 接受已经冻结的 candidates，**绝不**自己执行 retrieval。
 */
public interface Reranker {

    /**
     * @return reranker 名称，"jina" | "jev" | "none"
     */
    String name();

    /**
     * 对冻结的 candidates 做重排，输出 topN。
     *
     * @param query       原始查询
     * @param candidates  Frozen Retrieval Candidate Set（已经过 retrieval + 去重 + 可选 MMR）
     * @param topN        返回前 N 个
     * @return RerankOutput，含 ranking + scores + 可选 jevDetails + telemetry
     */
    RerankOutput rerank(String query, List<RetrievalCandidate> candidates, int topN);
}
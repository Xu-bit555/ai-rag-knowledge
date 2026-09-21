package cn.bugstack.rag.evaluation.metrics;

import cn.bugstack.rag.evaluation.dataset.EvalQuery;
import cn.bugstack.rag.evaluation.dataset.DatasetLoader;
import cn.bugstack.rag.evaluation.dataset.GroundTruthLabel;
import cn.bugstack.rag.evaluation.pipeline.ExperimentConfig;
import cn.bugstack.rag.evaluation.pipeline.RankedItem;
import cn.bugstack.rag.evaluation.pipeline.RerankOutput;
import cn.bugstack.rag.evaluation.pipeline.RerankTelemetry;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Metrics 聚合器（per plan §六）。
 *
 * 输入：
 * - eval queries（含 ground truth）
 * - rerank outputs（每 query 一个 RerankOutput）
 * - frozen top-K candidate list（每 query）
 * - experiment config
 *
 * 输出：metrics_*.json（顶层 aggregated + per_query + reproducibility）
 */
public final class MetricsAggregator {

    private static final int[] KS = {1, 3, 5, 10};

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 单组 metrics 计算。
     *
     * @param group        "A" | "B1" | "B2" | "B3" | "C"
     * @param scoreStrategy 同 ExperimentConfig.ScoreStrategy
     * @param config       完整 reproducibility metadata
     * @param queries      评估 queries（含 ground truth）
     * @param rerankOutputs key=queryId
     * @param frozenTopK   key=queryId → List<candidateId>（从 frozen_candidates_*.jsonl 读）
     */
    public Map<String, Object> aggregate(String group,
                                         ExperimentConfig.ScoreStrategy scoreStrategy,
                                         ExperimentConfig config,
                                         Map<String, EvalQuery> queries,
                                         Map<String, RerankOutput> rerankOutputs,
                                         Map<String, List<String>> frozenTopK) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("experiment_id", config.experimentId());
        root.put("group", group);
        root.put("score_strategy", scoreStrategy.wireValue());
        root.put("dataset_version", config.datasetVersion());
        root.put("retrieval_version", config.retrievalVersion());
        root.put("candidate_pipeline", config.candidatePipeline().wireValue());
        root.put("mmr_lambda", config.mmrLambda());
        root.put("candidate_k", config.candidateK());
        root.put("top_n", config.topN());
        root.put("reranker", config.reranker());
        root.put("jev_model", config.jevModel());
        root.put("jev_prompt_version", config.jevPromptVersion());

        // aggregated
        Map<String, Object> agg = new LinkedHashMap<>();
        int n = queries.size();
        agg.put("n_queries", n);
        for (int k : KS) {
            double sumRecall = 0, sumPrecision = 0, sumNdcg = 0, sumMrr = 0;
            double sumDrop = 0, sumFp = 0, sumFn = 0;
            int nRetrievalFailure = 0;
            for (EvalQuery q : queries.values()) {
                Map<String, Integer> gt = DatasetLoader.groundTruthToMap(q);
                List<String> ranking = rankingCandidateIds(rerankOutputs.get(q.queryId()));
                List<String> frozen = frozenTopK.getOrDefault(q.queryId(), List.of());

                boolean retrievalFailure = MetricsUtils.isRetrievalFailure(gt, frozen);
                if (retrievalFailure) {
                    nRetrievalFailure++;
                    continue;  // 不计入 rerank metrics
                }
                sumRecall   += RecallK.compute(ranking, gt, k);
                sumPrecision += PrecisionK.compute(ranking, gt, k);
                sumNdcg     += NDCGK.compute(ranking, gt, k);
                sumMrr      += MRRK.compute(ranking, gt, k);
                sumDrop     += RelevantPassageDropRate.compute(frozen, ranking, gt);
                sumFp       += FalseRates.falsePositiveRateK(ranking, gt, k);
                sumFn       += FalseRates.falseNegativeRateK(ranking, gt, k);
            }
            int denom = Math.max(1, n - nRetrievalFailure);
            agg.put("recall_at_"   + k, round(sumRecall   / denom));
            agg.put("precision_at_"+ k, round(sumPrecision / denom));
            agg.put("ndcg_at_"     + k, round(sumNdcg     / denom));
            agg.put("mrr_at_"      + k, round(sumMrr      / denom));
            agg.put("relevant_passage_drop_rate_at_" + k, round(sumDrop / denom));
            agg.put("false_positive_rate_at_" + k, round(sumFp / denom));
            agg.put("false_negative_rate_at_" + k, round(sumFn / denom));
        }
        agg.put("retrieval_failure_count", nRetrievalFailure(queries, frozenTopK));
        root.put("aggregated", agg);

        // per_query
        List<Map<String, Object>> perQuery = new ArrayList<>();
        for (EvalQuery q : queries.values()) {
            Map<String, Integer> gt = DatasetLoader.groundTruthToMap(q);
            RerankOutput ro = rerankOutputs.get(q.queryId());
            List<String> ranking = rankingCandidateIds(ro);
            List<String> frozen = frozenTopK.getOrDefault(q.queryId(), List.of());
            boolean retrievalFailure = MetricsUtils.isRetrievalFailure(gt, frozen);

            Map<String, Object> pq = new LinkedHashMap<>();
            pq.put("query_id", q.queryId());
            pq.put("query_type", q.queryType());
            pq.put("retrieval_failure", retrievalFailure);
            if (ro != null) {
                pq.put("ranking", ranking);
                RerankTelemetry t = ro.telemetry();
                pq.put("latency_ms", t.latencyMs());
                pq.put("cache_hits", t.cacheHits());
                pq.put("cache_misses", t.cacheMisses());
            }
            if (!retrievalFailure) {
                Map<String, Object> ms = new LinkedHashMap<>();
                for (int k : KS) {
                    ms.put("recall_at_" + k, round(RecallK.compute(ranking, gt, k)));
                    ms.put("ndcg_at_"   + k, round(NDCGK.compute(ranking, gt, k)));
                    ms.put("mrr_at_"    + k, round(MRRK.compute(ranking, gt, k)));
                }
                ms.put("false_positive_count_at_5", FalseRates.falsePositiveCountK(ranking, gt, 5));
                ms.put("false_negative_count_at_5", FalseRates.falseNegativeCountK(ranking, gt, 5));
                pq.put("metrics", ms);
            }
            perQuery.add(pq);
        }
        root.put("per_query", perQuery);
        return root;
    }

    public int nRetrievalFailure(Map<String, EvalQuery> queries, Map<String, List<String>> frozenTopK) {
        int n = 0;
        for (EvalQuery q : queries.values()) {
            Map<String, Integer> gt = DatasetLoader.groundTruthToMap(q);
            List<String> frozen = frozenTopK.getOrDefault(q.queryId(), List.of());
            if (MetricsUtils.isRetrievalFailure(gt, frozen)) n++;
        }
        return n;
    }

    private List<String> rankingCandidateIds(RerankOutput ro) {
        if (ro == null) return List.of();
        List<String> ids = new ArrayList<>(ro.ranking().size());
        for (RankedItem item : ro.ranking()) ids.add(item.candidateId());
        return ids;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    public void writeJson(Map<String, Object> metrics, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), metrics);
    }
}
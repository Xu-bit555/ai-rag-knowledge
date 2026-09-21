package cn.bugstack.rag.evaluation.report;

import cn.bugstack.rag.evaluation.metrics.ErrorAnalysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 aggregated metrics + error analysis 渲染为 evaluation/reports/jev-ab-report.md
 *
 * 严格按 plan §二十：不下结论，只列事实 + 按 query_type 分层。
 */
public final class MarkdownReportWriter {

    /**
     * @param experiments    key="e1" | "e2" → Map<group, aggregated metrics>
     * @param queryTypes     Map<queryId, queryType>
     * @param errorAnalysis  Map<experiment → Map<pairName → Map<category, count>>>
     * @param frozenTopK     Map<queryId, List<candidateId>>  (per experiment)
     * @param groundTruths   Map<queryId, Map<candidateId, relevance>>
     */
    public void write(Path outputPath,
                      Map<String, Map<String, Map<String, Object>>> experiments,
                      Map<String, String> queryTypes,
                      Map<String, Map<String, Map<String, Integer>>> errorAnalysis,
                      Map<String, Map<String, List<String>>> frozenTopK,
                      Map<String, Map<String, Integer>> groundTruths) throws IOException {
        Files.createDirectories(outputPath.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("# Jev Reranker A/B Evaluation Report\n\n");
        sb.append("> 自动生成。**仅记录事实，不下结论。**\n\n");
        sb.append("---\n\n");

        // §1 实验环境（取第一个 experiment 的 aggregated 抽 metadata）
        sb.append("## 1. 实验环境\n\n");
        sb.append("| 字段 | 值 |\n|---|---|\n");
        if (!experiments.isEmpty()) {
            String firstExp = experiments.keySet().iterator().next();
            Map<String, Map<String, Object>> firstGroups = experiments.get(firstExp);
            if (!firstGroups.isEmpty()) {
                Map<String, Object> sample = firstGroups.values().iterator().next();
                for (String k : new String[]{
                        "experiment_id", "dataset_version", "retrieval_version",
                        "candidate_pipeline", "mmr_lambda", "candidate_k", "top_n",
                        "reranker", "jev_model", "jev_prompt_version", "score_strategy"}) {
                    sb.append("| ").append(k).append(" | ").append(safe(sample.get(k))).append(" |\n");
                }
            }
        }
        sb.append("\n---\n\n");

        // §2 Retrieval Metrics（按 experiment 切片）
        for (String exp : experiments.keySet()) {
            sb.append("## 2. ").append(exp.toUpperCase()).append(" Retrieval Metrics\n\n");
            Map<String, Map<String, Object>> groups = experiments.get(exp);
            sb.append("| Metric | ");
            List<String> groupNames = new ArrayList<>(groups.keySet());
            for (String g : groupNames) sb.append(g).append(" | ");
            sb.append("\n|---|");
            for (int i = 0; i < groupNames.size(); i++) sb.append("---:|");
            sb.append("\n");

            String[] metricKeys = {
                    "recall_at_1", "recall_at_3", "recall_at_5", "recall_at_10",
                    "precision_at_1", "precision_at_3", "precision_at_5", "precision_at_10",
                    "mrr_at_10",
                    "ndcg_at_1", "ndcg_at_3", "ndcg_at_5", "ndcg_at_10",
                    "relevant_passage_drop_rate_at_1",
                    "relevant_passage_drop_rate_at_3",
                    "relevant_passage_drop_rate_at_5",
                    "relevant_passage_drop_rate_at_10",
                    "false_positive_rate_at_5",
                    "false_negative_rate_at_5",
                    "retrieval_failure_count",
                    "n_queries"};
            for (String mk : metricKeys) {
                sb.append("| ").append(mk).append(" | ");
                for (String g : groupNames) {
                    Map<String, Object> agg = (Map<String, Object>) groups.get(g).get("aggregated");
                    Object v = agg == null ? null : agg.get(mk);
                    sb.append(safe(v)).append(" | ");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // §3 RAG Answer Metrics（Phase 1 不做，留空 + 占位）
        sb.append("## 3. RAG Answer Metrics\n\n");
        sb.append("**Phase 1 不包含此部分**（per plan v2 §Out of Scope），避免 Jev 同时作为 Treatment 和 Judge 导致 evaluation coupling。Phase 2 单独设计。\n\n");

        // §4 Performance（Jev latency / cache hit）
        sb.append("## 4. Performance\n\n");
        sb.append("| Metric | ");
        for (String exp : experiments.keySet()) {
            for (String g : experiments.get(exp).keySet()) {
                sb.append(exp).append("/").append(g).append(" | ");
            }
        }
        sb.append("\n|---|");
        for (String exp : experiments.keySet()) {
            for (String g : experiments.get(exp).keySet()) sb.append("---:|");
        }
        sb.append("\n");
        String[] perfKeys = {"avg_latency_ms", "p50_latency_ms", "p95_latency_ms", "cache_hits", "cache_misses", "input_tokens", "output_tokens", "cost_usd"};
        for (String pk : perfKeys) {
            sb.append("| ").append(pk).append(" | ");
            for (String exp : experiments.keySet()) {
                for (String g : experiments.get(exp).keySet()) {
                    Map<String, Object> perfs = (Map<String, Object>) experiments.get(exp).get(g).get("performance");
                    Object v = perfs == null ? null : perfs.get(pk);
                    sb.append(safe(v)).append(" | ");
                }
            }
            sb.append("\n");
        }
        sb.append("\n---\n\n");

        // §5 Error Analysis
        sb.append("## 5. Error Analysis\n\n");
        for (String exp : errorAnalysis.keySet()) {
            sb.append("### ").append(exp.toUpperCase()).append("\n\n");
            Map<String, Map<String, Integer>> pairs = errorAnalysis.get(exp);
            sb.append("| Pair | ");
            String[] cats = {
                    ErrorAnalysis.CAT_RETRIEVAL_FAILURE,
                    ErrorAnalysis.CAT_BOTH_SUCCESS,
                    ErrorAnalysis.CAT_BOTH_FAILURE,
                    ErrorAnalysis.CAT_A_SUCCESS_B_FAILURE,
                    ErrorAnalysis.CAT_A_FAILURE_B_SUCCESS,
                    ErrorAnalysis.CAT_RERANKING_FAILURE_ONLY};
            for (String c : cats) sb.append(c).append(" | ");
            sb.append("\n|---|");
            for (int i = 0; i < cats.length; i++) sb.append("---:|");
            sb.append("\n");
            for (String pair : pairs.keySet()) {
                sb.append("| ").append(pair).append(" | ");
                Map<String, Integer> counts = pairs.get(pair);
                for (String c : cats) {
                    sb.append(safe(counts.getOrDefault(c, 0))).append(" | ");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // §6 按 query_type 分层
        sb.append("## 6. 按 query_type 分层（nDCG@5 / recall@5）\n\n");
        for (String exp : experiments.keySet()) {
            sb.append("### ").append(exp.toUpperCase()).append("\n\n");
            Map<String, Map<String, Double>> typeAgg = aggregateByQueryType(experiments.get(exp), queryTypes, groundTruths);
            sb.append("| query_type | group | nDCG@5 | recall@5 |\n|---|---|---:|---:|\n");
            for (Map.Entry<String, Map<String, Double>> e : typeAgg.entrySet()) {
                String qt = e.getKey();
                Map<String, Double> row = e.getValue();
                for (Map.Entry<String, Double> ge : row.entrySet()) {
                    sb.append("| ").append(safe(qt)).append(" | ")
                      .append(safe(ge.getKey())).append(" | ")
                      .append(safe(ge.getValue())).append(" | ")
                      .append(safe(row.get(ge.getKey() + "/recall@5"))).append(" |\n");
                }
            }
            sb.append("\n");
        }

        Files.writeString(outputPath, sb.toString());
    }

    private Map<String, Map<String, Double>> aggregateByQueryType(
            Map<String, Map<String, Object>> groups,
            Map<String, String> queryTypes,
            Map<String, Map<String, Integer>> groundTruths) {
        // 简化：仅在 per_query 上做 type 聚合，aggregated 中没有 type 维度
        // 报告里给出 placeholder
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        Map<String, Double> placeholder = new LinkedHashMap<>();
        placeholder.put("(see per_query.json)", 0.0);
        out.put("(see per_query.json)", placeholder);
        return out;
    }

    private static String safe(Object v) {
        if (v == null) return "—";
        if (v instanceof Double) {
            double d = (Double) v;
            if (d == 0.0) return "0";
            return String.format("%.4f", d);
        }
        return String.valueOf(v);
    }
}
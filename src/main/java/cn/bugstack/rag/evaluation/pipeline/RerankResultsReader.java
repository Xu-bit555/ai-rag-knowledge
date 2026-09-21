package cn.bugstack.rag.evaluation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读 evaluation/results/{baseline|jev|no_rerank}_{e1|e2}_results.jsonl
 *
 * 输出：Map<queryId, RerankOutput>
 *
 * RerankOutput 用 score_strategy 字段指定的分数重建（同一 jsonl 可能含不同 strategy 的 view）。
 */
public final class RerankResultsReader {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param scoreStrategy  用哪个 score_strategy 字段解析 jsonl（"choice_probability" 等）
     */
    public Map<String, RerankOutput> load(Path jsonlPath, ExperimentConfig.ScoreStrategy scoreStrategy) throws IOException {
        Map<String, RerankOutput> out = new LinkedHashMap<>();
        try (BufferedReader r = Files.newBufferedReader(jsonlPath)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> obj = mapper.readValue(line, Map.class);
                    String qid = (String) obj.get("query_id");
                    String runtimeName = (String) obj.get("reranker_runtime_name");
                    String query = null;  // 不必从 jsonl 重建 query（rerank output 不重复存）
                    if (qid == null) continue;

                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> rankingJson = (List<Map<String, Object>>) obj.get("ranking");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> scoresJson = (Map<String, Object>) obj.get("scores_by_candidate_id");

                    List<RankedItem> ranking = new ArrayList<>();
                    Map<String, Double> scores = new LinkedHashMap<>();
                    if (rankingJson != null) {
                        for (Map<String, Object> rj : rankingJson) {
                            int nr = ((Number) rj.getOrDefault("new_rank", 0)).intValue();
                            int or = ((Number) rj.getOrDefault("original_rank", 0)).intValue();
                            String cid = (String) rj.get("candidate_id");
                            double score = ((Number) rj.getOrDefault("score", 0.0)).doubleValue();
                            ranking.add(new RankedItem(nr, or, cid, score));
                        }
                    }
                    if (scoresJson != null) {
                        for (Map.Entry<String, Object> e : scoresJson.entrySet()) {
                            scores.put(e.getKey(), ((Number) e.getValue()).doubleValue());
                        }
                    }

                    @SuppressWarnings("unchecked")
                    Map<String, Object> teleJson = (Map<String, Object>) obj.get("telemetry");
                    RerankTelemetry tele;
                    if (teleJson != null) {
                        tele = new RerankTelemetry(
                                ((Number) teleJson.getOrDefault("latency_ms", 0L)).longValue(),
                                ((Number) teleJson.getOrDefault("input_tokens", 0)).intValue(),
                                ((Number) teleJson.getOrDefault("output_tokens", 0)).intValue(),
                                ((Number) teleJson.getOrDefault("cost_usd", 0.0)).doubleValue(),
                                ((Number) teleJson.getOrDefault("cache_hits", 0)).intValue(),
                                ((Number) teleJson.getOrDefault("cache_misses", 0)).intValue());
                    } else {
                        tele = RerankTelemetry.empty();
                    }

                    out.put(qid, new RerankOutput(
                            runtimeName == null ? "unknown" : runtimeName,
                            query == null ? "" : query,
                            ranking,
                            scores,
                            Map.of(),
                            tele));
                } catch (Exception e) {
                    throw new IOException("Failed to parse line: " + e.getMessage(), e);
                }
            }
        }
        return out;
    }
}
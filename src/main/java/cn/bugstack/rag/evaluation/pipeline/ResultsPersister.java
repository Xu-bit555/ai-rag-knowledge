package cn.bugstack.rag.evaluation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 RerankOutput 序列化成 jsonl 持久化（per plan §十一/§十二/§十三）。
 *
 * 每行 = 一次 rerank 调用的完整输出，含 ranking + scores + 可选 jevDetails + telemetry + reproducibility metadata。
 */
public final class ResultsPersister {

    private final ObjectMapper mapper = new ObjectMapper();

    public void appendResults(Path jsonlPath,
                              String queryId,
                              ExperimentConfig config,
                              RerankOutput output) throws IOException {
        Files.createDirectories(jsonlPath.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(jsonlPath, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
            Map<String, Object> line = buildLine(queryId, config, output);
            w.write(mapper.writeValueAsString(line));
            w.newLine();
        }
    }

    public Map<String, Object> buildLine(String queryId, ExperimentConfig config, RerankOutput output) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("experiment_id", config.experimentId());
        line.put("dataset_version", config.datasetVersion());
        line.put("retrieval_version", config.retrievalVersion());
        line.put("candidate_pipeline", config.candidatePipeline().wireValue());
        line.put("mmr_lambda", config.mmrLambda());
        line.put("candidate_k", config.candidateK());
        line.put("top_n", config.topN());
        line.put("reranker", config.reranker());
        line.put("jev_model", config.jevModel());
        line.put("jev_prompt_version", config.jevPromptVersion());
        line.put("score_strategy", config.scoreStrategy().wireValue());
        line.put("query_id", queryId);
        line.put("reranker_runtime_name", output.rerankerName());
        line.put("ranking", rankingAsList(output.ranking()));
        line.put("scores_by_candidate_id", output.scoresByCandidateId());
        if (output.jevDetails() != null && !output.jevDetails().isEmpty()) {
            line.put("jev_details", jevDetailsAsMap(output.jevDetails()));
        }
        RerankTelemetry t = output.telemetry();
        Map<String, Object> tele = new LinkedHashMap<>();
        tele.put("latency_ms", t.latencyMs());
        tele.put("input_tokens", t.inputTokens());
        tele.put("output_tokens", t.outputTokens());
        tele.put("cost_usd", t.costUsd());
        tele.put("cache_hits", t.cacheHits());
        tele.put("cache_misses", t.cacheMisses());
        line.put("telemetry", tele);
        return line;
    }

    private List<Map<String, Object>> rankingAsList(List<RankedItem> items) {
        java.util.List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (RankedItem it : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("new_rank", it.newRank());
            m.put("original_rank", it.originalRank());
            m.put("candidate_id", it.candidateId());
            m.put("score", it.score());
            list.add(m);
        }
        return list;
    }

    private Map<String, Object> jevDetailsAsMap(Map<String, JevRerankDetail> details) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, JevRerankDetail> e : details.entrySet()) {
            JevRerankDetail d = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("choice", d.choice());
            m.put("probabilities", d.probabilities());
            m.put("confidence", d.confidence());
            m.put("score_choice", d.scoreChoice());
            m.put("score_expected", d.scoreExpected());
            m.put("score_normalized", d.scoreNormalized());
            out.put(e.getKey(), m);
        }
        return out;
    }
}